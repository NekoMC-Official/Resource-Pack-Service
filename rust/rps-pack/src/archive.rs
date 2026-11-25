//! 来源 zip 的读取与安全闸：zip slip、单条目 64MB、总条目数 20000。

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

use serde_json::Value;
use zip::ZipArchive;

use crate::error::{err, MergeError, Result};

/// 单条目解压后的大小上限：64MB。
pub const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
/// 所有来源累计（以及输出）条目数上限。
pub const MAX_TOTAL_ENTRIES: usize = 20_000;

pub struct SourcePack {
    pub mcmeta: Option<Value>,
    pub entries: Vec<(String, Vec<u8>)>,
}

/// 读取一个来源 zip：校验条目名、限制体积与条目数，抽走 pack.mcmeta。
pub fn read_source(path: &Path, source_id: &str, read_total: &mut usize) -> Result<SourcePack> {
    let file = File::open(path)
        .map_err(|error| MergeError::new(format!("来源 {source_id} 打不开 {}: {error}", path.display())))?;
    let mut archive = ZipArchive::new(BufReader::new(file))
        .map_err(|error| MergeError::new(format!("来源 {source_id} 不是合法 zip: {error}")))?;

    if archive.len() > MAX_TOTAL_ENTRIES {
        return err(format!(
            "来源 {source_id} 条目数 {} 超过上限 {MAX_TOTAL_ENTRIES}",
            archive.len()
        ));
    }
    if *read_total + archive.len() > MAX_TOTAL_ENTRIES {
        return err(format!("所有来源累计条目数超过上限 {MAX_TOTAL_ENTRIES}"));
    }

    let mut mcmeta: Option<Value> = None;
    let mut entries: Vec<(String, Vec<u8>)> = Vec::new();

    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|error| {
            MergeError::new(format!("来源 {source_id} 第 {index} 个条目读取失败: {error}"))
        })?;
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
        validate_entry_name(&name)
            .map_err(|problem| MergeError::new(format!("来源 {source_id} 条目 {name} 不安全：{problem}")))?;
        let declared = entry.size();
        if declared > MAX_ENTRY_BYTES {
            return err(format!(
                "来源 {source_id} 条目 {name} 解压后 {declared} 字节，超过单条目上限 {MAX_ENTRY_BYTES}"
            ));
        }
        let mut buffer: Vec<u8> = Vec::new();
        Read::take(&mut entry, MAX_ENTRY_BYTES + 1)
            .read_to_end(&mut buffer)
            .map_err(|error| MergeError::new(format!("来源 {source_id} 条目 {name} 解压失败: {error}")))?;
        if buffer.len() as u64 > MAX_ENTRY_BYTES {
            return err(format!(
                "来源 {source_id} 条目 {name} 解压后超过单条目上限 {MAX_ENTRY_BYTES}"
            ));
        }
        *read_total += 1;
        if *read_total > MAX_TOTAL_ENTRIES {
            return err(format!("所有来源累计条目数超过上限 {MAX_TOTAL_ENTRIES}"));
        }
        if name == "pack.mcmeta" {
            let parsed = serde_json::from_slice::<Value>(&buffer).map_err(|error| {
                MergeError::new(format!("来源 {source_id} 的 pack.mcmeta 不是合法 JSON: {error}"))
            })?;
            mcmeta = Some(parsed);
            continue;
        }
        entries.push((name, buffer));
    }

    Ok(SourcePack { mcmeta, entries })
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
    if name.contains("..") {
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
