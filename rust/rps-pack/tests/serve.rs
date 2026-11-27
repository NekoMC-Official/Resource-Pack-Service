//! rps-pack serve 常驻模式：长度前缀帧协议、错误帧不退出、stdin 关闭后正常退出。

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

fn work(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("target")
        .join("test-work")
        .join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("创建测试目录");
    dir
}

fn write_source(path: &Path, entries: &[(&str, &[u8])]) {
    let file = std::fs::File::create(path).expect("创建 fixture zip");
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    zip.start_file("pack.mcmeta", options)
        .expect("写 pack.mcmeta");
    let mcmeta = serde_json::to_vec(&serde_json::json!({
        "pack": { "pack_format": 46, "description": "serve fixture" }
    }))
    .expect("序列化 pack.mcmeta");
    zip.write_all(&mcmeta).expect("写 pack.mcmeta 内容");
    for (name, bytes) in entries {
        zip.start_file(*name, options).expect("写条目");
        zip.write_all(bytes).expect("写条目内容");
    }
    zip.finish().expect("关闭 fixture zip");
}

fn spawn_serve() -> (Child, ChildStdin, ChildStdout) {
    let mut child = Command::new(env!("CARGO_BIN_EXE_rps-pack"))
        .arg("serve")
        .arg("--idle-seconds")
        .arg("30")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .expect("启动 serve 进程");
    let stdin = child.stdin.take().expect("拿到 stdin");
    let stdout = child.stdout.take().expect("拿到 stdout");
    (child, stdin, stdout)
}

fn send_frame(stdin: &mut ChildStdin, payload: &[u8]) {
    stdin
        .write_all(&(payload.len() as u32).to_le_bytes())
        .expect("写帧长度");
    stdin.write_all(payload).expect("写帧内容");
    stdin.flush().expect("刷新帧");
}

fn read_frame(stdout: &mut ChildStdout) -> serde_json::Value {
    let mut header = [0u8; 4];
    stdout.read_exact(&mut header).expect("读响应长度");
    let length = u32::from_le_bytes(header) as usize;
    assert!(
        length > 0 && length < 16 * 1024 * 1024,
        "响应长度异常: {length}"
    );
    let mut payload = vec![0u8; length];
    stdout.read_exact(&mut payload).expect("读响应内容");
    serde_json::from_slice(&payload).expect("响应是 JSON")
}

fn frame_for(output: &Path, source: &Path) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "output": output.to_string_lossy(),
        "request": {
            "format": 0,
            "description": "serve 测试",
            "policy": "override",
            "json_merge": true,
            "sources": [{ "id": "base", "path": source.to_string_lossy(), "priority": 0 }]
        }
    }))
    .expect("序列化请求帧")
}

#[test]
fn serve_handles_multiple_frames_and_errors_without_exiting() {
    let dir = work("serve-frames");
    let source = dir.join("source.zip");
    write_source(&source, &[("assets/minecraft/texts/a.txt", b"hello serve")]);

    let (mut child, mut stdin, mut stdout) = spawn_serve();

    // 坏帧：返回 ok:false，但进程继续服务。
    send_frame(&mut stdin, b"{not json");
    let bad = read_frame(&mut stdout);
    assert_eq!(bad.get("ok").and_then(|value| value.as_bool()), Some(false));
    assert!(bad.get("error").and_then(|value| value.as_str()).is_some());

    for index in 0..2 {
        let output = dir.join(format!("merged-{index}.zip"));
        send_frame(&mut stdin, &frame_for(&output, &source));
        let report = read_frame(&mut stdout);
        assert_eq!(
            report.get("ok").and_then(|value| value.as_bool()),
            Some(true),
            "第 {index} 帧失败: {report}"
        );
        assert_eq!(
            report.get("entries").and_then(|value| value.as_u64()),
            Some(2)
        );
        assert!(output.is_file(), "第 {index} 帧没有产出文件");
    }

    drop(stdin);
    let status = child.wait().expect("等待 serve 退出");
    assert!(status.success(), "serve 退出码异常: {status}");
}
