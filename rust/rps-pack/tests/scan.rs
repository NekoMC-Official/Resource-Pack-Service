//! 并行中央目录扫描的确定性测试：多线程结果必须与单线程逐条一致，错误按 spec 顺序报告。

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use flate2::read::DeflateDecoder;
use rps_pack::scan::{SourceSpec, open_sources, scan_threads};

fn work(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("target")
        .join("test-work")
        .join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("创建测试目录");
    dir
}

fn write_source(path: &Path, marker: &str, entries: usize) {
    let file = std::fs::File::create(path).expect("创建 fixture zip");
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let mcmeta = serde_json::json!({"pack": {"pack_format": 97, "description": marker}});
    zip.start_file("pack.mcmeta", options)
        .expect("写 pack.mcmeta");
    zip.write_all(&serde_json::to_vec(&mcmeta).expect("序列化"))
        .expect("写内容");
    for index in 0..entries {
        zip.start_file(format!("assets/{marker}/i{index}.txt"), options)
            .expect("写条目");
        zip.write_all(format!("{marker}-{index}").as_bytes())
            .expect("写内容");
    }
    zip.finish().expect("关闭 fixture zip");
}

fn specs(dir: &Path, count: usize) -> Vec<SourceSpec> {
    (0..count)
        .map(|index| SourceSpec {
            id: format!("src-{index}"),
            priority: index as i64,
            path: dir.join(format!("src-{index}.zip")),
        })
        .collect()
}

#[test]
fn parallel_scan_matches_sequential_scan() {
    let dir = work("scan-parallel");
    for index in 0..6 {
        write_source(
            &dir.join(format!("src-{index}.zip")),
            &format!("m{index}"),
            20 + index,
        );
    }
    let specs = specs(&dir, 6);
    let sequential = open_sources(&specs, 1).expect("单线程扫描");
    let parallel = open_sources(&specs, 4).expect("并行扫描");

    assert_eq!(sequential.len(), parallel.len());
    for (left, right) in sequential.iter().zip(parallel.iter()) {
        assert_eq!(left.id, right.id);
        assert_eq!(left.priority, right.priority);
        assert_eq!(left.entries.len(), right.entries.len());
        for (a, b) in left.entries.iter().zip(right.entries.iter()) {
            assert_eq!(
                (
                    a.index,
                    &a.name,
                    a.crc,
                    a.size,
                    a.compressed,
                    a.offset,
                    a.raw_method
                ),
                (
                    b.index,
                    &b.name,
                    b.crc,
                    b.size,
                    b.compressed,
                    b.offset,
                    b.raw_method
                )
            );
        }
        assert_eq!(
            left.mcmeta.as_ref().map(|value| value.to_string()),
            right.mcmeta.as_ref().map(|value| value.to_string())
        );
    }
}

#[test]
fn parallel_scan_reports_first_failing_source_in_order() {
    let dir = work("scan-error-order");
    write_source(&dir.join("src-0.zip"), "m0", 2);
    write_source(&dir.join("src-2.zip"), "m2", 2);
    let specs = vec![
        SourceSpec {
            id: "first".to_string(),
            priority: 0,
            path: dir.join("src-0.zip"),
        },
        SourceSpec {
            id: "missing".to_string(),
            priority: 1,
            path: dir.join("does-not-exist.zip"),
        },
        SourceSpec {
            id: "second".to_string(),
            priority: 2,
            path: dir.join("src-2.zip"),
        },
    ];
    let text = match open_sources(&specs, 4) {
        Ok(_) => panic!("缺失来源应报错"),
        Err(error) => error.to_string(),
    };
    assert!(
        text.contains("missing"),
        "错误应按顺序指向 missing，实际：{text}"
    );
}

#[test]
fn scan_threads_keeps_small_source_sets_single_threaded() {
    assert_eq!(scan_threads(1), 1);
    assert_eq!(scan_threads(3), 1);
    assert!(scan_threads(4) >= 1);
}

#[test]
fn reader_reopens_after_release_and_reads_same_bytes() {
    let dir = work("scan-reopen");
    write_source(&dir.join("src-0.zip"), "m0", 8);
    let specs = specs(&dir, 1);
    let mut readers = open_sources(&specs, 1).expect("扫描来源");
    let meta = readers[0]
        .entries
        .iter()
        .find(|meta| meta.name == "assets/m0/i3.txt")
        .expect("找到条目")
        .clone();
    let original = readers[0].read(&meta).expect("首次读取");
    readers[0].release_archive();
    let reopened = readers[0].read(&meta).expect("释放中央目录后仍按偏移读取");
    assert_eq!(original, reopened);
    readers[0].release_scan_metadata();
    let after_metadata = readers[0].read(&meta).expect("元数据清空后仍按偏移读取");
    assert_eq!(original, after_metadata);
}

#[test]
fn raw_slice_handles_forward_backward_and_repeated_ranges() {
    let dir = work("scan-raw-slice");
    write_source(&dir.join("src-0.zip"), "m0", 8);
    let specs = specs(&dir, 1);
    let mut readers = open_sources(&specs, 1).expect("扫描来源");
    let metas: Vec<_> = readers[0].entries.clone();
    // 前跳、回退、同区间重复读、再前跳：覆盖 seek_relative 与绝对 seek 两条路径。
    for position in [4usize, 2, 7, 7, 1, 8] {
        let meta = metas[position].clone();
        let expected = readers[0].read(&meta).expect("解压条目");
        assert_eq!(expected, format!("m0-{}", position - 1).into_bytes());
        let slice = readers[0]
            .raw_slice(meta.offset, meta.compressed)
            .expect("取原始字节");
        let mut actual = Vec::new();
        DeflateDecoder::new(slice)
            .read_to_end(&mut actual)
            .expect("解压原始字节");
        assert_eq!(expected, actual, "第 {position} 个条目的原始区间不一致");
    }
}

#[test]
fn partial_raw_slice_keeps_position_in_sync() {
    let dir = work("scan-raw-partial");
    write_source(&dir.join("src-0.zip"), "m0", 8);
    let specs = specs(&dir, 1);
    let mut readers = open_sources(&specs, 1).expect("扫描来源");
    let metas: Vec<_> = readers[0].entries.clone();
    let first = metas[4].clone();
    {
        let mut slice = readers[0]
            .raw_slice(first.offset, first.compressed)
            .expect("取原始字节");
        let mut one = [0u8; 1];
        slice.read_exact(&mut one).expect("只读一个字节");
    }
    let later = metas[7].clone();
    let expected = readers[0].read(&later).expect("解压后续条目");
    let slice = readers[0]
        .raw_slice(later.offset, later.compressed)
        .expect("取原始字节");
    let mut actual = Vec::new();
    DeflateDecoder::new(slice)
        .read_to_end(&mut actual)
        .expect("解压原始字节");
    assert_eq!(expected, actual, "部分消费后位置记账应仍准确");
}

#[test]
fn archive_fallback_validates_name_after_scan() {
    let dir = work("scan-fallback");
    write_source(&dir.join("src-0.zip"), "m0", 8);
    let specs = specs(&dir, 1);
    let mut readers = open_sources(&specs, 1).expect("扫描来源");
    let meta = readers[0]
        .entries
        .iter()
        .find(|meta| meta.name == "assets/m0/i2.txt")
        .expect("找到条目")
        .clone();
    readers[0].release_archive();
    let mut fallback = meta.clone();
    fallback.raw_method = None;
    let bytes = readers[0].read(&fallback).expect("回退重开中央目录后读取");
    assert_eq!(bytes, b"m0-2");
    let mut renamed = meta;
    renamed.raw_method = None;
    renamed.name = "assets/m0/wrong.txt".to_string();
    let error = readers[0].read(&renamed).expect_err("名字不一致应报错");
    let message = error.to_string();
    assert!(message.contains("扫描后被改动"), "{message}");
    assert!(message.contains("src-0"), "{message}");
}
