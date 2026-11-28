//! 来源 zip 的中央目录扫描：只读元数据，不解压内容。
//!
//! S2 的入口：打开来源后只枚举中央目录（名称、CRC、大小、压缩方法、数据偏移），
//! 内容按需读取（JSON 合并、SHA-1 判定、无法直拷的压缩方法）。

use flate2::read::DeflateDecoder;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

use serde_json::Value;
use sha1::{Digest, Sha1};
use zip::{CompressionMethod, ZipArchive};

use crate::error::{MergeError, Result, err};

/// 单条目解压后的大小上限：64MB。
pub const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
/// stored 条目只做字节直拷，不需要整条读进内存，因此可以放宽得多。
pub const MAX_RAW_ENTRY_BYTES: u64 = 1024 * 1024 * 1024;
/// 所有来源累计（以及输出）条目数上限。
pub const MAX_TOTAL_ENTRIES: usize = 500_000;

/// 直拷读取缓冲：小条目连续读取时合并系统调用。
const RAW_BUFFER: usize = 256 * 1024;

/// 大于这个体积的来源才提示顺序读：小包是「多文件、随机 seek」，顺序提示反而伤命中率。
const SEQUENTIAL_HINT_BYTES: u64 = 8 * 1024 * 1024;

/// 打开来源文件：大文件在 Windows 下带 FILE_FLAG_SEQUENTIAL_SCAN。
fn open_source(path: &Path) -> std::io::Result<File> {
    let large = std::fs::metadata(path)
        .map(|meta| meta.len() >= SEQUENTIAL_HINT_BYTES)
        .unwrap_or(false);
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    if large {
        use std::os::windows::fs::OpenOptionsExt;
        options.custom_flags(0x0800_0000);
    }
    options.open(path)
}

/// 中央目录里的一条元数据；不含内容。
#[derive(Debug, Clone)]
pub struct EntryMeta {
    pub index: usize,
    pub name: String,
    pub crc: u32,
    pub size: u64,
    pub compressed: u64,
    /// 数据区在来源文件里的绝对偏移。
    pub offset: u64,
    /// 可直接直拷的压缩方法：0 = store，8 = deflate；其他方法需要解压后重压。
    pub raw_method: Option<u16>,
}

/// 一个来源：中央目录 + 按需读取的句柄。
pub struct SourceReader {
    pub id: String,
    pub priority: i64,
    pub path: PathBuf,
    pub mcmeta: Option<Value>,
    pub entries: Vec<EntryMeta>,
    /// 扫描期持有；pack.mcmeta 读完即释放，省下 zip crate 自己那份中央目录。
    archive: Option<ZipArchive<BufReader<File>>>,
    raw: BufReader<File>,
    /// raw 句柄的真实偏移；由 RawSlice 在 drop 时回写，连续条目无需重复 seek。
    raw_pos: Arc<AtomicU64>,
}

/// 并行扫描的任务描述：每个来源一个，结果按 spec 顺序返回。
pub struct SourceSpec {
    pub id: String,
    pub priority: i64,
    pub path: PathBuf,
}

/// 并行度：来源少于 4 个保持 1；默认留一半核给 JVM、最多 4，环境变量 RPS_THREADS 可覆盖。
pub fn scan_threads(sources: usize) -> usize {
    if sources < 4 {
        return 1;
    }
    if let Ok(text) = std::env::var("RPS_THREADS") {
        if let Ok(value) = text.trim().parse::<usize>() {
            return value.clamp(1, 8);
        }
    }
    std::thread::available_parallelism()
        .map(|parallelism| (parallelism.get() / 2).clamp(1, 4))
        .unwrap_or(1)
}

/// 按 specs 顺序扫描多个来源；并行时结果仍按原顺序收集，错误按顺序报告（确定性）。
pub fn open_sources(specs: &[SourceSpec], threads: usize) -> Result<Vec<SourceReader>> {
    if threads <= 1 || specs.len() <= 1 {
        let mut readers = Vec::with_capacity(specs.len());
        let scanned = AtomicUsize::new(0);
        for spec in specs {
            readers.push(SourceReader::open(
                &spec.path,
                &spec.id,
                spec.priority,
                &scanned,
            )?);
        }
        return Ok(readers);
    }
    let mut slots: Vec<Option<Result<SourceReader>>> = (0..specs.len()).map(|_| None).collect();
    let chunk = specs.len().div_ceil(threads).max(1);
    let scanned = AtomicUsize::new(0);
    std::thread::scope(|scope| {
        for (spec_chunk, slot_chunk) in specs.chunks(chunk).zip(slots.chunks_mut(chunk)) {
            let scanned = &scanned;
            scope.spawn(move || {
                for (spec, slot) in spec_chunk.iter().zip(slot_chunk.iter_mut()) {
                    *slot = Some(SourceReader::open(
                        &spec.path,
                        &spec.id,
                        spec.priority,
                        scanned,
                    ));
                }
            });
        }
    });
    let mut readers = Vec::with_capacity(specs.len());
    let mut total = 0usize;
    for slot in slots {
        let reader = match slot {
            Some(result) => result?,
            None => return err("并行扫描没有产出结果".to_string()),
        };
        total += reader.entries.len();
        readers.push(reader);
    }
    if total > MAX_TOTAL_ENTRIES {
        return err(format!("所有来源累计条目数超过上限 {MAX_TOTAL_ENTRIES}"));
    }
    Ok(readers)
}

impl SourceReader {
    /// 打开一个来源并扫描中央目录；不读取任何条目内容。
    pub fn open(
        path: &Path,
        source_id: &str,
        priority: i64,
        scanned_total: &AtomicUsize,
    ) -> Result<Self> {
        let file = open_source(path).map_err(|error| {
            MergeError::new(format!(
                "来源 {source_id} 打不开 {}: {error}",
                path.display()
            ))
        })?;
        let raw = std::fs::File::open(path).map_err(|error| {
            MergeError::new(format!("来源 {source_id} 打开读取句柄失败: {error}"))
        })?;
        let raw = BufReader::with_capacity(RAW_BUFFER, raw);
        let mut archive = ZipArchive::new(BufReader::new(file))
            .map_err(|error| MergeError::new(format!("来源 {source_id} 不是合法 zip: {error}")))?;

        if archive.len() > MAX_TOTAL_ENTRIES {
            return err(format!(
                "来源 {source_id} 条目数 {} 超过上限 {MAX_TOTAL_ENTRIES}",
                archive.len()
            ));
        }
        let previous = scanned_total.fetch_add(archive.len(), Ordering::Relaxed);
        if previous + archive.len() > MAX_TOTAL_ENTRIES {
            return err(format!("所有来源累计条目数超过上限 {MAX_TOTAL_ENTRIES}"));
        }

        let mut entries: Vec<EntryMeta> = Vec::with_capacity(archive.len());
        for index in 0..archive.len() {
            let entry = archive.by_index_raw(index).map_err(|error| {
                MergeError::new(format!(
                    "来源 {source_id} 第 {index} 个条目读取失败: {error}"
                ))
            })?;
            if entry.encrypted() {
                return err(format!(
                    "来源 {source_id} 条目 {} 已加密，无法合并",
                    entry
                        .name()
                        .map(|name| name.to_string())
                        .unwrap_or_else(|_| "<未知>".to_string())
                ));
            }
            if entry.is_dir() {
                continue;
            }
            let name = entry
                .name()
                .map_err(|error| {
                    MergeError::new(format!(
                        "来源 {source_id} 第 {index} 个条目名称无效: {error}"
                    ))
                })?
                .to_string();
            validate_entry_name(&name).map_err(|problem| {
                MergeError::new(format!("来源 {source_id} 条目 {name} 不安全：{problem}"))
            })?;
            let size = entry.size();
            let method = match entry.compression() {
                CompressionMethod::Stored => Some(0),
                CompressionMethod::Deflated => Some(8),
                _ => None,
            };
            // Stored 条目声明的大小必须与数据长度一致，否则直拷会写出坏包，退回解压路径。
            let stored_mismatch = matches!(method, Some(0)) && entry.compressed_size() != size;
            let method = if stored_mismatch { None } else { method };
            let offset = entry.data_start();
            let copyable = matches!(method, Some(0)) && offset.is_some();
            if copyable && size > MAX_RAW_ENTRY_BYTES {
                return err(format!(
                    "来源 {source_id} 条目 {name} 解压后 {size} 字节，超过单条目直拷上限 {MAX_RAW_ENTRY_BYTES}"
                ));
            }
            if !copyable && size > MAX_ENTRY_BYTES {
                let reason = if stored_mismatch {
                    "（stored 声明的压缩长度与原始长度不一致，退回解压路径）"
                } else {
                    "；只有 stored 条目能直拷"
                };
                return err(format!(
                    "来源 {source_id} 条目 {name} 解压后 {size} 字节，超过可解压上限 {MAX_ENTRY_BYTES}{reason}"
                ));
            }
            entries.push(EntryMeta {
                index,
                name,
                crc: entry.crc32(),
                size,
                compressed: entry.compressed_size(),
                offset: offset.unwrap_or(0),
                raw_method: if offset.is_some() { method } else { None },
            });
        }

        let mut reader = SourceReader {
            id: source_id.to_string(),
            priority,
            path: path.to_path_buf(),
            mcmeta: None,
            entries,
            archive: Some(archive),
            raw,
            raw_pos: Arc::new(AtomicU64::new(0)),
        };
        if let Some(position) = reader
            .entries
            .iter()
            .position(|meta| meta.name == "pack.mcmeta")
        {
            let meta = reader.entries[position].clone();
            if meta.size > MAX_ENTRY_BYTES {
                return err(format!(
                    "来源 {source_id} 的 pack.mcmeta 有 {} 字节，超过解析上限 {MAX_ENTRY_BYTES}",
                    meta.size
                ));
            }
            let bytes = reader.read(&meta)?;
            let parsed = serde_json::from_slice::<Value>(&bytes).map_err(|error| {
                MergeError::new(format!(
                    "来源 {source_id} 的 pack.mcmeta 不是合法 JSON: {error}"
                ))
            })?;
            reader.mcmeta = Some(parsed);
        }
        Ok(reader)
    }

    /// 取中央目录；已释放过就按需重开（只在需要解压/哈希时才付这份代价）。
    fn archive(&mut self, source_id: &str) -> Result<&mut ZipArchive<BufReader<File>>> {
        if self.archive.is_none() {
            let file = open_source(&self.path).map_err(|error| {
                MergeError::new(format!(
                    "来源 {source_id} 重开失败 {}: {error}",
                    self.path.display()
                ))
            })?;
            let archive = ZipArchive::new(BufReader::new(file)).map_err(|error| {
                MergeError::new(format!("来源 {source_id} 不是合法 zip: {error}"))
            })?;
            self.archive = Some(archive);
        }
        Ok(self.archive.as_mut().expect("刚装好"))
    }

    /// 这一层处理完就放手；后续若需要内容会按需重开。
    pub fn release_archive(&mut self) {
        self.archive = None;
    }

    /// 决策结束后释放扫描期元数据：写出只需要原始偏移与压缩长度。
    pub fn release_scan_metadata(&mut self) {
        self.entries = Vec::new();
        self.mcmeta = None;
        self.archive = None;
    }

    /// 解压一个条目到内存；只走扫描期记下的原始偏移，避免与已释放的中央目录形成两个视图。
    pub fn read(&mut self, meta: &EntryMeta) -> Result<Vec<u8>> {
        let source_id = self.id.clone();
        if let Some(method) = meta.raw_method {
            return self.read_at(
                meta.offset,
                meta.compressed,
                method,
                meta.size,
                &meta.name,
                &source_id,
            );
        }
        let archive = self.archive(&source_id)?;
        let mut entry = archive.by_index(meta.index).map_err(|error| {
            MergeError::new(format!(
                "来源 {} 第 {} 个条目读取失败: {error}",
                source_id, meta.index
            ))
        })?;
        let actual = entry
            .name()
            .map_err(|error| MergeError::new(format!("来源 {source_id} 条目名读取失败: {error}")))?
            .into_owned();
        if actual != meta.name {
            return err(format!(
                "来源 {source_id} 在扫描后被改动：第 {} 个条目从 {} 变成了 {actual}",
                meta.index, meta.name
            ));
        }
        read_capped(entry.by_ref(), meta.size, &meta.name, &source_id)
    }

    /// 按原始偏移解压条目（决策阶段元数据已释放时使用）。
    pub fn read_raw(
        &mut self,
        name: &str,
        offset: u64,
        compressed: u64,
        method: u16,
        size: u64,
    ) -> Result<Vec<u8>> {
        let source_id = self.id.clone();
        self.read_at(offset, compressed, method, size, name, &source_id)
    }

    fn read_at(
        &mut self,
        offset: u64,
        compressed: u64,
        method: u16,
        declared: u64,
        name: &str,
        source_id: &str,
    ) -> Result<Vec<u8>> {
        let slice = self.raw_slice(offset, compressed)?;
        match method {
            0 => read_capped(slice, declared, name, source_id),
            8 => read_capped(DeflateDecoder::new(slice), declared, name, source_id),
            other => err(format!("来源 {source_id} 的压缩方式 {other} 暂不支持")),
        }
    }

    /// 流式计算条目内容 SHA-1：走原始偏移，不把内容留在内存里。
    pub fn sha1(
        &mut self,
        name: &str,
        offset: u64,
        compressed: u64,
        method: u16,
        size: u64,
    ) -> Result<String> {
        let source_id = self.id.clone();
        let slice = self.raw_slice(offset, compressed)?;
        match method {
            0 => hash_capped(slice, size, MAX_RAW_ENTRY_BYTES, name, &source_id),
            8 => hash_capped(DeflateDecoder::new(slice), size, MAX_ENTRY_BYTES, name, &source_id),
            other => err(format!("来源 {source_id} 的压缩方式 {other} 暂不支持")),
        }
    }

    /// 打开一段原始（已压缩）字节区间，供直拷写使用。
    pub fn raw_slice(&mut self, offset: u64, len: u64) -> Result<RawSlice<'_>> {
        let position = self.raw_pos.load(Ordering::Relaxed);
        if position != offset {
            let delta = offset.saturating_sub(position);
            if offset > position && delta <= RAW_BUFFER as u64 {
                // 相邻条目之间只隔一个局部头，前跳不丢缓冲。
                self.raw.seek_relative(delta as i64).map_err(|error| {
                    MergeError::new(format!("来源 {} 定位失败: {error}", self.id))
                })?;
            } else {
                self.raw.seek(SeekFrom::Start(offset)).map_err(|error| {
                    MergeError::new(format!("来源 {} 定位失败: {error}", self.id))
                })?;
            }
        }
        #[cfg(debug_assertions)]
        debug_assert_eq!(
            self.raw.stream_position().ok(),
            Some(offset),
            "来源 {} 的读取位置与记账不一致",
            self.id
        );
        Ok(RawSlice {
            inner: Read::by_ref(&mut self.raw).take(len),
            slot: Arc::clone(&self.raw_pos),
            offset,
            len,
        })
    }
}

/// 原始字节区间：drop 时把 BufReader 的真实位置写回，避免乐观偏移影响下一次 seek。
pub struct RawSlice<'a> {
    inner: std::io::Take<&'a mut BufReader<File>>,
    slot: Arc<AtomicU64>,
    offset: u64,
    len: u64,
}

impl Read for RawSlice<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.inner.read(buf)
    }
}

impl Drop for RawSlice<'_> {
    fn drop(&mut self) {
        // Take::limit() 是剩余未读字节：已读 = len - 剩余。纯算术，不额外 seek。
        let remaining = self.inner.limit();
        let consumed = self.len.saturating_sub(remaining);
        self.slot
            .store(self.offset.saturating_add(consumed), Ordering::Relaxed);
    }
}

fn read_capped<R: Read>(reader: R, declared: u64, name: &str, source_id: &str) -> Result<Vec<u8>> {
    // 只按 1MB 预分配：声明值来自来源 zip，不能被它诱导出 64MB 的瞬时分配。
    let mut buffer: Vec<u8> = Vec::with_capacity(declared.min(1 << 20) as usize);
    reader
        .take(MAX_ENTRY_BYTES + 1)
        .read_to_end(&mut buffer)
        .map_err(|error| MergeError::new(format!("来源 {source_id} 条目读取失败: {error}")))?;
    if buffer.len() as u64 > MAX_ENTRY_BYTES {
        return err(format!(
            "来源 {source_id} 条目 {name} 解压后超过单条目上限 {MAX_ENTRY_BYTES}"
        ));
    }
    if buffer.len() as u64 != declared {
        return err(format!(
            "来源 {source_id} 条目 {name} 长度不符：声明 {declared} 字节，实际 {} 字节",
            buffer.len()
        ));
    }
    Ok(buffer)
}

fn hash_capped<R: Read>(
    reader: R,
    expected: u64,
    cap: u64,
    name: &str,
    source_id: &str,
) -> Result<String> {
    let mut hasher = Sha1::new();
    let mut buffer = [0u8; 64 * 1024];
    let mut total = 0u64;
    let mut reader = reader.take(cap + 1);
    loop {
        let read = reader
            .read(&mut buffer)
            .map_err(|error| MergeError::new(format!("来源 {source_id} 条目解压失败: {error}")))?;
        if read == 0 {
            break;
        }
        total += read as u64;
        if total > cap {
            return err(format!(
                "来源 {source_id} 条目 {name} 解压后超过单条目上限 {cap}"
            ));
        }
        hasher.update(&buffer[..read]);
    }
    if total != expected {
        return err(format!(
            "来源 {source_id} 条目 {name} 长度不符：声明 {expected} 字节，实际 {total} 字节"
        ));
    }
    Ok(hex(&hasher.finalize()))
}

/// 条目名安全闸：拒绝 ..、绝对路径、盘符与 NUL。
pub fn validate_entry_name(name: &str) -> std::result::Result<(), &'static str> {
    if name.is_empty() {
        return Err("条目名为空");
    }
    let bytes = name.as_bytes();
    if bytes[0] == b'/' || bytes[0] == b'\\' {
        return Err("条目名以 / 开头（绝对路径）");
    }
    if name.split(['/', '\\']).any(|segment| segment == "..") {
        return Err("条目名包含 ..（zip slip）");
    }
    if bytes.len() >= 2 && bytes[1] == b':' && bytes[0].is_ascii_alphabetic() {
        return Err("条目名带盘符（绝对路径）");
    }
    if name.contains('\0') {
        return Err("条目名包含 NUL");
    }
    Ok(())
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push_str(&format!("{byte:02x}"));
    }
    text
}

#[allow(dead_code)]
fn _assert_write<T: Write>(_value: T) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_capped_rejects_short_and_long_entries() {
        let short = read_capped(std::io::Cursor::new(b"ab"), 3, "e", "src-0")
            .expect_err("声明 3 字节只给 2 字节应报错")
            .to_string();
        assert!(short.contains("长度不符"), "{short}");
        let long = read_capped(std::io::Cursor::new(b"abcd"), 3, "e", "src-0")
            .expect_err("声明 3 字节实给 4 字节应报错")
            .to_string();
        assert!(long.contains("长度不符"), "{long}");
    }
}
