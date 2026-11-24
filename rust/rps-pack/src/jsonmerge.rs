//! 可合并 JSON 的两种结构合并：深层对象合并、数组高优先拼接去重。

use std::collections::HashSet;

use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MergeKind {
    /// assets/*/lang/*.json 与 assets/*/sounds.json：深层合并，高层覆盖同键。
    Deep,
    /// assets/*/font/*.json 与 assets/*/atlases/*.json：数组高层在前拼接，按序列化去重。
    Concat,
}

/// 判断条目是否属于可合并 JSON，以及用哪种合并方式。
pub fn kind_of(name: &str) -> Option<MergeKind> {
    let mut parts = name.split('/');
    if parts.next()? != "assets" {
        return None;
    }
    let namespace = parts.next()?;
    if namespace.is_empty() {
        return None;
    }
    let tail: Vec<&str> = parts.collect();
    match tail.as_slice() {
        ["sounds.json"] => Some(MergeKind::Deep),
        ["lang", file] if is_json(file) => Some(MergeKind::Deep),
        ["font", file] if is_json(file) => Some(MergeKind::Concat),
        ["atlases", file] if is_json(file) => Some(MergeKind::Concat),
        _ => None,
    }
}

fn is_json(file: &str) -> bool {
    file.ends_with(".json")
}

pub fn merge(kind: MergeKind, low: Value, high: Value) -> Value {
    match kind {
        MergeKind::Deep => deep(low, high),
        MergeKind::Concat => concat(low, high),
    }
}

fn deep(low: Value, high: Value) -> Value {
    match (low, high) {
        (Value::Object(mut base), Value::Object(overlay)) => {
            for (key, value) in overlay {
                let merged = match base.remove(&key) {
                    Some(existing) => deep(existing, value),
                    None => value,
                };
                base.insert(key, merged);
            }
            Value::Object(base)
        }
        (_, high) => high,
    }
}

fn concat(low: Value, high: Value) -> Value {
    match (low, high) {
        (Value::Object(mut base), Value::Object(overlay)) => {
            for (key, value) in overlay {
                let merged = match base.remove(&key) {
                    Some(existing) => concat(existing, value),
                    None => value,
                };
                base.insert(key, merged);
            }
            Value::Object(base)
        }
        (Value::Array(low_items), Value::Array(high_items)) => {
            let mut merged: Vec<Value> = Vec::with_capacity(low_items.len() + high_items.len());
            let mut seen: HashSet<String> = HashSet::new();
            for item in high_items.into_iter().chain(low_items.into_iter()) {
                let key = item.to_string();
                if seen.insert(key) {
                    merged.push(item);
                }
            }
            Value::Array(merged)
        }
        (_, high) => high,
    }
}
