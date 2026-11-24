//! 合并失败的错误类型。

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergeError {
    message: String,
}

impl MergeError {
    pub fn new(message: impl Into<String>) -> Self {
        MergeError {
            message: message.into(),
        }
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for MergeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for MergeError {}

pub type Result<T> = std::result::Result<T, MergeError>;

/// 便捷早退：`return err("...")`。
pub fn err<T>(message: impl Into<String>) -> Result<T> {
    Err(MergeError::new(message))
}
