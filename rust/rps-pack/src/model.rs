//! 请求、报告与冲突的数据结构（对应 design.md 第 6 节的 JSON 契约）。

use std::collections::BTreeMap;

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
    /// 直拷来源条目时是否解压校验 CRC（默认关闭，开启会放弃直拷的性能收益）。
    #[serde(default)]
    pub verify_sources: bool,
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

/// 资源条目的类型，决定冲突的默认严重度与展示分组。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
pub enum AssetKind {
    #[serde(rename = "texture")]
    Texture,
    #[serde(rename = "texture-meta")]
    TextureMeta,
    #[serde(rename = "model")]
    Model,
    #[serde(rename = "blockstate")]
    BlockState,
    #[serde(rename = "item-model")]
    ItemModel,
    #[serde(rename = "lang")]
    Lang,
    #[serde(rename = "sound")]
    Sound,
    #[serde(rename = "font")]
    Font,
    #[serde(rename = "atlas")]
    Atlas,
    #[serde(rename = "shader")]
    Shader,
    #[serde(rename = "metadata")]
    Metadata,
    #[default]
    #[serde(rename = "other")]
    Other,
}

impl AssetKind {
    /// 只按 ZIP 路径分类，不读取文件内容；大小写按 Minecraft 资源路径习惯忽略。
    pub fn of(name: &str) -> Self {
        if name == "pack.mcmeta" || name == "pack.png" {
            return AssetKind::Metadata;
        }
        let lower = name.to_ascii_lowercase();
        let Some(rest) = lower.strip_prefix("assets/") else {
            return AssetKind::Other;
        };
        let Some((_namespace, tail)) = rest.split_once('/') else {
            return AssetKind::Other;
        };
        if let Some(texture) = tail.strip_prefix("textures/") {
            return if texture.ends_with(".mcmeta") {
                AssetKind::TextureMeta
            } else {
                AssetKind::Texture
            };
        }
        if tail.starts_with("models/") && tail.ends_with(".json") {
            return AssetKind::Model;
        }
        if tail.starts_with("blockstates/") && tail.ends_with(".json") {
            return AssetKind::BlockState;
        }
        if tail.starts_with("items/") && tail.ends_with(".json") {
            return AssetKind::ItemModel;
        }
        if tail.starts_with("lang/") && tail.ends_with(".json") {
            return AssetKind::Lang;
        }
        if tail == "sounds.json" {
            return AssetKind::Sound;
        }
        if tail.starts_with("font/") && tail.ends_with(".json") {
            return AssetKind::Font;
        }
        if tail.starts_with("atlases/") && tail.ends_with(".json") {
            return AssetKind::Atlas;
        }
        if tail.starts_with("shaders/") {
            return AssetKind::Shader;
        }
        AssetKind::Other
    }

    pub fn as_str(self) -> &'static str {
        match self {
            AssetKind::Texture => "texture",
            AssetKind::TextureMeta => "texture-meta",
            AssetKind::Model => "model",
            AssetKind::BlockState => "blockstate",
            AssetKind::ItemModel => "item-model",
            AssetKind::Lang => "lang",
            AssetKind::Sound => "sound",
            AssetKind::Font => "font",
            AssetKind::Atlas => "atlas",
            AssetKind::Shader => "shader",
            AssetKind::Metadata => "metadata",
            AssetKind::Other => "other",
        }
    }

    pub fn severity(self) -> Severity {
        match self {
            AssetKind::Texture
            | AssetKind::TextureMeta
            | AssetKind::Model
            | AssetKind::BlockState
            | AssetKind::ItemModel => Severity::High,
            AssetKind::Lang
            | AssetKind::Sound
            | AssetKind::Font
            | AssetKind::Atlas => Severity::Medium,
            AssetKind::Shader | AssetKind::Metadata | AssetKind::Other => Severity::Low,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    High,
    Medium,
    #[default]
    Low,
}

impl Severity {
    /// 排序用：数值越小越严重。
    pub fn rank(self) -> u8 {
        match self {
            Severity::High => 0,
            Severity::Medium => 1,
            Severity::Low => 2,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Severity::High => "high",
            Severity::Medium => "medium",
            Severity::Low => "low",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum ConflictMode {
    #[default]
    Replace,
    JsonDeep,
    JsonConcat,
    First,
}

/// 冲突中的一侧：来源 id、优先级与内容指纹。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConflictSource {
    pub id: String,
    pub priority: i64,
    pub sha1: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Conflict {
    pub path: String,
    pub winner: String,
    pub losers: Vec<String>,
    pub kind: AssetKind,
    pub severity: Severity,
    pub mode: ConflictMode,
    pub winner_priority: i64,
    pub loser_priorities: Vec<i64>,
    pub winner_sha1: String,
    pub loser_sha1s: Vec<String>,
    pub same_priority: bool,
    /// JSON 语义冲突的字段说明；路径覆盖冲突为空。
    pub fields: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ConflictSummary {
    pub total: usize,
    pub high: usize,
    pub medium: usize,
    pub low: usize,
    pub by_kind: BTreeMap<String, usize>,
    pub truncated: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct Report {
    pub ok: bool,
    pub sha1: String,
    pub bytes: u64,
    pub entries: usize,
    pub deduped: usize,
    /// 所有胜出条目的解压后总字节数（压缩率分母）。
    #[serde(rename = "rawBytes")]
    pub raw_bytes: u64,
    /// 输出字节 / 解压后总字节，越小说明压缩越有效。
    pub ratio: f64,
    /// 必须重新压缩的条目数（JSON 合并结果与不支持的来源压缩方法）。
    pub recompressed: usize,
    /// 压缩无效、退回 store 的条目数。
    pub stored: usize,
    /// 直接拷贝来源压缩字节的条目数。
    #[serde(rename = "rawCopied")]
    pub raw_copied: usize,
    pub format: i64,
    pub format_min: i64,
    pub format_max: i64,
    pub conflicts: Vec<Conflict>,
    #[serde(rename = "conflictSummary")]
    pub conflict_summary: ConflictSummary,
    pub warnings: Vec<String>,
    pub millis: u64,
}

#[cfg(test)]
mod tests {
    use super::{AssetKind, Severity};

    #[test]
    fn classifies_texture_and_model_paths() {
        assert_eq!(
            AssetKind::of("assets/minecraft/textures/block/stone.png"),
            AssetKind::Texture
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/textures/block/stone.png.mcmeta"),
            AssetKind::TextureMeta
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/models/block/stone.json"),
            AssetKind::Model
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/blockstates/stone.json"),
            AssetKind::BlockState
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/items/stone.json"),
            AssetKind::ItemModel
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/textures/block/stone.png").severity(),
            Severity::High
        );
        assert_eq!(
            AssetKind::of("assets/minecraft/lang/zh_cn.json").severity(),
            Severity::Medium
        );
        assert_eq!(AssetKind::of("pack.png"), AssetKind::Metadata);
    }
}
