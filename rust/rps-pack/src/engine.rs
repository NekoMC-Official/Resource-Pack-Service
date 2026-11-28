//! 合并核心：中央目录扫描 → 层序决策 → 原始字节直拷写出。
//!
//! S2：不把来源整包读进内存。决策阶段只持有条目元数据与胜者索引；需要内容时
//! 才解压（JSON 合并、SHA-1 判定），写出阶段默认把来源里已压缩的字节直拷。

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use serde_json::Value;
use sha1::{Digest, Sha1};

use crate::error::{MergeError, Result, err};
use crate::jsonmerge;
use crate::mcmeta;
use crate::model::{
    AssetKind, Conflict, ConflictMode, ConflictSource, ConflictSummary, Policy, Report, Request,
    Severity,
};
use crate::scan::{EntryMeta, MAX_ENTRY_BYTES, MAX_RAW_ENTRY_BYTES, SourceReader, hex};
use crate::writer::{WriteStats, ZipArchiveWriter};

pub const PACK_MC_META: &str = "pack.mcmeta";
pub const PACK_ICON: &str = "pack.png";

/// 压缩级别固定，保证同样输入得到同样字节。
const DEFLATE_LEVEL: u32 = 6;
/// 报告里最多保留的冲突明细；汇总仍统计全部。
const MAX_CONFLICT_DETAILS: usize = 5000;
/// 单条冲突最多保留的字段差异；与 jsonmerge 的上限一致。
const MAX_CONFLICT_FIELDS: usize = 50;
/// 胜者条目的内容来源：直拷原始压缩字节，或内存里已合并出的字节。
enum Body {
    Raw {
        source: usize,
        offset: u64,
        compressed: u64,
    },
    Owned(Vec<u8>),
}

struct Winner {
    crc: u32,
    size: u64,
    /// 写出时使用的压缩方法：0 = store，8 = deflate。
    method: u16,
    sha1: Option<String>,
    body: Body,
    /// 层序：0 是最先叠的低层。
    layer: usize,
    /// 来源标签在内存里共享一份，40 万条目时不重复分配。
    source: Arc<str>,
    priority: i64,
}

impl Winner {
    fn from_meta(
        meta: &EntryMeta,
        rank: usize,
        source: &crate::model::Source,
        label: &Arc<str>,
        readers: &mut [SourceReader],
    ) -> Result<Self> {
        let body = match meta.raw_method {
            Some(_method) => Body::Raw {
                source: rank,
                offset: meta.offset,
                compressed: meta.compressed,
            },
            // 无法直拷的压缩方法（bzip2/zstd…）：现在解压，写出时重压。
            None => Body::Owned(readers[rank].read(&meta)?),
        };
        Ok(Winner {
            crc: meta.crc,
            size: meta.size,
            method: meta.raw_method.unwrap_or(8),
            sha1: None,
            body,
            layer: rank,
            source: Arc::clone(label),
            priority: source.priority,
        })
    }

    fn owned(bytes: Vec<u8>, layer: usize, source: &str, priority: i64) -> Self {
        Winner {
            crc: crc32fast::hash(&bytes),
            size: bytes.len() as u64,
            method: 8,
            sha1: None,
            body: Body::Owned(bytes),
            layer,
            source: Arc::from(source),
            priority,
        }
    }
}

fn sha1_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    hex(&hasher.finalize())
}

/// 只在需要判定时计算内容 SHA-1，并缓存。
fn ensure_sha1(winner: &mut Winner, readers: &mut [SourceReader], name: &str) -> Result<String> {
    if let Some(sha1) = &winner.sha1 {
        return Ok(sha1.clone());
    }
    let sha1 = match &winner.body {
        Body::Owned(bytes) => sha1_bytes(bytes),
        Body::Raw {
            source,
            offset,
            compressed,
            ..
        } => readers[*source].sha1(name, *offset, *compressed, winner.method, winner.size)?,
    };
    winner.sha1 = Some(sha1.clone());
    Ok(sha1)
}

/// 取条目内容（仅 JSON 合并用；冲突判定走 SHA-1），超过解压上限直接报错。
fn body_bytes(winner: &Winner, readers: &mut [SourceReader], name: &str) -> Result<Vec<u8>> {
    if winner.size > MAX_ENTRY_BYTES {
        return err(format!(
            "条目 {name} 有 {} 字节，超过逐字节比较上限 {MAX_ENTRY_BYTES}",
            winner.size
        ));
    }
    match &winner.body {
        Body::Owned(bytes) => Ok(bytes.clone()),
        Body::Raw {
            source,
            offset,
            compressed,
            ..
        } => Ok(readers[*source].read_raw(
            name,
            *offset,
            *compressed,
            winner.method,
            winner.size,
        )?),
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

fn side_of(
    winner: &mut Winner,
    readers: &mut [SourceReader],
    name: &str,
) -> Result<ConflictSource> {
    Ok(ConflictSource {
        id: winner.source.to_string(),
        priority: winner.priority,
        sha1: ensure_sha1(winner, readers, name)?,
        bytes: winner.size,
    })
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

    // 先只扫中央目录：拿到条目元数据与各来源的 pack.mcmeta。
    let specs: Vec<crate::scan::SourceSpec> = order
        .iter()
        .map(|&index| {
            let source = &request.sources[index];
            crate::scan::SourceSpec {
                id: source.id.clone(),
                priority: source.priority,
                path: resolve(project_root, &source.path),
            }
        })
        .collect();
    let scan_started = Instant::now();
    let mut readers = crate::scan::open_sources(&specs, crate::scan::scan_threads(specs.len()))?;
    // 来源标签预建一次，40 万条目共享同一份字符串。
    let labels: Vec<Arc<str>> = readers
        .iter()
        .map(|reader| Arc::from(reader.id.as_str()))
        .collect();
    let scan_ms = scan_started.elapsed().as_millis();

    let mut ranges: Vec<(String, i64, i64)> = Vec::new();
    for reader in &readers {
        if let Some(meta) = &reader.mcmeta {
            let (low, high) = mcmeta::interval(meta, &reader.id)?;
            ranges.push((reader.id.clone(), low, high));
        }
    }

    let (mut format_min, mut format_max) = match ranges.first() {
        Some((_, low, high)) => (*low, *high),
        None => {
            if request.format <= 0 {
                return err(
                    "所有来源都没有 pack.mcmeta，且请求里 format 为 0：无法确定 pack_format，请在请求里显式指定 format",
                );
            }
            (request.format, request.format)
        }
    };
    for (_, low, high) in ranges.iter().skip(1) {
        format_min = format_min.max(*low);
        format_max = format_max.min(*high);
    }
    if format_min > format_max {
        let detail = readers
            .iter()
            .zip(ranges.iter())
            .map(|(_reader, (id, low, high))| format!("{id}={low}..{high}"))
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

    let mut pack = serde_json::Map::new();
    pack.insert("pack_format".to_string(), serde_json::json!(format));
    pack.insert("min_format".to_string(), serde_json::json!(format_min));
    pack.insert("max_format".to_string(), serde_json::json!(format_max));
    // 26.x 客户端按 min_format 校验：只要区间下界落在 15..=64，
    // 就必须同时给出 supported_formats，否则判定元数据损坏并卸载整包。
    if format_min < 65 {
        pack.insert(
            "supported_formats".to_string(),
            serde_json::json!([format_min, format_max]),
        );
    }
    pack.insert(
        "description".to_string(),
        serde_json::json!(request.description),
    );
    let built = serde_json::json!({ "pack": pack });
    let built_bytes = serde_json::to_vec(&built)
        .map_err(|error| MergeError::new(format!("pack.mcmeta 序列化失败: {error}")))?;

    let mut entries: BTreeMap<String, Winner> = BTreeMap::new();
    let mut conflicts: BTreeMap<String, ConflictState> = BTreeMap::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut deduped = 0usize;

    for rank in 0..readers.len() {
        let source_index = order[rank];
        // 按来源逐个取走元数据：名字直接移进胜者表，不再逐条克隆一份字符串。
        let mut metas = std::mem::take(&mut readers[rank].entries);
        for meta in metas.drain(..) {
            if meta.name == PACK_MC_META {
                continue;
            }
            let duplicate = insert(
                &mut entries,
                &mut conflicts,
                request,
                rank,
                source_index,
                meta,
                &mut readers,
                &labels,
                &mut warnings,
            )?;
            if duplicate {
                deduped += 1;
            }
        }
        // 中央目录只在读取不支持直拷的条目时才需要；这一层处理完就放手。
        readers[rank].release_archive();
    }

    entries.insert(
        PACK_MC_META.to_string(),
        Winner::owned(built_bytes, usize::MAX, "rps-pack", i64::MAX),
    );
    warnings.extend(namespace_warnings(&entries));
    for reader in &mut readers {
        reader.release_scan_metadata();
    }

    let raw_bytes: u64 = entries.values().map(|winner| winner.size).sum();
    if std::env::var_os("RPS_TRACE").is_some() {
        eprintln!(
            "[rps-pack] scan={scan_ms}ms total={}ms",
            started.elapsed().as_millis()
        );
    }
    let entry_count = entries.len();
    let stats = write_zip(entries, &mut readers, output, request.verify_sources)?;
    let bytes = stats.bytes;
    let sha1_hex = stats.sha1.clone();
    let ratio = if raw_bytes == 0 {
        0.0
    } else {
        bytes as f64 / raw_bytes as f64
    };

    let total_conflicts = conflicts.len();
    let mut conflict_summary = summarize(&conflicts);
    conflict_summary.truncated = total_conflicts > MAX_CONFLICT_DETAILS;
    // 明细有上限时优先保留高严重度项，再按路径稳定排序，避免重要冲突被字典序挤掉。
    let mut ordered: Vec<(String, ConflictState)> = conflicts.into_iter().collect();
    ordered.sort_by(|left, right| {
        severity_rank(right.1.severity())
            .cmp(&severity_rank(left.1.severity()))
            .then_with(|| left.0.cmp(&right.0))
    });
    let detailed: Vec<Conflict> = ordered
        .into_iter()
        .take(MAX_CONFLICT_DETAILS)
        .map(|(path, state)| into_conflict(path, state))
        .collect();
    Ok(Report {
        ok: true,
        sha1: sha1_hex,
        bytes,
        entries: entry_count,
        deduped,
        format,
        format_min,
        format_max,
        conflicts: detailed,
        conflict_summary,
        warnings,
        raw_bytes,
        ratio,
        recompressed: stats.recompressed,
        stored: stats.stored,
        raw_copied: stats.raw_copied,
        millis: started.elapsed().as_millis() as u64,
    })
}

fn severity_rank(severity: Severity) -> u8 {
    match severity {
        Severity::High => 3,
        Severity::Medium => 2,
        Severity::Low => 1,
    }
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

#[allow(clippy::too_many_arguments)]
fn note_conflict(
    conflicts: &mut BTreeMap<String, ConflictState>,
    name: &str,
    winner: &mut Winner,
    loser: &mut Winner,
    kind: AssetKind,
    mode: ConflictMode,
    fields: Vec<String>,
    readers: &mut [SourceReader],
) -> Result<()> {
    let winner_side = side_of(winner, readers, name)?;
    let loser_side = side_of(loser, readers, name)?;
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
        return Ok(());
    }
    let mut state = ConflictState::new(winner_side, kind, mode, fields);
    state.add_loser(loser_side);
    conflicts.insert(name.to_string(), state);
    Ok(())
}

/// 把一个来源条目并入累计结果；返回是否命中内容去重。
#[allow(clippy::too_many_arguments)]
fn insert(
    entries: &mut BTreeMap<String, Winner>,
    conflicts: &mut BTreeMap<String, ConflictState>,
    request: &Request,
    rank: usize,
    source_index: usize,
    meta: EntryMeta,
    readers: &mut [SourceReader],
    labels: &[Arc<str>],
    warnings: &mut Vec<String>,
) -> Result<bool> {
    let source = &request.sources[source_index];
    let mut incoming = Winner::from_meta(&meta, rank, source, &labels[rank], readers)?;
    let name = meta.name;
    let previous = match entries.remove(&name) {
        Some(previous) => previous,
        None => {
            entries.insert(name, incoming);
            return Ok(false);
        }
    };
    let mut previous = previous;

    // 大小与 CRC 只做快速预筛；真正判重要求 SHA-1 也一致。
    if previous.size == incoming.size && previous.crc == incoming.crc {
        if ensure_sha1(&mut previous, readers, &name)?
            == ensure_sha1(&mut incoming, readers, &name)?
        {
            entries.insert(name, previous);
            return Ok(true);
        }
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
            let low_bytes = body_bytes(&previous, readers, &name)?;
            let high_bytes = body_bytes(&incoming, readers, &name)?;
            let low = serde_json::from_slice::<Value>(&low_bytes);
            let high = serde_json::from_slice::<Value>(&high_bytes);
            match (low, high) {
                (Ok(low), Ok(high)) => {
                    let fields = jsonmerge::conflicts(merge_kind, &low, &high);
                    let merged = jsonmerge::merge(merge_kind, low, high);
                    let merged_bytes = serde_json::to_vec(&merged).map_err(|error| {
                        MergeError::new(format!("{name} 合并后序列化失败: {error}"))
                    })?;
                    let mut merged_winner =
                        Winner::owned(merged_bytes, rank, &source.id, source.priority);
                    let mode = match merge_kind {
                        jsonmerge::MergeKind::Deep => ConflictMode::JsonDeep,
                        jsonmerge::MergeKind::Concat => ConflictMode::JsonConcat,
                    };
                    if !fields.is_empty() {
                        note_conflict(
                            conflicts,
                            &name,
                            &mut merged_winner,
                            &mut previous,
                            kind,
                            mode,
                            fields,
                            readers,
                        )?;
                    }
                    entries.insert(name, merged_winner);
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
                &mut incoming,
                &mut previous,
                kind,
                ConflictMode::Replace,
                Vec::new(),
                readers,
            )?;
            entries.insert(name, incoming);
            Ok(false)
        }
        Policy::First => {
            note_conflict(
                conflicts,
                &name,
                &mut previous,
                &mut incoming,
                kind,
                ConflictMode::Replace,
                Vec::new(),
                readers,
            )?;
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

fn namespace_warnings(entries: &BTreeMap<String, Winner>) -> Vec<String> {
    let mut owners: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (name, winner) in entries {
        if name == PACK_MC_META {
            continue;
        }
        if let Some(namespace) = namespace_of(name) {
            owners
                .entry(namespace.to_string())
                .or_default()
                .insert(winner.source.to_string());
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

/// 写出用的同目录临时文件：成功替换前一直由 Drop 负责清理，失败不会留下半成品。
struct TempOutput {
    path: PathBuf,
    keep: bool,
}

impl TempOutput {
    fn new(path: PathBuf) -> Self {
        Self { path, keep: false }
    }
}

impl Drop for TempOutput {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

fn write_zip(
    entries: BTreeMap<String, Winner>,
    readers: &mut [SourceReader],
    output: &Path,
    verify_sources: bool,
) -> Result<WriteStats> {
    for reader in readers.iter() {
        reader.check_unchanged()?;
    }
    let mut temp = TempOutput::new(temp_output(output));
    let mut writer = ZipArchiveWriter::create(&temp.path, DEFLATE_LEVEL)?;
    // 逐个取走：名字直接搬进 writer，BTreeMap 的节点与键随迭代释放。
    for (name, winner) in entries {
        let name = name.into_bytes();
        let display = std::str::from_utf8(&name)
            .map_err(|error| MergeError::new(format!("条目名不是合法 UTF-8: {error}")))?;
        match &winner.body {
            Body::Owned(bytes) => {
                writer.add_auto(&display, bytes)?;
            }
            Body::Raw {
                source,
                offset,
                compressed,
                ..
            } => {
                if verify_sources {
                    verify_raw(
                        &mut readers[*source],
                        &display,
                        *offset,
                        *compressed,
                        winner.method,
                        winner.crc,
                        winner.size,
                    )?;
                }
                let mut slice = readers[*source].raw_slice(*offset, *compressed)?;
                writer.add_raw(
                    &display,
                    winner.crc,
                    *compressed,
                    winner.size,
                    winner.method,
                    &mut slice,
                )?;
            }
        }
    }
    let stats = writer.finish()?;
    std::fs::rename(&temp.path, output).map_err(|error| {
        MergeError::new(format!("替换输出失败 {}: {error}", output.display()))
    })?;
    temp.keep = true;
    Ok(stats)
}

/// 同目录临时文件：写完再原子替换，失败由 TempOutput 清理。
fn temp_output(output: &Path) -> PathBuf {
    let name = output
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("merged.zip");
    output.with_file_name(format!(".{name}.{}.tmp", std::process::id()))
}

/// 解压并核对来源条目的 CRC 与大小；只在请求显式要求时执行。
fn verify_raw(
    reader: &mut SourceReader,
    name: &str,
    offset: u64,
    compressed: u64,
    method: u16,
    expected_crc: u32,
    expected_size: u64,
) -> Result<()> {
    let slice = reader.raw_slice(offset, compressed)?;
    let mut hasher = crc32fast::Hasher::new();
    let mut total = 0u64;
    let mut buffer = vec![0u8; 64 * 1024];
    match method {
        0 => {
            let mut source = std::io::BufReader::with_capacity(64 * 1024, slice);
            loop {
                let read = std::io::Read::read(&mut source, &mut buffer).map_err(|error| {
                    MergeError::new(format!("条目 {name} 校验读取失败: {error}"))
                })?;
                if read == 0 {
                    break;
                }
                hasher.update(&buffer[..read]);
                total += read as u64;
                if total > MAX_RAW_ENTRY_BYTES {
                    return err(format!(
                        "条目 {name} 解压后超过单条目直拷上限 {MAX_RAW_ENTRY_BYTES} 字节，拒绝校验"
                    ));
                }
            }
        }
        8 => {
            let mut decoder = flate2::read::DeflateDecoder::new(slice);
            loop {
                let read = std::io::Read::read(&mut decoder, &mut buffer).map_err(|error| {
                    MergeError::new(format!("条目 {name} 校验解压失败: {error}"))
                })?;
                if read == 0 {
                    break;
                }
                hasher.update(&buffer[..read]);
                total += read as u64;
                if total > MAX_ENTRY_BYTES {
                    return err(format!(
                        "条目 {name} 解压后超过单条目上限 {MAX_ENTRY_BYTES} 字节，拒绝校验"
                    ));
                }
            }
        }
        other => {
            return Err(MergeError::new(format!(
                "条目 {name} 的压缩方法 {other} 无法校验"
            )));
        }
    }
    let crc = hasher.finalize();
    if crc != expected_crc || total != expected_size {
        return Err(MergeError::new(format!(
            "条目 {name} 校验失败：CRC {crc:08x}/{expected_crc:08x}，大小 {total}/{expected_size}"
        )));
    }
    Ok(())
}
