//! 顺序 zip writer：本地头 / 中央目录 / EOCD / zip64，字节级确定，单趟出 SHA-1。
//!
//! 两条设计约束：
//! - 写本地头之前必须已经知道 crc 与压缩后大小，所以 deflate 结果先落到内存
//!   （只有需要重压的条目才走这条路径，上限受 MAX_ENTRY_BYTES 约束）。
//! - 每个写出的字节都是最终字节，因此可以边写边算 SHA-1，省掉「写完再整包读一遍」
//!   的第二趟 I/O。SHA-1 默认交给独立线程：1 MiB 块写满即交，更新摘要与写出重叠；
//!   设 RPS_SYNC_HASH=1 可退回同线程计算（排障用）。时间戳固定 1980-01-01 00:00:00，
//!   权限固定 0644，不写注释与扩展字段，同样输入必然得到同样字节。
//!
//! 暂不支持单条目 >= 4 GiB（需要本地 zip64 头，S6 再补）；条目数超过 65535 或目录
//! 偏移超过 4 GiB 时自动写 zip64 EOCD。

use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::Path;
use std::sync::mpsc::{Receiver, SyncSender, sync_channel};
use std::thread::JoinHandle;

use flate2::Compression;
use flate2::write::DeflateEncoder;
use sha1::{Digest, Sha1};

use crate::error::{MergeError, Result};

const LOCAL_SIG: u32 = 0x0403_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;
const EOCD64_SIG: u32 = 0x0606_4b50;
const EOCD64_LOCATOR_SIG: u32 = 0x0706_4b50;
const ZIP64_EXTRA_ID: u16 = 0x0001;
const U32_MAX: u64 = 0xFFFF_FFFF;
/// DOS 时间 1980-01-01 00:00:00：日期位 0x0021，时间位 0。
const DOS_DATE: u16 = 0x0021;
const DOS_TIME: u16 = 0;
const VERSION_DEFAULT: u16 = 20;
const VERSION_ZIP64: u16 = 45;
/// 直拷来源字节时每次搬运的窗口。
const COPY_BUFFER: usize = 1 << 20;
/// 输出缓冲：流式写大包时减少系统调用。
const OUTPUT_BUFFER: usize = 1 << 20;
/// 哈希线程的搬运块：写满一块交出去，与下一块的写出重叠。
const HASH_BLOCK: usize = 1 << 20;
/// 待哈希队列深度与可回收块数，决定额外内存上限（约 (QUEUE+BLOCKS+1) MiB）。
const HASH_QUEUE: usize = 4;
const HASH_BLOCKS: usize = 6;

struct Entry {
    name: Vec<u8>,
    crc: u32,
    compressed: u64,
    uncompressed: u64,
    offset: u64,
    method: u16,
    flags: u16,
    version: u16,
}

impl Entry {
    fn needs_zip64(&self) -> bool {
        self.uncompressed >= U32_MAX || self.compressed >= U32_MAX || self.offset >= U32_MAX
    }
}

/// 一次合并的写出结果：调用方直接用 sha1 与字节数，不再回读输出文件。
#[derive(Debug, Clone)]
pub struct WriteStats {
    pub bytes: u64,
    pub sha1: String,
    pub recompressed: usize,
    pub stored: usize,
    pub raw_copied: usize,
}

pub struct ZipArchiveWriter {
    inner: BufWriter<File>,
    entries: Vec<Entry>,
    hash: HashSink,
    pos: u64,
    level: u32,
    scratch: Vec<u8>,
    header: Vec<u8>,
    central: Vec<u8>,
    recompressed: usize,
    stored: usize,
    raw_copied: usize,
}

impl ZipArchiveWriter {
    /// 创建输出文件；父目录不存在时自动创建。
    pub fn create(path: &Path, level: u32) -> Result<Self> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(|error| {
                    MergeError::new(format!("创建输出目录失败 {}: {error}", parent.display()))
                })?;
            }
        }
        let file = File::create(path).map_err(|error| {
            MergeError::new(format!("创建输出文件失败 {}: {error}", path.display()))
        })?;
        let hash = HashSink::new()?;
        // 直拷的临时缓冲只服务同线程哈希；线程模式下字节直接读进哈希块。
        let scratch = if hash.is_threaded() {
            Vec::new()
        } else {
            vec![0u8; COPY_BUFFER]
        };
        Ok(Self {
            inner: BufWriter::with_capacity(OUTPUT_BUFFER, file),
            entries: Vec::new(),
            hash,
            pos: 0,
            level,
            scratch,
            header: Vec::with_capacity(256),
            central: Vec::with_capacity(1 << 20),
            recompressed: 0,
            stored: 0,
            raw_copied: 0,
        })
    }

    /// 以 deflate 写入一个条目（固定走压缩，不判断收益）。
    pub fn add_deflated(&mut self, name: &str, data: &[u8]) -> Result<()> {
        let compressed = deflate(data, self.level, name)?;
        self.write_owned(
            name,
            crc32fast::hash(data),
            8,
            data.len() as u64,
            &compressed,
        )?;
        self.recompressed += 1;
        Ok(())
    }

    /// store 写入一个条目。
    pub fn add_stored(&mut self, name: &str, data: &[u8]) -> Result<()> {
        self.write_owned(name, crc32fast::hash(data), 0, data.len() as u64, data)?;
        self.stored += 1;
        Ok(())
    }

    /// 逐条目自适应：先按设定等级压缩，压不动就退回 store。
    ///
    /// 返回 true 表示写了 deflate 数据，false 表示退回 store。JSON 合并结果、
    /// 以及来源用了我们不支持的压缩方法的条目都会走这里。
    pub fn add_auto(&mut self, name: &str, data: &[u8]) -> Result<bool> {
        let compressed = deflate(data, self.level, name)?;
        if compressed.len() >= data.len() {
            self.add_stored(name, data)?;
            return Ok(false);
        }
        self.write_owned(
            name,
            crc32fast::hash(data),
            8,
            data.len() as u64,
            &compressed,
        )?;
        self.recompressed += 1;
        Ok(true)
    }

    /// 直拷来源 zip 里已压缩的字节（method 0/8），不重新压缩。
    pub fn add_raw<R: Read>(
        &mut self,
        name: &str,
        crc: u32,
        compressed: u64,
        uncompressed: u64,
        method: u16,
        data: &mut R,
    ) -> Result<()> {
        let name_bytes = name.as_bytes();
        validate_name(name_bytes, name)?;
        if !matches!(method, 0 | 8) {
            return Err(MergeError::new(format!(
                "条目 {name} 的压缩方法 {method} 不支持直接拷贝"
            )));
        }
        if method == 0 && compressed != uncompressed {
            return Err(MergeError::new(format!(
                "条目 {name} 的 stored 声明大小不一致：压缩 {compressed} 字节，原始 {uncompressed} 字节"
            )));
        }
        if uncompressed >= U32_MAX || compressed >= U32_MAX {
            return Err(MergeError::new(format!(
                "条目 {name} 超过 4 GiB，当前写入器尚未支持（S6）"
            )));
        }
        let flags = utf8_flag(name_bytes);
        let offset = self.pos;
        self.header.clear();
        local_header(
            &mut self.header,
            name_bytes,
            crc,
            compressed,
            uncompressed,
            method,
            flags,
            VERSION_DEFAULT,
        );
        emit(&mut self.inner, &mut self.hash, &self.header)
            .map_err(|error| MergeError::new(format!("写入条目 {name} 失败: {error}")))?;
        self.pos += self.header.len() as u64;

        let data_start = self.pos;
        let mut remaining = compressed;
        if self.hash.is_threaded() {
            // 直接读进哈希块：写出与哈希共用同一份字节，不再多一次拷贝。
            while remaining > 0 {
                let want = remaining.min(COPY_BUFFER as u64) as usize;
                let spare = self
                    .hash
                    .spare()
                    .ok_or_else(|| MergeError::new("哈希缓冲不可用"))?;
                let want = want.min(spare.len());
                let read = data.read(&mut spare[..want]).map_err(|error| {
                    MergeError::new(format!("读取条目 {name} 的数据失败: {error}"))
                })?;
                if read == 0 {
                    return Err(MergeError::new(format!(
                        "条目 {name} 压缩数据不完整：还差 {remaining} 字节"
                    )));
                }
                self.inner
                    .write_all(&spare[..read])
                    .map_err(|error| MergeError::new(format!("拷贝条目 {name} 失败: {error}")))?;
                self.pos += read as u64;
                remaining -= read as u64;
                self.hash.advance(read)?;
            }
        } else {
            while remaining > 0 {
                let want = remaining.min(COPY_BUFFER as u64) as usize;
                let read = data.read(&mut self.scratch[..want]).map_err(|error| {
                    MergeError::new(format!("读取条目 {name} 的数据失败: {error}"))
                })?;
                if read == 0 {
                    return Err(MergeError::new(format!(
                        "条目 {name} 压缩数据不完整：还差 {remaining} 字节"
                    )));
                }
                emit(&mut self.inner, &mut self.hash, &self.scratch[..read])
                    .map_err(|error| MergeError::new(format!("拷贝条目 {name} 失败: {error}")))?;
                self.pos += read as u64;
                remaining -= read as u64;
            }
        }
        self.pos = data_start + compressed;
        self.raw_copied += 1;
        self.entries.push(Entry {
            name: name_bytes.to_vec(),
            crc,
            compressed,
            uncompressed,
            offset,
            method,
            flags,
            version: VERSION_DEFAULT,
        });
        Ok(())
    }

    /// 写中央目录、zip64 EOCD（如需）与普通 EOCD，返回整包指纹。
    pub fn finish(mut self) -> Result<WriteStats> {
        let central_start = self.pos;
        for index in 0..self.entries.len() {
            if self.central.len() >= (1 << 20) {
                let buffered = std::mem::take(&mut self.central);
                self.emit(&buffered)?;
                self.central = buffered;
                self.central.clear();
            }
            central_record(&mut self.central, &self.entries[index]);
        }
        if !self.central.is_empty() {
            let buffered = std::mem::take(&mut self.central);
            self.emit(&buffered)?;
            self.central = buffered;
            self.central.clear();
        }
        let central_size = self.pos - central_start;
        let count = self.entries.len() as u64;
        let needs64 =
            count >= u16::MAX as u64 || central_size >= U32_MAX || central_start >= U32_MAX;

        if needs64 {
            let eocd64_offset = self.pos;
            let mut buffer = Vec::with_capacity(56);
            push_u32(&mut buffer, EOCD64_SIG);
            push_u64(&mut buffer, 44);
            push_u16(&mut buffer, VERSION_ZIP64);
            push_u16(&mut buffer, VERSION_ZIP64);
            push_u32(&mut buffer, 0);
            push_u32(&mut buffer, 0);
            push_u64(&mut buffer, count);
            push_u64(&mut buffer, count);
            push_u64(&mut buffer, central_size);
            push_u64(&mut buffer, central_start);
            self.emit(&buffer)?;

            let mut locator = Vec::with_capacity(20);
            push_u32(&mut locator, EOCD64_LOCATOR_SIG);
            push_u32(&mut locator, 0);
            push_u64(&mut locator, eocd64_offset);
            push_u32(&mut locator, 1);
            self.emit(&locator)?;
        }

        let mut eocd = Vec::with_capacity(22);
        push_u32(&mut eocd, EOCD_SIG);
        push_u16(&mut eocd, 0);
        push_u16(&mut eocd, 0);
        push_u16(&mut eocd, count.min(u16::MAX as u64) as u16);
        push_u16(&mut eocd, count.min(u16::MAX as u64) as u16);
        push_u32(&mut eocd, central_size.min(U32_MAX) as u32);
        push_u32(&mut eocd, central_start.min(U32_MAX) as u32);
        push_u16(&mut eocd, 0);
        self.emit(&eocd)?;

        self.inner
            .flush()
            .map_err(|error| MergeError::new(format!("刷新输出失败: {error}")))?;
        let sha1 = self.hash.finish()?;
        Ok(WriteStats {
            bytes: self.pos,
            sha1,
            recompressed: self.recompressed,
            stored: self.stored,
            raw_copied: self.raw_copied,
        })
    }

    fn write_owned(
        &mut self,
        name: &str,
        crc: u32,
        method: u16,
        uncompressed: u64,
        payload: &[u8],
    ) -> Result<()> {
        let name_bytes = name.as_bytes();
        validate_name(name_bytes, name)?;
        if uncompressed >= U32_MAX {
            return Err(MergeError::new(format!(
                "条目 {name} 超过 4 GiB，当前写入器尚未支持（S6）"
            )));
        }
        let compressed = payload.len() as u64;
        if compressed >= U32_MAX {
            return Err(MergeError::new(format!(
                "条目 {name} 压缩后超过 4 GiB，当前写入器尚未支持（S6）"
            )));
        }
        let flags = utf8_flag(name_bytes);
        let offset = self.pos;
        self.header.clear();
        local_header(
            &mut self.header,
            name_bytes,
            crc,
            compressed,
            uncompressed,
            method,
            flags,
            VERSION_DEFAULT,
        );
        emit(&mut self.inner, &mut self.hash, &self.header)
            .map_err(|error| MergeError::new(format!("写入条目 {name} 失败: {error}")))?;
        self.pos += self.header.len() as u64;
        if !payload.is_empty() {
            emit(&mut self.inner, &mut self.hash, payload)
                .map_err(|error| MergeError::new(format!("写入条目 {name} 失败: {error}")))?;
        }
        self.pos += compressed;
        self.entries.push(Entry {
            name: name_bytes.to_vec(),
            crc,
            compressed,
            uncompressed,
            offset,
            method,
            flags,
            version: VERSION_DEFAULT,
        });
        Ok(())
    }

    fn emit(&mut self, bytes: &[u8]) -> Result<()> {
        emit(&mut self.inner, &mut self.hash, bytes)
            .map_err(|error| MergeError::new(format!("写入输出失败: {error}")))?;
        self.pos += bytes.len() as u64;
        Ok(())
    }
}

fn validate_name(name: &[u8], display: &str) -> Result<()> {
    if display.split(['/', '\\']).any(|segment| segment == "..") {
        return Err(MergeError::new(format!(
            "条目名 {display} 包含 ..（zip slip）"
        )));
    }
    if name.is_empty() {
        return Err(MergeError::new("条目名不能为空"));
    }
    if name.len() > u16::MAX as usize {
        return Err(MergeError::new(format!("条目名过长：{display}")));
    }
    Ok(())
}

fn utf8_flag(name: &[u8]) -> u16 {
    if name.is_ascii() { 0 } else { 0x0800 }
}

fn deflate(data: &[u8], level: u32, name: &str) -> Result<Vec<u8>> {
    let mut compressed = Vec::with_capacity(data.len() / 2 + 64);
    let mut encoder = DeflateEncoder::new(&mut compressed, Compression::new(level));
    encoder
        .write_all(data)
        .map_err(|error| MergeError::new(format!("压缩条目 {name} 失败: {error}")))?;
    encoder
        .finish()
        .map_err(|error| MergeError::new(format!("结束压缩条目 {name} 失败: {error}")))?;
    Ok(compressed)
}

fn emit<W: Write>(inner: &mut W, hash: &mut HashSink, data: &[u8]) -> Result<()> {
    inner
        .write_all(data)
        .map_err(|error| MergeError::new(format!("写入输出失败: {error}")))?;
    hash.update(data)
}

/// SHA-1 的计算位置：默认独立线程（写一块交一块），RPS_SYNC_HASH=1 时退回当前线程。
enum HashSink {
    Inline(Sha1),
    Threaded(ThreadedHash),
}

impl HashSink {
    fn new() -> Result<Self> {
        let sync = std::env::var("RPS_SYNC_HASH")
            .map(|value| !value.is_empty() && value != "0")
            .unwrap_or(false);
        if sync {
            return Ok(Self::Inline(Sha1::new()));
        }
        // 线程起不来不值得让整次合成失败：退回同线程，字节与摘要完全一致。
        match ThreadedHash::start() {
            Ok(threaded) => Ok(Self::Threaded(threaded)),
            Err(error) => {
                eprintln!("rps-pack: 哈希线程不可用（{error}），退回同线程计算");
                Ok(Self::Inline(Sha1::new()))
            }
        }
    }

    fn is_threaded(&self) -> bool {
        matches!(self, Self::Threaded(_))
    }

    /// 当前块里尚未填满的尾部；内联模式没有块，返回 None。
    fn spare(&mut self) -> Option<&mut [u8]> {
        match self {
            Self::Threaded(threaded) => Some(threaded.spare()),
            Self::Inline(_) => None,
        }
    }

    fn advance(&mut self, count: usize) -> Result<()> {
        match self {
            Self::Threaded(threaded) => threaded.advance(count),
            Self::Inline(_) => Ok(()),
        }
    }

    fn update(&mut self, data: &[u8]) -> Result<()> {
        match self {
            Self::Inline(hasher) => {
                hasher.update(data);
                Ok(())
            }
            Self::Threaded(threaded) => threaded.push(data),
        }
    }

    fn finish(self) -> Result<String> {
        match self {
            Self::Inline(hasher) => Ok(hex(&hasher.finalize().to_vec())),
            Self::Threaded(threaded) => threaded.finish(),
        }
    }
}

struct ThreadedHash {
    sender: Option<SyncSender<(Vec<u8>, usize)>>,
    recycle: Receiver<Vec<u8>>,
    handle: Option<JoinHandle<Vec<u8>>>,
    block: Vec<u8>,
    filled: usize,
}

impl ThreadedHash {
    fn start() -> Result<Self> {
        let (sender, receiver) = sync_channel::<(Vec<u8>, usize)>(HASH_QUEUE);
        let (returned, recycle) = sync_channel::<Vec<u8>>(HASH_BLOCKS);
        let handle = std::thread::Builder::new()
            .name("rps-pack-hash".into())
            .spawn(move || {
                let mut hasher = Sha1::new();
                while let Ok((buffer, filled)) = receiver.recv() {
                    hasher.update(&buffer[..filled]);
                    let _ = returned.try_send(buffer);
                }
                hasher.finalize().to_vec()
            })
            .map_err(|error| MergeError::new(format!("启动哈希线程失败: {error}")))?;
        Ok(Self {
            sender: Some(sender),
            recycle,
            handle: Some(handle),
            block: vec![0u8; HASH_BLOCK],
            filled: 0,
        })
    }

    fn spare(&mut self) -> &mut [u8] {
        &mut self.block[self.filled..]
    }

    fn advance(&mut self, count: usize) -> Result<()> {
        self.filled += count;
        if self.filled == self.block.len() {
            self.flush()?;
        }
        Ok(())
    }

    fn push(&mut self, data: &[u8]) -> Result<()> {
        let mut rest = data;
        while !rest.is_empty() {
            let take = rest.len().min(self.spare().len());
            self.spare()[..take].copy_from_slice(&rest[..take]);
            rest = &rest[take..];
            self.advance(take)?;
        }
        Ok(())
    }

    fn flush(&mut self) -> Result<()> {
        if self.filled == 0 {
            return Ok(());
        }
        let buffer = std::mem::replace(&mut self.block, Vec::new());
        let filled = self.filled;
        self.filled = 0;
        let Some(sender) = self.sender.as_ref() else {
            self.block = vec![0u8; HASH_BLOCK];
            return Err(MergeError::new("哈希线程已结束"));
        };
        if sender.send((buffer, filled)).is_err() {
            // 复位块，避免调用方吞掉这一次错误后 advance 在空块上空转。
            self.block = vec![0u8; HASH_BLOCK];
            return Err(MergeError::new("哈希线程提前退出，无法继续计算 SHA-1"));
        }
        // 回收块的长度一直是 HASH_BLOCK（只读不缩），直接复用，不再清零重写。
        self.block = match self.recycle.try_recv() {
            Ok(buffer) => buffer,
            Err(_) => vec![0u8; HASH_BLOCK],
        };
        Ok(())
    }

    fn finish(mut self) -> Result<String> {
        self.flush()?;
        drop(self.sender.take());
        let handle = self
            .handle
            .take()
            .ok_or_else(|| MergeError::new("哈希线程已结束"))?;
        let digest = handle
            .join()
            .map_err(|_| MergeError::new("哈希线程异常退出"))?;
        Ok(hex(&digest))
    }
}

fn local_header(
    buffer: &mut Vec<u8>,
    name: &[u8],
    crc: u32,
    compressed: u64,
    uncompressed: u64,
    method: u16,
    flags: u16,
    version: u16,
) {
    let placeholder32 = |value: u64| {
        if value >= U32_MAX {
            u32::MAX
        } else {
            value as u32
        }
    };
    buffer.clear();
    push_u32(buffer, LOCAL_SIG);
    push_u16(buffer, version);
    push_u16(buffer, flags);
    push_u16(buffer, method);
    push_u16(buffer, DOS_TIME);
    push_u16(buffer, DOS_DATE);
    push_u32(buffer, crc);
    push_u32(buffer, placeholder32(compressed));
    push_u32(buffer, placeholder32(uncompressed));
    push_u16(buffer, name.len() as u16);
    push_u16(buffer, 0);
    buffer.extend_from_slice(name);
}

fn central_record(buffer: &mut Vec<u8>, entry: &Entry) {
    let mut extra = Vec::new();
    let mut uncompressed32 = u32::MAX;
    let mut compressed32 = u32::MAX;
    let mut offset32 = u32::MAX;
    if entry.uncompressed >= U32_MAX {
        push_u64(&mut extra, entry.uncompressed);
    } else {
        uncompressed32 = entry.uncompressed as u32;
    }
    if entry.compressed >= U32_MAX {
        push_u64(&mut extra, entry.compressed);
    } else {
        compressed32 = entry.compressed as u32;
    }
    if entry.offset >= U32_MAX {
        push_u64(&mut extra, entry.offset);
    } else {
        offset32 = entry.offset as u32;
    }
    let mut extra_field = Vec::new();
    if !extra.is_empty() {
        push_u16(&mut extra_field, ZIP64_EXTRA_ID);
        push_u16(&mut extra_field, extra.len() as u16);
        extra_field.extend_from_slice(&extra);
    }
    let version = if entry.needs_zip64() {
        VERSION_ZIP64
    } else {
        entry.version
    };
    push_u32(buffer, CENTRAL_SIG);
    // 高字节 3 表示 Unix，权限位在 external attributes 里。
    push_u16(buffer, 0x0300 | version);
    push_u16(buffer, version);
    push_u16(buffer, entry.flags);
    push_u16(buffer, entry.method);
    push_u16(buffer, DOS_TIME);
    push_u16(buffer, DOS_DATE);
    push_u32(buffer, entry.crc);
    push_u32(buffer, compressed32);
    push_u32(buffer, uncompressed32);
    push_u16(buffer, entry.name.len() as u16);
    push_u16(buffer, extra_field.len() as u16);
    push_u16(buffer, 0);
    push_u16(buffer, 0);
    push_u16(buffer, 0);
    push_u32(buffer, 0o100644 << 16);
    push_u32(buffer, offset32);
    buffer.extend_from_slice(&entry.name);
    buffer.extend_from_slice(&extra_field);
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0F) as usize] as char);
    }
    out
}

fn push_u16(buffer: &mut Vec<u8>, value: u16) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn push_u32(buffer: &mut Vec<u8>, value: u32) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn push_u64(buffer: &mut Vec<u8>, value: u64) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload(len: usize) -> Vec<u8> {
        (0..len).map(|index| (index % 251) as u8).collect()
    }

    /// 线程路径用例的显式开关判断：对照运行（RPS_SYNC_HASH）时打印跳过原因，
    /// 而不是静默通过一个什么都没测的用例。
    fn assert_threaded(sink: &HashSink) -> bool {
        if sink.is_threaded() {
            return true;
        }
        eprintln!("RPS_SYNC_HASH 已设置，跳过线程路径用例");
        false
    }

    fn digest(data: &[u8]) -> String {
        let mut hasher = Sha1::new();
        hasher.update(data);
        hex(&hasher.finalize().to_vec())
    }

    #[test]
    fn threaded_hash_matches_inline_across_block_boundaries() {
        let data = payload(HASH_BLOCK * 2 + 12_345);
        let mut sink = HashSink::new().expect("启动哈希");
        if !assert_threaded(&sink) {
            return;
        }
        for chunk in data.chunks(700_000) {
            sink.update(chunk).expect("更新摘要");
        }
        assert_eq!(sink.finish().expect("收尾"), digest(&data));
    }

    #[test]
    fn threaded_hash_advance_matches_after_zero_copy_fill() {
        let data = payload(HASH_BLOCK + 4_096);
        let mut sink = HashSink::new().expect("启动哈希");
        if !assert_threaded(&sink) {
            return;
        }
        let mut written = 0;
        while written < data.len() {
            let spare = sink.spare().expect("哈希块");
            let take = (data.len() - written).min(spare.len());
            spare[..take].copy_from_slice(&data[written..written + take]);
            written += take;
            sink.advance(take).expect("交付块");
        }
        assert_eq!(sink.finish().expect("收尾"), digest(&data));
    }
}
