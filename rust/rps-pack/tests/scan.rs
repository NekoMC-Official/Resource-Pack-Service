//! 并行中央目录扫描的确定性测试：多线程结果必须与单线程逐条一致，错误按 spec 顺序报告。

use std::io::Write;
use std::path::{Path, PathBuf};

use rps_pack::scan::{open_sources, scan_threads, SourceSpec};

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
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mcmeta = serde_json::json!({"pack": {"pack_format": 97, "description": marker}});
    zip.start_file("pack.mcmeta", options).expect("写 pack.mcmeta");
    zip.write_all(&serde_json::to_vec(&mcmeta).expect("序列化")).expect("写内容");
    for index in 0..entries {
        zip.start_file(format!("assets/{marker}/i{index}.txt"), options)
            .expect("写条目");
        zip.write_all(format!("{marker}-{index}").as_bytes()).expect("写内容");
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
        write_source(&dir.join(format!("src-{index}.zip")), &format!("m{index}"), 20 + index);
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
                (a.index, &a.name, a.crc, a.size, a.compressed, a.offset, a.raw_method),
                (b.index, &b.name, b.crc, b.size, b.compressed, b.offset, b.raw_method)
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
    assert!(text.contains("missing"), "错误应按顺序指向 missing，实际：{text}");
}

#[test]
fn scan_threads_keeps_small_source_sets_single_threaded() {
    assert_eq!(scan_threads(1), 1);
    assert_eq!(scan_threads(3), 1);
    assert!(scan_threads(4) >= 1);
}
