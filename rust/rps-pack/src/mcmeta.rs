//! pack.mcmeta 的格式区间解析与交集。

use serde_json::Value;

use crate::error::{err, Result};

/// 取一个来源声明的 supported_formats / pack_format，归一化成闭区间。
pub fn interval(mcmeta: &Value, source_id: &str) -> Result<(i64, i64)> {
    let pack = match mcmeta.get("pack").and_then(Value::as_object) {
        Some(pack) => pack,
        None => return err(format!("来源 {source_id} 的 pack.mcmeta 缺少 pack 对象")),
    };
    if let Some(supported) = pack.get("supported_formats") {
        return match parse_supported(supported) {
            Some(range) => Ok(range),
            None => err(format!(
                "来源 {source_id} 的 supported_formats 无法解析: {supported}"
            )),
        };
    }
    match pack.get("pack_format").and_then(Value::as_i64) {
        Some(format) => Ok((format, format)),
        None => err(format!(
            "来源 {source_id} 的 pack.mcmeta 同时缺少 pack_format 与 supported_formats"
        )),
    }
}

fn parse_supported(value: &Value) -> Option<(i64, i64)> {
    match value {
        Value::Number(number) => number.as_i64().map(|format| (format, format)),
        Value::Array(items) => {
            let mut formats: Vec<i64> = items.iter().filter_map(Value::as_i64).collect();
            if formats.is_empty() {
                return None;
            }
            formats.sort_unstable();
            let last = *formats.last()?;
            Some((formats[0], last))
        }
        Value::Object(object) => {
            let low = object.get("min_inclusive").and_then(Value::as_i64)?;
            let high = object.get("max_inclusive").and_then(Value::as_i64)?;
            Some((low.min(high), low.max(high)))
        }
        _ => None,
    }
}
