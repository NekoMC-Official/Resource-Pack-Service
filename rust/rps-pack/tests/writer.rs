//! 自写 zip writer 的契约：可读回、时间戳归一、字节确定、zip64 目录。

use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;

use rps_pack::writer::ZipArchiveWriter;
use sha1::{Digest, Sha1};
use zip::{CompressionMethod, ZipArchive, ZipWriter};

fn work(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("target")
        .join("test-work")
        .join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("创建测试目录");
    dir
}

#[test]
fn roundtrip_reads_back_contents_with_default_timestamp() {
    let dir = work("writer-roundtrip");
    let path = dir.join("out.zip");
    let text = b"hello writer hello writer hello writer".repeat(64);
    let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
    writer.add_deflated("assets/demo/hello.txt", &text).expect("写 deflate 条目");
    writer.add_stored("assets/demo/raw.bin", &[0, 1, 2, 3, 4, 5]).expect("写 store 条目");
    writer.finish().expect("收尾");

    let mut archive = ZipArchive::new(std::fs::File::open(&path).expect("打开产物")).expect("解析产物");
    assert_eq!(archive.len(), 2);

    {
        let mut entry = archive.by_index(0).expect("读第一条");
        assert_eq!(entry.name().expect("名称").as_ref(), "assets/demo/hello.txt");
        assert_eq!(entry.compression(), zip::CompressionMethod::Deflated);
        assert_eq!(entry.last_modified(), Some(zip::DateTime::default()));
        let mut read = Vec::new();
        entry.read_to_end(&mut read).expect("读内容");
        assert_eq!(read, text);
    }
    {
        let mut entry = archive.by_index(1).expect("读第二条");
        assert_eq!(entry.name().expect("名称").as_ref(), "assets/demo/raw.bin");
        assert_eq!(entry.compression(), zip::CompressionMethod::Stored);
        assert_eq!(entry.last_modified(), Some(zip::DateTime::default()));
        let mut read = Vec::new();
        entry.read_to_end(&mut read).expect("读内容");
        assert_eq!(read, vec![0, 1, 2, 3, 4, 5]);
    }
}

#[test]
fn non_ascii_names_roundtrip() {
    let dir = work("writer-unicode");
    let path = dir.join("out.zip");
    let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
    writer
        .add_deflated("assets/nekomc/说明.txt", "中文内容".as_bytes())
        .expect("写中文名条目");
    writer.finish().expect("收尾");

    let mut archive = ZipArchive::new(std::fs::File::open(&path).expect("打开产物")).expect("解析产物");
    let mut entry = archive.by_index(0).expect("读条目");
    assert_eq!(entry.name().expect("名称").as_ref(), "assets/nekomc/说明.txt");
    let mut text = String::new();
    entry.read_to_string(&mut text).expect("读内容");
    assert_eq!(text, "中文内容");
}

#[test]
fn same_input_produces_identical_bytes() {
    let dir = work("writer-deterministic");
    for name in ["a.zip", "b.zip"] {
        let mut writer = ZipArchiveWriter::create(&dir.join(name), 6).expect("创建写入器");
        writer.add_deflated("x.txt", &[7u8; 1024]).expect("写 deflate 条目");
        writer.add_stored("y.bin", &[9u8; 32]).expect("写 store 条目");
        writer.finish().expect("收尾");
    }
    let a = std::fs::read(dir.join("a.zip")).expect("读 a");
    let b = std::fs::read(dir.join("b.zip")).expect("读 b");
    assert_eq!(a, b);
}

#[test]
fn writes_zip64_directory_for_many_entries() {
    let dir = work("writer-zip64");
    let path = dir.join("many.zip");
    let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
    const COUNT: usize = 70_000;
    for index in 0..COUNT {
        writer.add_stored(&format!("f/{index:06}"), &[]).expect("写空条目");
    }
    writer.finish().expect("收尾");

    let mut archive = ZipArchive::new(std::fs::File::open(&path).expect("打开产物")).expect("解析产物");
    assert_eq!(archive.len(), COUNT);
    assert_eq!(archive.by_index(0).expect("首条").name().expect("名称").as_ref(), "f/000000");
    assert_eq!(
        archive.by_index(COUNT - 1).expect("末条").name().expect("名称").as_ref(),
        "f/069999"
    );
}

/// finish() 报告的 sha1/bytes 必须与磁盘上的产物完全一致。
#[test]
fn finish_reports_real_sha1_and_size() {
    let dir = work("writer-sha1");
    let path = dir.join("out.zip");
    let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
    writer.add_deflated("a.txt", b"content-a content-a").expect("写 deflate");
    writer.add_stored("b.bin", b"content-b").expect("写 store");
    let stats = writer.finish().expect("收尾");

    let bytes = std::fs::read(&path).expect("读取产物");
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let actual = hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect::<String>();
    assert_eq!(stats.sha1, actual);
    assert_eq!(stats.bytes, bytes.len() as u64);
}

/// 直拷路径（add_raw）同样参与整包 SHA-1，且内容可原样读回。
#[test]
fn add_raw_participates_in_sha1_and_round_trips() {
    let dir = work("writer-raw");
    let source = dir.join("source.zip");
    {
        let mut zip = ZipWriter::new(File::create(&source).expect("建来源"));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(CompressionMethod::Stored);
        zip.start_file("assets/raw.txt", options).expect("写来源条目");
        zip.write_all(b"raw content from source").expect("写内容");
        zip.finish().expect("收尾来源");
    }

    let (name, crc, compressed, size, offset) = {
        let mut archive =
            ZipArchive::new(File::open(&source).expect("打开来源")).expect("解析来源");
        let entry = archive.by_index(0).expect("首条");
        (
            entry.name().expect("名称").to_string(),
            entry.crc32(),
            entry.compressed_size(),
            entry.size(),
            entry.data_start().expect("数据偏移"),
        )
    };

    let path = dir.join("out.zip");
    let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
    let mut data = File::open(&source).expect("打开来源");
    data.seek(SeekFrom::Start(offset)).expect("定位数据");
    writer
        .add_raw(&name, crc, compressed, size, 0, &mut data)
        .expect("直拷条目");
    let stats = writer.finish().expect("收尾");

    let bytes = std::fs::read(&path).expect("读取产物");
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let actual = hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect::<String>();
    assert_eq!(stats.sha1, actual);
    assert_eq!(stats.bytes, bytes.len() as u64);
    assert_eq!(stats.raw_copied, 1);

    let mut archive = ZipArchive::new(File::open(&path).expect("打开产物")).expect("解析产物");
    let mut entry = archive.by_index(0).expect("首条");
    assert_eq!(entry.compression(), CompressionMethod::Stored);
    let mut read = Vec::new();
    entry.read_to_end(&mut read).expect("读内容");
    assert_eq!(read, b"raw content from source");
}

#[test]
fn zip64_directory_starts_at_65535_entries() {
    for (count, expect_zip64) in [(65_534usize, false), (65_535, true)] {
        let dir = work(&format!("writer-zip64-{count}"));
        let path = dir.join("out.zip");
        let mut writer = ZipArchiveWriter::create(&path, 6).expect("创建写入器");
        for index in 0..count {
            writer
                .add_stored(&format!("assets/demo/{index}.bin"), b"x")
                .expect("写条目");
        }
        writer.finish().expect("收尾");
        let bytes = std::fs::read(&path).expect("读产物");
        let has_eocd64 = bytes.windows(4).any(|window| window == [0x50, 0x4b, 0x06, 0x06]);
        assert_eq!(has_eocd64, expect_zip64, "{count} 条目时的 zip64 判定");
    }
}
