//! 可合并 JSON 的两种结构合并：深层对象合并、数组高优先拼接去重。
//!
//! 合并结果保持原有语义；`conflicts` 只负责找出同一语义键/同一 id 的不同值。

use std::collections::{BTreeMap, HashSet};

use serde_json::Value;

const MAX_FIELDS: usize = 50;
const MAX_FIELD_CHARS: usize = 120;

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

/// 返回语义冲突的字段说明；空表示只是正常合并/拼接。
///
/// 规则：
/// - Deep：两边同一路径都有值且不相等（数组按整体比较）。
/// - Concat：只报同一 `id` 的不同定义；不同条目正常拼接，不算冲突。
pub fn conflicts(kind: MergeKind, low: &Value, high: &Value) -> Vec<String> {
    let mut fields = Vec::new();
    match kind {
        MergeKind::Deep => deep_conflicts(low, high, "", &mut fields),
        MergeKind::Concat => concat_conflicts(low, high, "", &mut fields),
    }
    fields
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

fn deep_conflicts(low: &Value, high: &Value, path: &str, out: &mut Vec<String>) {
    if out.len() >= MAX_FIELDS {
        return;
    }
    match (low, high) {
        (Value::Object(low_map), Value::Object(high_map)) => {
            for (key, high_value) in high_map {
                if let Some(low_value) = low_map.get(key) {
                    deep_conflicts(low_value, high_value, &format!("{path}/{key}"), out);
                }
            }
        }
        _ if low != high => push_field(out, path, low, high),
        _ => {}
    }
}

fn concat_conflicts(low: &Value, high: &Value, path: &str, out: &mut Vec<String>) {
    if out.len() >= MAX_FIELDS {
        return;
    }
    match (low, high) {
        (Value::Object(low_map), Value::Object(high_map)) => {
            for (key, high_value) in high_map {
                if let Some(low_value) = low_map.get(key) {
                    concat_conflicts(low_value, high_value, &format!("{path}/{key}"), out);
                }
            }
        }
        (Value::Array(low_items), Value::Array(high_items)) => {
            let low_by_id: BTreeMap<String, &Value> = low_items
                .iter()
                .filter_map(|item| item_id(item).map(|id| (id, item)))
                .collect();
            for high_item in high_items {
                let Some(id) = item_id(high_item) else {
                    continue;
                };
                let Some(low_item) = low_by_id.get(&id) else {
                    continue;
                };
                if *low_item != high_item {
                    push_field(out, &format!("{path}[id={id}]"), low_item, high_item);
                }
            }
        }
        _ if low != high => push_field(out, path, low, high),
        _ => {}
    }
}

fn item_id(value: &Value) -> Option<String> {
    value
        .as_object()?
        .get("id")?
        .as_str()
        .map(ToString::to_string)
}

fn push_field(out: &mut Vec<String>, path: &str, low: &Value, high: &Value) {
    if out.len() >= MAX_FIELDS {
        return;
    }
    let pointer = if path.is_empty() { "/" } else { path };
    let mut field = format!("{pointer}: {} -> {}", short(low), short(high));
    if field.chars().count() > MAX_FIELD_CHARS {
        field = field
            .chars()
            .take(MAX_FIELD_CHARS.saturating_sub(1))
            .collect::<String>()
            + "…";
    }
    out.push(field);
}

fn short(value: &Value) -> String {
    let text = value.to_string();
    if text.chars().count() > 60 {
        text.chars().take(60).collect::<String>() + "…"
    } else {
        text
    }
}

#[cfg(test)]
mod tests {
    use super::{MergeKind, conflicts};

    #[test]
    fn deep_reports_same_key_difference() {
        let low = serde_json::json!({"a": "1", "b": "low", "nested": {"x": 1}});
        let high = serde_json::json!({"b": "high", "c": "3", "nested": {"x": 2}});
        let fields = conflicts(MergeKind::Deep, &low, &high);
        assert_eq!(fields.len(), 2);
        assert!(fields.iter().any(|field| field.starts_with("/b:")));
        assert!(fields.iter().any(|field| field.starts_with("/nested/x:")));
    }

    #[test]
    fn deep_ignores_disjoint_keys() {
        let low = serde_json::json!({"a": "1"});
        let high = serde_json::json!({"b": "2"});
        assert!(conflicts(MergeKind::Deep, &low, &high).is_empty());
    }

    #[test]
    fn concat_reports_same_id_difference_only() {
        let low = serde_json::json!({"providers": [{"id": "x", "type": "a"}, {"id": "y"}]});
        let high = serde_json::json!({"providers": [{"id": "x", "type": "b"}, {"id": "z"}]});
        let fields = conflicts(MergeKind::Concat, &low, &high);
        assert_eq!(fields.len(), 1);
        assert!(fields[0].contains("[id=x]"));
    }
}
