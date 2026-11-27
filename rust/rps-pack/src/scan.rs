//! 来源 zip 的中央目录扫描：只读元数据，不解压内容。
//!
//! S2 的入口：打开来源后只枚举中央目录（名称、CRC、大小、压缩方法、数据偏移），
//! 内容按需读取（JSON 合并、SHA-1 判定、无法直拷的压缩方法）。

use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use serde_json::Value;
use sha1::{Digest, Sha1};
use zip::{CompressionMethod, ZipArchive};

use crate::error::{err, MergeError, Result};

/// 单条目解压后的大小上限：64MB。
pub const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
/// 所有来源累计（以及输出）条目数上限。
pub const MAX_TOTAL_ENTRIES: usize = 20_000;

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
    archive: ZipArchive<BufReader<File>>,
    raw: File,
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
        let mut scanned = 0usize;
        for spec in specs {
            readers.push(SourceReader::open(&spec.path, &spec.id, spec.priority, &mut scanned)?);
        }
        return Ok(readers);
    }
    let mut slots: Vec<Option<Result<SourceReader>>> = (0..specs.len()).map(|_| None).collect();
    let chunk = specs.len().div_ceil(threads).max(1);
    std::thread::scope(|scope| {
        for (spec_chunk, slot_chunk) in specs.chunks(chunk).zip(slots.chunks_mut(chunk)) {
            scope.spawn(move || {
                let mut scanned = 0usize;
                for (spec, slot) in spec_chunk.iter().zip(slot_chunk.iter_mut()) {
                    *slot = Some(SourceReader::open(
                        &spec.path,
                        &spec.id,
                        spec.priority,
                        &mut scanned,
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
        scanned_total: &mut usize,
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
        let mut archive = ZipArchive::new(BufReader::new(file)).map_err(|error| {
            MergeError::new(format!("来源 {source_id} 不是合法 zip: {error}"))
        })?;

        if archive.len() > MAX_TOTAL_ENTRIES {
            return err(format!(
                "来源 {source_id} 条目数 {} 超过上限 {MAX_TOTAL_ENTRIES}",
                archive.len()
            ));
        }
        if *scanned_total + archive.len() > MAX_TOTAL_ENTRIES {
            return err(format!("所有来源累计条目数超过上限 {MAX_TOTAL_ENTRIES}"));
        }

        let mut entries: Vec<EntryMeta> = Vec::with_capacity(archive.len());
        for index in 0..archive.len() {
            let entry = archive.by_index_raw(index).map_err(|error| {
                MergeError::new(format!("来源 {source_id} 第 {index} 个条目读取失败: {error}"))
            })?;
            if entry.encrypted() {
                return err(format!(
                    "来源 {source_id} 条目 {} 已加密，无法合并",
                    entry.name().map(|name| name.to_string()).unwrap_or_else(|_| "<未知>".to_string())
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
            if size > MAX_ENTRY_BYTES {
                return err(format!(
                    "来源 {source_id} 条目 {name} 解压后 {size} 字节，超过单条目上限 {MAX_ENTRY_BYTES}"
                ));
            }
            let method = match entry.compression() {
                CompressionMethod::Stored => Some(0),
                CompressionMethod::Deflated => Some(8),
                _ => None,
            };
            // Stored 条目声明的大小必须与数据长度一致，否则直拷会写出坏包，退回解压路径。
            let method = if matches!(method, Some(0)) && entry.compressed_size() != size {
                None
            } else {
                method
            };
            let offset = entry.data_start();
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
        *scanned_total += entries.len();

        let mut reader = SourceReader {
            id: source_id.to_string(),
            priority,
            path: path.to_path_buf(),
            mcmeta: None,
            entries,
            archive,
            raw,
        };
        if let Some(position) = reader.entries.iter().position(|meta| meta.name == "pack.mcmeta") {
            let index = reader.entries[position].index;
            let bytes = reader.read(index)?;
            let parsed = serde_json::from_slice::<Value>(&bytes).map_err(|error| {
                MergeError::new(format!("来源 {source_id} 的 pack.mcmeta 不是合法 JSON: {error}"))
            })?;
            reader.mcmeta = Some(parsed);
        }
        Ok(reader)
    }

    /// 解压一个条目到内存；带 64MB 上限兜底。
    pub fn read(&mut self, index: usize) -> Result<Vec<u8>> {
        let mut entry = self.archive.by_index(index).map_err(|error| {
            MergeError::new(format!("来源 {} 第 {index} 个条目读取失败: {error}", self.id))
        })?;
        let declared = entry.size();
        let mut buffer: Vec<u8> = Vec::with_capacity(declared.min(MAX_ENTRY_BYTES) as usize);
        entry
            .by_ref()
            .take(MAX_ENTRY_BYTES + 1)
            .read_to_end(&mut buffer)
            .map_err(|error| {
                MergeError::new(format!(
                    "来源 {} 条目读取失败: {error}",
                    self.id
                ))
            })?;
        if buffer.len() as u64 > MAX_ENTRY_BYTES {
            return err(format!(
                "来源 {} 条目解压后超过单条目上限 {MAX_ENTRY_BYTES}",
                self.id
            ));
        }
        Ok(buffer)
    }

    /// 流式计算一个条目的内容 SHA-1（不把内容留在内存里）。
    pub fn sha1(&mut self, index: usize) -> Result<String> {
        let mut entry = self.archive.by_index(index).map_err(|error| {
            MergeError::new(format!("来源 {} 第 {index} 个条目读取失败: {error}", self.id))
        })?;
        let mut hasher = Sha1::new();
        let mut buffer = [0u8; 64 * 1024];
        let mut total = 0u64;
        loop {
            let read = entry.read(&mut buffer).map_err(|error| {
                MergeError::new(format!("来源 {} 条目解压失败: {error}", self.id))
            })?;
            if read == 0 {
                break;
            }
            total += read as u64;
            if total > MAX_ENTRY_BYTES {
                return err(format!(
                    "来源 {} 条目解压后超过单条目上限 {MAX_ENTRY_BYTES}",
                    self.id
                ));
            }
            hasher.update(&buffer[..read]);
        }
        Ok(hex(&hasher.finalize()))
    }

    /// 打开一段原始（已压缩）字节区间，供直拷写使用。
    pub fn raw_slice(&mut self, offset: u64, len: u64) -> Result<impl Read + '_> {
        self.raw
            .seek(SeekFrom::Start(offset))
            .map_err(|error| MergeError::new(format!("来源 {} 定位失败: {error}", self.id)))?;
        Ok(Read::by_ref(&mut self.raw).take(len))
    }
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
