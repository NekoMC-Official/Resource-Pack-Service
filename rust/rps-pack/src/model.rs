//! 请求、报告与冲突的数据结构（对应 design.md 第 6 节的 JSON 契约）。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Policy {
    #[default]
    Override,
    Strict,
    First,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Request {
    /// `> 0` 时覆盖重建出的 pack_format；否则取格式区间交集的最大值。
    #[serde(default)]
    pub format: i64,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub policy: Policy,
    #[serde(default = "default_json_merge")]
    pub json_merge: bool,
    #[serde(default)]
    pub sources: Vec<Source>,
}

fn default_json_merge() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize)]
pub struct Source {
    pub id: String,
    /// 相对项目根的路径或绝对路径。
    pub path: String,
    #[serde(default)]
    pub priority: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Conflict {
    pub path: String,
    pub winner: String,
    pub losers: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Report {
    pub ok: bool,
    pub sha1: String,
    pub bytes: u64,
    pub entries: usize,
    pub deduped: usize,
    pub format: i64,
    pub format_min: i64,
    pub format_max: i64,
    pub conflicts: Vec<Conflict>,
    pub warnings: Vec<String>,
    pub millis: u64,
}
