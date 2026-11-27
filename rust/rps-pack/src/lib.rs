//! rps-pack：把多个资源包按优先级叠成一个确定性的 zip。
//!
//! 协议见 `docs/design.md` 第 4 节（合并语义）与第 6 节（rps-pack 协议）。

pub mod engine;
pub mod error;
pub mod jsonmerge;
pub mod mcmeta;
pub mod model;
pub mod scan;
pub mod writer;

pub use engine::merge;
pub use error::{MergeError, Result};
pub use model::{
    AssetKind, Conflict, ConflictMode, ConflictSource, ConflictSummary, Policy, Report, Request,
    Severity, Source,
};
