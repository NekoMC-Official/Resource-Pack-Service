//! rps-pack 合并语义的集成测试：fixture 全部用 zip crate 现场生成，不依赖外部文件。

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use rps_pack::{AssetKind, ConflictMode, Policy, Request, Severity, Source, merge};
use sha1::{Digest, Sha1};

fn work(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("target")
        .join("test-work")
        .join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("创建测试目录");
    dir
}

fn mcmeta_bytes(pack_format: i64, supported: Option<serde_json::Value>) -> Vec<u8> {
    let mut pack = serde_json::json!({
        "pack_format": pack_format,
        "description": "fixture",
    });
    if let Some(supported) = supported {
        pack["supported_formats"] = supported;
    }
    serde_json::to_vec(&serde_json::json!({ "pack": pack })).expect("序列化 fixture pack.mcmeta")
}

fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
    let file = std::fs::File::create(path).expect("创建 fixture zip");
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in entries {
        zip.start_file(*name, options).expect("写条目");
        zip.write_all(bytes).expect("写条目内容");
    }
    zip.finish().expect("关闭 fixture zip");
}

fn write_source(
    path: &Path,
    pack_format: i64,
    supported: Option<serde_json::Value>,
    entries: &[(&str, &[u8])],
) {
    let file = std::fs::File::create(path).expect("创建 fixture zip");
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    zip.start_file("pack.mcmeta", options)
        .expect("写 pack.mcmeta");
    zip.write_all(&mcmeta_bytes(pack_format, supported))
        .expect("写 pack.mcmeta 内容");
    for (name, bytes) in entries {
        zip.start_file(*name, options).expect("写条目");
        zip.write_all(bytes).expect("写条目内容");
    }
    zip.finish().expect("关闭 fixture zip");
}

fn source(id: &str, path: &Path, priority: i64) -> Source {
    Source {
        id: id.to_string(),
        path: path.to_string_lossy().into_owned(),
        priority,
    }
}

fn request(sources: Vec<Source>) -> Request {
    Request {
        format: 0,
        description: "测试资源包".to_string(),
        policy: Policy::Override,
        json_merge: true,
        verify_sources: false,
        sources,
    }
}

fn read_zip(path: &Path) -> Vec<(String, Vec<u8>)> {
    let file = std::fs::File::open(path).expect("打开输出 zip");
    let mut archive = zip::ZipArchive::new(file).expect("输出是合法 zip");
    let mut entries = Vec::new();
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).expect("读取输出条目");
        let name = entry.name().unwrap().to_string();
        let mut buffer = Vec::new();
        entry.read_to_end(&mut buffer).expect("读取输出条目内容");
        entries.push((name, buffer));
    }
    entries
}

fn read_timestamps(path: &Path) -> Vec<(String, Option<zip::DateTime>)> {
    let file = std::fs::File::open(path).expect("打开输出 zip");
    let mut archive = zip::ZipArchive::new(file).expect("输出是合法 zip");
    let mut stamps = Vec::new();
    for index in 0..archive.len() {
        let entry = archive.by_index(index).expect("读取输出条目");
        stamps.push((entry.name().unwrap().to_string(), entry.last_modified()));
    }
    stamps
}

fn entry<'a>(entries: &'a [(String, Vec<u8>)], name: &str) -> Option<&'a [u8]> {
    entries
        .iter()
        .find(|(entry_name, _)| entry_name == name)
        .map(|(_, bytes)| bytes.as_slice())
}

fn merge_ok(
    request: &Request,
    dir: &Path,
    out: &Path,
) -> (rps_pack::Report, Vec<(String, Vec<u8>)>) {
    let report = merge(request, dir, out).expect("合并应当成功");
    let entries = read_zip(out);
    (report, entries)
}

#[test]
fn single_source_passthrough() {
    let dir = work("single-source");
    let base = dir.join("base.zip");
    write_source(
        &base,
        46,
        None,
        &[
            ("assets/minecraft/texts/splash.txt", b"hello".as_slice()),
            ("pack.png", b"base-logo".as_slice()),
        ],
    );
    let out = dir.join("out.zip");
    let (report, entries) = merge_ok(&request(vec![source("base", &base, 0)]), &dir, &out);

    assert_eq!(report.format, 46);
    assert_eq!((report.format_min, report.format_max), (46, 46));
    assert_eq!(report.entries, 3, "两个文件 + 重建的 pack.mcmeta");
    assert_eq!(report.deduped, 0);
    assert!(report.conflicts.is_empty());
    assert_eq!(
        entry(&entries, "assets/minecraft/texts/splash.txt"),
        Some(b"hello".as_slice())
    );
    assert_eq!(entry(&entries, "pack.png"), Some(b"base-logo".as_slice()));

    let mcmeta: serde_json::Value =
        serde_json::from_slice(entry(&entries, "pack.mcmeta").expect("输出含 pack.mcmeta"))
            .expect("pack.mcmeta 是 JSON");
    assert_eq!(mcmeta["pack"]["pack_format"], 46);
    assert_eq!(
        mcmeta["pack"]["supported_formats"],
        serde_json::json!([46, 46])
    );

    let names: Vec<&str> = entries.iter().map(|(name, _)| name.as_str()).collect();
    let mut sorted = names.clone();
    sorted.sort_unstable();
    assert_eq!(names, sorted, "输出条目必须按名称升序写入");

    for (name, modified) in read_timestamps(&out) {
        assert_eq!(
            modified,
            Some(zip::DateTime::default()),
            "条目 {name} 的时间戳必须固定为 1980-01-01 00:00:00"
        );
    }
}

#[test]
fn two_layer_override_conflict() {
    let dir = work("override");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/models/block/a.json";
    write_source(&low, 46, None, &[(path, br#"{"from":"low"}"#.as_slice())]);
    write_source(&high, 46, None, &[(path, br#"{"from":"high"}"#.as_slice())]);
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1);
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.path, path);
    assert_eq!(conflict.winner, "high");
    assert_eq!(conflict.losers, vec!["low".to_string()]);
    assert_eq!(conflict.kind, AssetKind::Model);
    assert_eq!(conflict.severity, Severity::High);
    assert_eq!(conflict.mode, ConflictMode::Replace);
    assert_eq!(conflict.winner_priority, 10);
    assert_eq!(conflict.loser_priorities, vec![0]);
    assert!(!conflict.same_priority);
    assert_eq!(conflict.winner_sha1.len(), 40);
    assert_eq!(report.conflict_summary.high, 1);
    assert_eq!(report.deduped, 0);
    assert_eq!(
        entry(&entries, path),
        Some(br#"{"from":"high"}"#.as_slice()),
        "override 策略下高层胜"
    );
}

#[test]
fn identical_entries_are_deduped() {
    let dir = work("dedupe");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/texts/same.txt";
    write_source(&low, 46, None, &[(path, b"same-bytes".as_slice())]);
    write_source(&high, 46, None, &[(path, b"same-bytes".as_slice())]);
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.deduped, 1, "CRC 与大小一致应计一次去重");
    assert!(report.conflicts.is_empty(), "内容相同不算冲突");
    assert_eq!(report.entries, 2);
    assert_eq!(entry(&entries, path), Some(b"same-bytes".as_slice()));
}

#[test]
fn lang_json_is_deep_merged() {
    let dir = work("lang-merge");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/lang/en_us.json";
    write_source(
        &low,
        46,
        None,
        &[(
            path,
            br#"{"a":"1","b":"low","nested":{"x":1,"keep":true}}"#.as_slice(),
        )],
    );
    write_source(
        &high,
        46,
        None,
        &[(path, br#"{"b":"high","c":"3","nested":{"y":2}}"#.as_slice())],
    );
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1, "同一键不同值是语义冲突");
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.kind, AssetKind::Lang);
    assert_eq!(conflict.severity, Severity::Medium);
    assert_eq!(conflict.mode, ConflictMode::JsonDeep);
    assert!(
        conflict.fields.iter().any(|field| field.starts_with("/b:")),
        "应指出 /b 字段差异: {:?}",
        conflict.fields
    );
    assert_eq!(report.conflict_summary.medium, 1);
    let merged: serde_json::Value =
        serde_json::from_slice(entry(&entries, path).expect("lang 条目存在")).expect("合法 JSON");
    assert_eq!(
        merged,
        serde_json::json!({
            "a": "1",
            "b": "high",
            "c": "3",
            "nested": {"x": 1, "keep": true, "y": 2},
        })
    );
}

#[test]
fn font_arrays_are_concatenated_high_first() {
    let dir = work("font-merge");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/font/default.json";
    write_source(
        &low,
        46,
        None,
        &[(
            path,
            br#"{"providers":[{"type":"a"},{"type":"shared"}]}"#.as_slice(),
        )],
    );
    write_source(
        &high,
        46,
        None,
        &[(
            path,
            br#"{"providers":[{"type":"shared"},{"type":"b"}]}"#.as_slice(),
        )],
    );
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert!(report.conflicts.is_empty());
    let merged: serde_json::Value =
        serde_json::from_slice(entry(&entries, path).expect("font 条目存在")).expect("合法 JSON");
    assert_eq!(
        merged,
        serde_json::json!({
            "providers": [{"type": "shared"}, {"type": "b"}, {"type": "a"}],
        }),
        "高层数组在前，按序列化结果去重"
    );
}

#[test]
fn format_intersection_empty_fails() {
    let dir = work("format-empty");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    write_source(&low, 5, Some(serde_json::json!([1, 5])), &[]);
    write_source(&high, 9, Some(serde_json::json!([6, 9])), &[]);
    let out = dir.join("out.zip");

    let error = merge(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    )
    .expect_err("区间无交集必须失败");
    assert!(
        error.to_string().contains("无交集"),
        "错误应说明格式区间无交集: {error}"
    );
    assert!(!out.exists(), "失败时不应留下输出文件");
}

#[test]
fn zip_slip_entries_are_rejected() {
    let dir = work("zip-slip");
    let traversal = dir.join("traversal.zip");
    write_source(&traversal, 46, None, &[("../evil.txt", b"boom".as_slice())]);
    let error = merge(
        &request(vec![source("evil", &traversal, 0)]),
        &dir,
        &dir.join("a.zip"),
    )
    .expect_err("包含 .. 的条目必须拒绝");
    assert!(
        error.to_string().contains(".."),
        "错误应指出 zip slip: {error}"
    );

    let absolute = dir.join("absolute.zip");
    write_source(&absolute, 46, None, &[("/etc/passwd", b"boom".as_slice())]);
    let error = merge(
        &request(vec![source("evil", &absolute, 0)]),
        &dir,
        &dir.join("b.zip"),
    )
    .expect_err("绝对路径条目必须拒绝");
    assert!(
        error.to_string().contains("绝对路径"),
        "错误应指出绝对路径条目: {error}"
    );
}

#[test]
fn same_input_twice_produces_same_sha1() {
    let dir = work("determinism");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    write_source(
        &low,
        46,
        Some(serde_json::json!([40, 60])),
        &[
            ("assets/minecraft/texts/one.txt", b"one".as_slice()),
            (
                "assets/minecraft/lang/zh_cn.json",
                r#"{"a":"1","b":"低"}"#.as_bytes(),
            ),
        ],
    );
    write_source(
        &high,
        60,
        Some(serde_json::json!([50, 90])),
        &[
            ("assets/minecraft/texts/two.txt", b"two".as_slice()),
            (
                "assets/minecraft/lang/zh_cn.json",
                r#"{"b":"高","c":"3"}"#.as_bytes(),
            ),
        ],
    );
    let request = request(vec![source("low", &low, 0), source("high", &high, 10)]);

    let first_out = dir.join("first.zip");
    let second_out = dir.join("second.zip");
    let first = merge(&request, &dir, &first_out).expect("第一次合并");
    let second = merge(&request, &dir, &second_out).expect("第二次合并");

    assert_eq!(first.sha1, second.sha1, "同样输入两次必须得到相同 sha1");
    assert_eq!(first.bytes, second.bytes);
    assert_eq!(
        std::fs::read(&first_out).expect("读第一次输出"),
        std::fs::read(&second_out).expect("读第二次输出"),
        "输出字节必须完全一致"
    );
}

#[test]
fn strict_policy_fails_on_path_conflict() {
    let dir = work("strict");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/texts/conflict.txt";
    write_source(&low, 46, None, &[(path, b"low".as_slice())]);
    write_source(&high, 46, None, &[(path, b"high".as_slice())]);

    let mut request = request(vec![source("low", &low, 0), source("high", &high, 10)]);
    request.policy = Policy::Strict;
    let error = merge(&request, &dir, &dir.join("out.zip")).expect_err("strict 必须失败");
    assert!(
        error.to_string().contains("strict"),
        "错误应点名 strict 策略: {error}"
    );
}

#[test]
fn first_policy_keeps_lower_layer() {
    let dir = work("first");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/texts/conflict.txt";
    write_source(&low, 46, None, &[(path, b"low".as_slice())]);
    write_source(&high, 46, None, &[(path, b"high".as_slice())]);
    let out = dir.join("out.zip");

    let mut request = request(vec![source("low", &low, 0), source("high", &high, 10)]);
    request.policy = Policy::First;
    let (report, entries) = merge_ok(&request, &dir, &out);

    assert_eq!(
        entry(&entries, path),
        Some(b"low".as_slice()),
        "first 策略低层胜"
    );
    assert_eq!(report.conflicts.len(), 1);
    assert_eq!(report.conflicts[0].winner, "low");
    assert_eq!(report.conflicts[0].losers, vec!["high".to_string()]);
}

#[test]
fn pack_icon_comes_from_highest_layer() {
    let dir = work("pack-icon");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    write_source(&low, 46, None, &[("pack.png", b"low-logo".as_slice())]);
    write_source(&high, 46, None, &[("pack.png", b"high-logo".as_slice())]);
    let out = dir.join("out.zip");

    let mut request = request(vec![source("low", &low, 0), source("high", &high, 10)]);
    request.policy = Policy::First;
    let (report, entries) = merge_ok(&request, &dir, &out);

    assert_eq!(
        entry(&entries, "pack.png"),
        Some(b"high-logo".as_slice()),
        "pack.png 固定取最高层，不受 policy 影响"
    );
    assert!(report.conflicts.is_empty(), "图标不算冲突");
}

#[test]
fn requested_format_overrides_intersection_max() {
    let dir = work("format-override");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    write_source(&low, 46, Some(serde_json::json!([46, 60])), &[]);
    write_source(&high, 60, Some(serde_json::json!([50, 70])), &[]);
    let out = dir.join("out.zip");

    let mut request = request(vec![source("low", &low, 0), source("high", &high, 10)]);
    request.format = 99;
    let (report, entries) = merge_ok(&request, &dir, &out);

    assert_eq!(report.format, 99);
    assert_eq!((report.format_min, report.format_max), (50, 60));
    let mcmeta: serde_json::Value =
        serde_json::from_slice(entry(&entries, "pack.mcmeta").expect("输出含 pack.mcmeta"))
            .unwrap();
    assert_eq!(mcmeta["pack"]["pack_format"], 99);
    assert_eq!(mcmeta["pack"]["min_format"], 50);
    assert_eq!(mcmeta["pack"]["max_format"], 60);
    // 26.x 客户端按 min_format 校验：下界 50 落在 15..=64，必须给出 supported_formats
    assert_eq!(
        mcmeta["pack"]["supported_formats"],
        serde_json::json!([50, 60])
    );
}

#[test]
fn modern_only_range_omits_supported_formats() {
    let dir = work("format-modern-only");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    write_source(&low, 70, Some(serde_json::json!([70, 80])), &[]);
    write_source(&high, 80, Some(serde_json::json!([80, 97])), &[]);
    let out = dir.join("out.zip");

    let mut request = request(vec![source("low", &low, 0), source("high", &high, 10)]);
    request.format = 97;
    let (report, entries) = merge_ok(&request, &dir, &out);

    assert_eq!((report.format_min, report.format_max), (80, 80));
    let mcmeta: serde_json::Value =
        serde_json::from_slice(entry(&entries, "pack.mcmeta").expect("输出含 pack.mcmeta"))
            .unwrap();
    assert_eq!(mcmeta["pack"]["min_format"], 80);
    assert!(
        mcmeta["pack"]["supported_formats"].is_null(),
        "下界 >=65 的纯新格式包不需要 supported_formats"
    );
}

#[test]
fn missing_mcmeta_is_tolerated() {
    let dir = work("missing-mcmeta-tolerated");
    let bare = dir.join("bare.zip");
    write_zip(&bare, &[("assets/minecraft/texts/x.txt", b"x".as_slice())]);
    let out = dir.join("out.zip");

    let mut request = request(vec![source("bare", &bare, 0)]);
    request.format = 46;
    let (report, entries) = merge_ok(&request, &dir, &out);

    assert_eq!(report.format, 46);
    assert_eq!((report.format_min, report.format_max), (46, 46));
    assert_eq!(
        entry(&entries, "assets/minecraft/texts/x.txt"),
        Some(b"x".as_slice()),
        "没有 pack.mcmeta 的来源，条目也要照常进入结果"
    );
    let mcmeta: serde_json::Value =
        serde_json::from_slice(entry(&entries, "pack.mcmeta").expect("输出仍要有 pack.mcmeta"))
            .expect("pack.mcmeta 是 JSON");
    assert_eq!(mcmeta["pack"]["pack_format"], 46);
    assert_eq!(
        mcmeta["pack"]["supported_formats"],
        serde_json::json!([46, 46])
    );
}

#[test]
fn missing_mcmeta_without_format_fails() {
    let dir = work("missing-mcmeta-no-format");
    let bare = dir.join("bare.zip");
    write_zip(&bare, &[("assets/minecraft/texts/x.txt", b"x".as_slice())]);
    let out = dir.join("out.zip");

    let error = merge(&request(vec![source("bare", &bare, 0)]), &dir, &out)
        .expect_err("全部来源都没有 pack.mcmeta 且 format 为 0 时必须失败");
    let message = error.to_string();
    assert!(
        message.contains("pack.mcmeta") && message.contains("format"),
        "错误文案应同时点明 pack.mcmeta 与 format: {message}"
    );
    assert!(!out.exists(), "失败时不应留下输出文件");
}

#[test]
fn same_size_different_content_is_not_deduped() {
    let dir = work("same-size");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/texts/same-size.txt";
    write_source(&low, 46, None, &[(path, b"aaaa".as_slice())]);
    write_source(&high, 46, None, &[(path, b"bbbb".as_slice())]);
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.deduped, 0, "大小相同但内容不同不能去重");
    assert_eq!(report.conflicts.len(), 1);
    assert_eq!(entry(&entries, path), Some(b"bbbb".as_slice()));
}

#[test]
fn same_priority_conflict_is_flagged() {
    let dir = work("same-priority");
    let alpha = dir.join("alpha.zip");
    let beta = dir.join("beta.zip");
    let path = "assets/minecraft/textures/block/shared.png";
    write_source(&alpha, 46, None, &[(path, b"alpha".as_slice())]);
    write_source(&beta, 46, None, &[(path, b"beta".as_slice())]);
    let out = dir.join("out.zip");

    let (report, _) = merge_ok(
        &request(vec![source("alpha", &alpha, 5), source("beta", &beta, 5)]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1);
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.kind, AssetKind::Texture);
    assert_eq!(conflict.winner, "beta", "同优先级按请求顺序后写覆盖");
    assert_eq!(conflict.losers, vec!["alpha".to_string()]);
    assert_eq!(conflict.winner_priority, 5);
    assert_eq!(conflict.loser_priorities, vec![5]);
    assert!(conflict.same_priority, "同优先级应显式标记，便于区分误冲突");
}

#[test]
fn font_same_id_reports_json_conflict() {
    let dir = work("font-conflict");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/font/default.json";
    write_source(
        &low,
        46,
        None,
        &[(
            path,
            br#"{"providers":[{"id":"glyph","type":"bitmap","file":"low.png"}]}"#.as_slice(),
        )],
    );
    write_source(
        &high,
        46,
        None,
        &[(path, br#"{"providers":[{"id":"glyph","type":"bitmap","file":"high.png"},{"id":"extra","type":"space"}]}"#.as_slice())],
    );
    let out = dir.join("out.zip");

    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1);
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.kind, AssetKind::Font);
    assert_eq!(conflict.severity, Severity::Medium);
    assert_eq!(conflict.mode, ConflictMode::JsonConcat);
    assert!(
        conflict
            .fields
            .iter()
            .any(|field| field.contains("[id=glyph]")),
        "应指出同 id 定义差异: {:?}",
        conflict.fields
    );
    let merged: serde_json::Value =
        serde_json::from_slice(entry(&entries, path).expect("font 条目存在")).expect("合法 JSON");
    assert_eq!(
        merged["providers"].as_array().map(Vec::len),
        Some(3),
        "不同定义仍按高优先在前拼接"
    );
}
#[test]
fn three_layers_accumulate_losers() {
    let dir = work("three-layers");
    let low = dir.join("low.zip");
    let mid = dir.join("mid.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/textures/block/stone.png";
    write_source(&low, 64, None, &[(path, b"low".as_slice())]);
    write_source(&mid, 64, None, &[(path, b"mid".as_slice())]);
    write_source(&high, 64, None, &[(path, b"high".as_slice())]);
    let out = dir.join("out.zip");

    let (report, _) = merge_ok(
        &request(vec![
            source("low", &low, 0),
            source("mid", &mid, 5),
            source("high", &high, 10),
        ]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1);
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.winner, "high");
    assert_eq!(conflict.winner_priority, 10);
    assert_eq!(conflict.losers.len(), 2);
    assert!(conflict.losers.contains(&"low".to_string()));
    assert!(conflict.losers.contains(&"mid".to_string()));
    assert_eq!(conflict.loser_priorities.len(), 2);
    assert!(conflict.loser_priorities.contains(&5));
    assert!(conflict.loser_priorities.contains(&0));
    assert_eq!(report.conflict_summary.high, 1);
}

#[test]
fn json_conflict_fields_are_capped() {
    let dir = work("json-field-cap");
    let low = dir.join("low.zip");
    let high = dir.join("high.zip");
    let path = "assets/minecraft/lang/en_us.json";
    let build = |value: &str| {
        let mut map = serde_json::Map::new();
        for index in 0..60 {
            map.insert(
                format!("k{index}"),
                serde_json::Value::String(value.to_string()),
            );
        }
        serde_json::to_vec(&serde_json::Value::Object(map)).expect("序列化 lang fixture")
    };

    let low_json = build("low");
    let high_json = build("high");
    write_source(&low, 64, None, &[(path, low_json.as_slice())]);
    write_source(&high, 64, None, &[(path, high_json.as_slice())]);
    let out = dir.join("out.zip");

    let (report, _) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );

    assert_eq!(report.conflicts.len(), 1);
    let conflict = &report.conflicts[0];
    assert_eq!(conflict.kind, AssetKind::Lang);
    assert_eq!(conflict.severity, Severity::Medium);
    assert_eq!(conflict.mode, ConflictMode::JsonDeep);
    assert_eq!(conflict.fields.len(), 50);
    assert!(conflict.fields.iter().all(|field| field.starts_with('/')));
}

#[test]
fn stored_entries_are_passed_through() {
    let dir = work("stored-passthrough");
    let base = dir.join("base.zip");
    {
        let file = std::fs::File::create(&base).expect("创建 fixture zip");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        zip.start_file("pack.mcmeta", options)
            .expect("写 pack.mcmeta");
        zip.write_all(&mcmeta_bytes(46, None))
            .expect("写 pack.mcmeta 内容");
        zip.start_file("assets/minecraft/texts/raw.txt", options)
            .expect("写 store 条目");
        zip.write_all(b"stored-bytes").expect("写 store 内容");
        zip.finish().expect("关闭 fixture zip");
    }
    let out = dir.join("out.zip");
    let (report, entries) = merge_ok(&request(vec![source("base", &base, 0)]), &dir, &out);

    assert_eq!(report.entries, 2);
    assert_eq!(
        entry(&entries, "assets/minecraft/texts/raw.txt"),
        Some(b"stored-bytes".as_slice())
    );
    let file = std::fs::File::open(&out).expect("打开输出");
    let mut archive = zip::ZipArchive::new(file).expect("输出是合法 zip");
    let raw = archive
        .by_name("assets/minecraft/texts/raw.txt")
        .expect("读输出条目");
    assert_eq!(
        raw.compression(),
        zip::CompressionMethod::Stored,
        "store 条目应直拷，不被重压"
    );
}
/// 请求里的 priority 与数组顺序相反时，胜者内容必须来自优先级更高的来源。
#[test]
fn out_of_order_priorities_read_from_winning_source() {
    let dir = work("out-of-order-priority");
    let high = dir.join("high.zip");
    let low = dir.join("low.zip");
    write_source(
        &high,
        46,
        None,
        &[("assets/ns/value.txt", b"high-priority".as_slice())],
    );
    write_source(
        &low,
        46,
        None,
        &[("assets/ns/value.txt", b"low-priority".as_slice())],
    );
    let request = request(vec![source("high", &high, 10), source("low", &low, 0)]);
    let out = dir.join("out.zip");
    let (report, entries) = merge_ok(&request, &dir, &out);
    assert_eq!(
        entry(&entries, "assets/ns/value.txt"),
        Some(b"high-priority".as_slice())
    );

    let bytes = std::fs::read(&out).expect("读取输出");
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let actual = hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    assert_eq!(report.sha1, actual, "报告里的 sha1 必须与产物文件一致");
    assert_eq!(report.bytes, bytes.len() as u64);
}

/// 来源 zip 里的目录条目不能让后续条目的原始偏移错位。
#[test]
fn directory_entries_do_not_shift_raw_offsets() {
    let dir = work("directory-entries");
    let source_path = dir.join("with-dirs.zip");
    {
        let file = std::fs::File::create(&source_path).expect("创建 fixture zip");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("pack.mcmeta", options)
            .expect("写 pack.mcmeta");
        zip.write_all(&mcmeta_bytes(46, None))
            .expect("写 pack.mcmeta 内容");
        zip.add_directory("assets/", options).expect("写目录条目");
        zip.start_file("assets/ns/first.txt", options)
            .expect("写 first");
        zip.write_all(b"first-content").expect("写 first 内容");
        zip.start_file("assets/ns/second.txt", options)
            .expect("写 second");
        zip.write_all(b"second-content").expect("写 second 内容");
        zip.finish().expect("关闭 fixture zip");
    }
    let request = request(vec![source("base", &source_path, 0)]);
    let out = dir.join("out.zip");
    let (_, entries) = merge_ok(&request, &dir, &out);
    // read_zip 读条目会校验 CRC：偏移错位会直接在这里暴露。
    assert_eq!(
        entry(&entries, "assets/ns/first.txt"),
        Some(b"first-content".as_slice())
    );
    assert_eq!(
        entry(&entries, "assets/ns/second.txt"),
        Some(b"second-content".as_slice())
    );
}

/// 直拷路径默认不校验来源字节；显式开启时必须发现数据区损坏。
#[test]
fn corrupting_source_data_is_detected_when_verifying() {
    let dir = work("verify-sources");
    let source_path = dir.join("base.zip");
    write_source(
        &source_path,
        46,
        None,
        &[(
            "assets/ns/blob.bin",
            b"payload-payload-payload-payload".as_slice(),
        )],
    );

    let (start, length) = {
        let file = std::fs::File::open(&source_path).expect("打开 fixture");
        let mut archive = zip::ZipArchive::new(file).expect("解析 fixture");
        let entry = archive.by_index_raw(1).expect("读条目");
        (
            entry.data_start().expect("数据起点"),
            entry.compressed_size(),
        )
    };
    let mut bytes = std::fs::read(&source_path).expect("读取 fixture");
    let target = start as usize + (length as usize / 2);
    bytes[target] ^= 0xFF;
    std::fs::write(&source_path, bytes).expect("写回损坏数据");

    let mut request = request(vec![source("base", &source_path, 0)]);
    let out = dir.join("out.zip");
    merge(&request, &dir, &out).expect("默认直拷不校验，仍应成功");

    request.verify_sources = true;
    let error = merge(&request, &dir, &out).expect_err("开启校验后应当发现损坏");
    assert!(error.to_string().contains("校验失败"), "{error}");
}

#[test]
fn stored_low_priority_source_yields_to_high_priority() {
    let dir = work("stored-multi-source");
    let low = dir.join("low.zip");
    {
        let file = std::fs::File::create(&low).expect("创建 fixture zip");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        zip.start_file("pack.mcmeta", options)
            .expect("写 pack.mcmeta");
        zip.write_all(&mcmeta_bytes(46, None))
            .expect("写 pack.mcmeta 内容");
        zip.start_file("assets/demo/value.txt", options)
            .expect("写 store 条目");
        zip.write_all(b"from-low").expect("写 store 内容");
        zip.finish().expect("关闭 fixture zip");
    }
    let high = dir.join("high.zip");
    write_source(&high, 46, None, &[("assets/demo/value.txt", b"from-high")]);
    let out = dir.join("out.zip");
    let (report, entries) = merge_ok(
        &request(vec![source("low", &low, 0), source("high", &high, 10)]),
        &dir,
        &out,
    );
    assert_eq!(
        entry(&entries, "assets/demo/value.txt"),
        Some(b"from-high".as_slice())
    );
    assert_eq!(report.raw_copied, 1, "只有高层 deflate 条目走直拷");
}

#[test]
fn large_directory_reaches_zip64_and_keeps_every_entry() {
    const COUNT: usize = 70_000;
    let dir = work("zip64-directory");
    let src = dir.join("src.zip");
    {
        let file = std::fs::File::create(&src).expect("创建 fixture zip");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        zip.start_file("pack.mcmeta", options)
            .expect("写 pack.mcmeta");
        zip.write_all(&mcmeta_bytes(46, None))
            .expect("写 pack.mcmeta 内容");
        for index in 0..COUNT {
            zip.start_file(format!("assets/bulk/f{index:05}.bin"), options)
                .expect("写批量条目");
            zip.write_all(format!("v{index}").as_bytes())
                .expect("写批量内容");
        }
        zip.finish().expect("关闭 fixture zip");
    }
    let out = dir.join("out.zip");
    let (report, entries) = merge_ok(&request(vec![source("bulk", &src, 0)]), &dir, &out);
    assert_eq!(report.entries, COUNT + 1);
    assert_eq!(entries.len(), COUNT + 1);
    let produced = std::fs::read(&out).expect("读回产物");
    assert!(
        produced.windows(4).any(|window| window == b"PK\x06\x06"),
        "70,000 条应当写出 zip64 EOCD"
    );
    assert_eq!(report.raw_copied, COUNT, "全部批量条目都应走直拷");
    assert_eq!(
        entry(&entries, "assets/bulk/f00000.bin"),
        Some(b"v0".as_slice())
    );
    let last = format!("assets/bulk/f{:05}.bin", COUNT - 1);
    let expected = format!("v{}", COUNT - 1);
    assert_eq!(entry(&entries, &last), Some(expected.as_bytes()));
}
