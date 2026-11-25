//! 合并核心：层序叠加、去重、冲突策略、格式交集重建、确定性写出。

use std::collections::{BTreeMap, BTreeSet};
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::Instant;

use serde_json::Value;
use sha1::{Digest, Sha1};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipWriter};

use crate::archive::read_source;
use crate::crc::crc32;
use crate::error::{err, MergeError, Result};
use crate::jsonmerge;
use crate::mcmeta;
use crate::model::{
    AssetKind, Conflict, ConflictMode, ConflictSource, ConflictSummary, Policy, Report, Request,
    Severity,
};

pub const PACK_MC_META: &str = "pack.mcmeta";
pub const PACK_ICON: &str = "pack.png";

/// 压缩级别固定，保证同样输入得到同样字节。
const DEFLATE_LEVEL: i64 = 6;

/// 报告里最多保留的冲突明细；汇总仍统计全部。
const MAX_CONFLICT_DETAILS: usize = 5000;

/// 单条冲突最多保留的字段差异；与 jsonmerge 的上限一致。
const MAX_CONFLICT_FIELDS: usize = 50;

struct Blob {
    bytes: Vec<u8>,
    crc: u32,
    size: u64,
    sha1: OnceLock<String>,
    /// 层序：0 是最先叠的低层。
    layer: usize,
    source: String,
    priority: i64,
}

impl Blob {
    fn new(bytes: Vec<u8>, layer: usize, source: &str, priority: i64) -> Self {
        let crc = crc32(&bytes);
        let size = bytes.len() as u64;
        Blob {
            bytes,
            crc,
            size,
            sha1: OnceLock::new(),
            layer,
            source: source.to_string(),
            priority,
        }
    }

    /// 只在 CRC/大小相同时才计算 SHA-1，避免为每个条目付哈希成本。
    fn sha1(&self) -> &str {
        self.sha1.get_or_init(|| {
            let mut hasher = Sha1::new();
            hasher.update(&self.bytes);
            hasher
                .finalize()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect()
        })
    }
}

struct ConflictState {
    winner: ConflictSource,
    losers: Vec<ConflictSource>,
    kind: AssetKind,
    mode: ConflictMode,
    same_priority: bool,
    fields: Vec<String>,
}

impl ConflictState {
    fn new(
        winner: ConflictSource,
        kind: AssetKind,
        mode: ConflictMode,
        fields: Vec<String>,
    ) -> Self {
        ConflictState {
            winner,
            losers: Vec::new(),
            kind,
            mode,
            same_priority: false,
            fields,
        }
    }

    /// 语言/声音/字体/图集整文件替换时只算低危；只有同键或同 id 的语义差异才算中危。
    fn severity(&self) -> Severity {
        match self.mode {
            ConflictMode::Replace
                if matches!(
                    self.kind,
                    AssetKind::Lang | AssetKind::Sound | AssetKind::Font | AssetKind::Atlas
                ) =>
            {
                Severity::Low
            }
            _ => self.kind.severity(),
        }
    }

    fn add_loser(&mut self, loser: ConflictSource) {
        if loser.id == self.winner.id || self.losers.iter().any(|item| item.id == loser.id) {
            return;
        }
        self.same_priority |= loser.priority == self.winner.priority;
        self.losers.push(loser);
    }
}

fn side_of(blob: &Blob) -> ConflictSource {
    ConflictSource {
        id: blob.source.clone(),
        priority: blob.priority,
        sha1: blob.sha1().to_string(),
        bytes: blob.size,
    }
}

/// 合并所有来源并写出 zip，返回契约里的报告（不含 ok 之外的诊断）。
pub fn merge(request: &Request, project_root: &Path, output: &Path) -> Result<Report> {
    let started = Instant::now();

    if request.sources.is_empty() {
        return err("sources 为空：没有可合并的来源");
    }

    // priority 升序；同 priority 保持请求里的数组顺序。
    let mut order: Vec<usize> = (0..request.sources.len()).collect();
    order.sort_by_key(|&index| (request.sources[index].priority, index));

    let mut entries: BTreeMap<String, Blob> = BTreeMap::new();
    let mut conflicts: BTreeMap<String, ConflictState> = BTreeMap::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut ranges: Vec<(i64, i64)> = Vec::with_capacity(order.len());
    let mut deduped = 0usize;
    let mut read_total = 0usize;

    for (rank, &index) in order.iter().enumerate() {
        let source = &request.sources[index];
        let path = resolve(project_root, &source.path);
        let pack = read_source(&path, &source.id, &mut read_total)?;
        if let Some(meta) = &pack.mcmeta {
            ranges.push(mcmeta::interval(meta, &source.id)?);
        }
        for (name, bytes) in pack.entries {
            let duplicate = insert(
                &mut entries,
                &mut conflicts,
                request,
                rank,
                source,
                name,
                bytes,
                &mut warnings,
            )?;
            if duplicate {
                deduped += 1;
            }
        }
    }

    let (mut format_min, mut format_max) = match ranges.first() {
        Some((low, high)) => (*low, *high),
        None => {
            if request.format <= 0 {
                return err("所有来源都没有 pack.mcmeta，且请求里 format 为 0：无法确定 pack_format，请在请求里显式指定 format");
            }
            (request.format, request.format)
        }
    };
    for (low, high) in ranges.iter().skip(1) {
        format_min = format_min.max(*low);
        format_max = format_max.min(*high);
    }
    if format_min > format_max {
        let detail = order
            .iter()
            .zip(ranges.iter())
            .map(|(&index, (low, high))| format!("{}={low}..{high}", request.sources[index].id))
            .collect::<Vec<_>>()
            .join(", ");
        return err(format!("格式区间无交集：{detail}"));
    }

    let format = if request.format > 0 {
        request.format
    } else {
        format_max
    };
    if format <= 0 {
        return err("无法确定 pack_format：交集最大值为 0，请在请求里显式指定 format");
    }

    let built = serde_json::json!({
        "pack": {
            "pack_format": format,
            "supported_formats": [format_min, format_max],
            "description": request.description,
        }
    });
    let built_bytes = serde_json::to_vec(&built)
        .map_err(|error| MergeError::new(format!("pack.mcmeta 序列化失败: {error}")))?;
    entries.insert(
        PACK_MC_META.to_string(),
        Blob::new(built_bytes, usize::MAX, "rps-pack", i64::MAX),
    );

    warnings.extend(namespace_warnings(&entries));

    write_zip(&entries, output)?;
    let bytes = std::fs::read(output)
        .map_err(|error| MergeError::new(format!("读取输出文件失败 {}: {error}", output.display())))?;
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let sha1_hex = hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();

    let total_conflicts = conflicts.len();
    let mut conflict_summary = summarize(&conflicts);
    conflict_summary.truncated = total_conflicts > MAX_CONFLICT_DETAILS;

    Ok(Report {
        ok: true,
        sha1: sha1_hex,
        bytes: bytes.len() as u64,
        entries: entries.len(),
        deduped,
        format,
        format_min,
        format_max,
        conflicts: conflicts
            .into_iter()
            .take(MAX_CONFLICT_DETAILS)
            .map(|(path, state)| into_conflict(path, state))
            .collect(),
        conflict_summary,
        warnings,
        millis: started.elapsed().as_millis() as u64,
    })
}

fn summarize(conflicts: &BTreeMap<String, ConflictState>) -> ConflictSummary {
    let mut summary = ConflictSummary::default();
    for state in conflicts.values() {
        summary.total += 1;
        match state.severity() {
            Severity::High => summary.high += 1,
            Severity::Medium => summary.medium += 1,
            Severity::Low => summary.low += 1,
        }
        *summary
            .by_kind
            .entry(state.kind.as_str().to_string())
            .or_insert(0) += 1;
    }
    summary
}

fn into_conflict(path: String, state: ConflictState) -> Conflict {
    let severity = state.severity();
    let winner = state.winner;
    let losers = state.losers;
    Conflict {
        path,
        winner: winner.id,
        losers: losers.iter().map(|item| item.id.clone()).collect(),
        kind: state.kind,
        severity,
        mode: state.mode,
        winner_priority: winner.priority,
        loser_priorities: losers.iter().map(|item| item.priority).collect(),
        winner_sha1: winner.sha1,
        loser_sha1s: losers.iter().map(|item| item.sha1.clone()).collect(),
        same_priority: state.same_priority,
        fields: state.fields,
    }
}

fn note_conflict(
    conflicts: &mut BTreeMap<String, ConflictState>,
    name: &str,
    winner: &Blob,
    loser: &Blob,
    kind: AssetKind,
    mode: ConflictMode,
    fields: Vec<String>,
) {
    let winner_side = side_of(winner);
    let loser_side = side_of(loser);
    if let Some(state) = conflicts.get_mut(name) {
        if state.winner.id != winner_side.id {
            let previous = std::mem::replace(&mut state.winner, winner_side);
            state.add_loser(previous);
            state.kind = kind;
            state.mode = mode;
            state.fields.clear();
        }
        if !fields.is_empty() {
            if state.fields.is_empty() {
                state.fields = fields;
            } else {
                for field in fields {
                    if state.fields.len() >= MAX_CONFLICT_FIELDS {
                        break;
                    }
                    if !state.fields.contains(&field) {
                        state.fields.push(field);
                    }
                }
            }
        }
        state.add_loser(loser_side);
        return;
    }
    let mut state = ConflictState::new(winner_side, kind, mode, fields);
    state.add_loser(loser_side);
    conflicts.insert(name.to_string(), state);
}

/// 把一个来源条目并入累计结果；返回是否命中内容去重。
#[allow(clippy::too_many_arguments)]
fn insert(
    entries: &mut BTreeMap<String, Blob>,
    conflicts: &mut BTreeMap<String, ConflictState>,
    request: &Request,
    rank: usize,
    source: &crate::model::Source,
    name: String,
    bytes: Vec<u8>,
    warnings: &mut Vec<String>,
) -> Result<bool> {
    let incoming = Blob::new(bytes, rank, &source.id, source.priority);
    let previous = match entries.remove(&name) {
        Some(previous) => previous,
        None => {
            entries.insert(name, incoming);
            return Ok(false);
        }
    };

    // 大小与 CRC 只做快速预筛；真正判重要求 SHA-1 也一致。
    if previous.size == incoming.size
        && previous.crc == incoming.crc
        && previous.sha1() == incoming.sha1()
    {
        entries.insert(name, previous);
        return Ok(true);
    }

    // 同一个来源内部出现同名条目（异常 zip）：后写覆盖先写，不算冲突。
    if previous.layer == rank {
        entries.insert(name, incoming);
        return Ok(false);
    }

    let kind = AssetKind::of(&name);
    if kind == AssetKind::Metadata {
        entries.insert(name, incoming);
        return Ok(false);
    }

    if request.json_merge {
        if let Some(merge_kind) = jsonmerge::kind_of(&name) {
            let low = serde_json::from_slice::<Value>(&previous.bytes);
            let high = serde_json::from_slice::<Value>(&incoming.bytes);
            match (low, high) {
                (Ok(low), Ok(high)) => {
                    let fields = jsonmerge::conflicts(merge_kind, &low, &high);
                    let merged = jsonmerge::merge(merge_kind, low, high);
                    let merged_bytes = serde_json::to_vec(&merged).map_err(|error| {
                        MergeError::new(format!("{name} 合并后序列化失败: {error}"))
                    })?;
                    let merged_blob = Blob::new(merged_bytes, rank, &source.id, source.priority);
                    let mode = match merge_kind {
                        jsonmerge::MergeKind::Deep => ConflictMode::JsonDeep,
                        jsonmerge::MergeKind::Concat => ConflictMode::JsonConcat,
                    };
                    if !fields.is_empty() {
                        note_conflict(
                            conflicts,
                            &name,
                            &merged_blob,
                            &previous,
                            kind,
                            mode,
                            fields,
                        );
                    }
                    entries.insert(name, merged_blob);
                    return Ok(false);
                }
                _ => warnings.push(format!("{name}：JSON 解析失败，退化为按路径覆盖")),
            }
        }
    }

    match request.policy {
        Policy::Strict => err(format!(
            "路径冲突（strict）：{name} 内容不同，来自 {} 与 {}",
            previous.source, source.id
        )),
        Policy::Override => {
            note_conflict(
                conflicts,
                &name,
                &incoming,
                &previous,
                kind,
                ConflictMode::Replace,
                Vec::new(),
            );
            entries.insert(name, incoming);
            Ok(false)
        }
        Policy::First => {
            note_conflict(
                conflicts,
                &name,
                &previous,
                &incoming,
                kind,
                ConflictMode::Replace,
                Vec::new(),
            );
            entries.insert(name, previous);
            Ok(false)
        }
    }
}

fn namespace_of(name: &str) -> Option<&str> {
    let rest = name.strip_prefix("assets/")?;
    let (namespace, tail) = rest.split_once('/')?;
    if namespace.is_empty() || tail.is_empty() {
        return None;
    }
    Some(namespace)
}

fn namespace_warnings(entries: &BTreeMap<String, Blob>) -> Vec<String> {
    let mut owners: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (name, blob) in entries {
        if name == PACK_MC_META {
            continue;
        }
        if let Some(namespace) = namespace_of(name) {
            owners
                .entry(namespace.to_string())
                .or_default()
                .insert(blob.source.clone());
        }
    }
    owners
        .into_iter()
        .filter(|(_, sources)| sources.len() > 1)
        .map(|(namespace, sources)| {
            format!(
                "命名空间共用：assets/{namespace}/ 由 {} 共同提供（仅报告，不阻断）",
                sources.into_iter().collect::<Vec<_>>().join(", ")
            )
        })
        .collect()
}

fn resolve(project_root: &Path, raw: &str) -> PathBuf {
    let path = PathBuf::from(raw);
    if path.is_absolute() || raw.starts_with('/') || raw.starts_with('\\') {
        return path;
    }
    project_root.join(path)
}

fn write_zip(entries: &BTreeMap<String, Blob>, output: &Path) -> Result<()> {
    if let Some(parent) = output.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(|error| {
                MergeError::new(format!("创建输出目录失败 {}: {error}", parent.display()))
            })?;
        }
    }
    let file = File::create(output)
        .map_err(|error| MergeError::new(format!("创建输出文件失败 {}: {error}", output.display())))?;
    let mut writer = ZipWriter::new(BufWriter::new(file));
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .compression_level(Some(DEFLATE_LEVEL))
        .last_modified_time(DateTime::default())
        .unix_permissions(0o644)
        .large_file(false);
    for (name, blob) in entries {
        writer.start_file(name.as_str(), options).map_err(|error| {
            MergeError::new(format!("写入条目 {name} 失败: {error}"))
        })?;
        writer.write_all(&blob.bytes).map_err(|error| {
            MergeError::new(format!("写入条目 {name} 内容失败: {error}"))
        })?;
    }
    let mut inner = writer
        .finish()
        .map_err(|error| MergeError::new(format!("关闭输出 zip 失败: {error}")))?;
    inner
        .flush()
        .map_err(|error| MergeError::new(format!("刷新输出失败: {error}")))?;
    Ok(())
}
