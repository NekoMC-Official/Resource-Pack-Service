//! rps-pack：把多个资源包合成一个（CLI 入口）。
//!
//! 成功：exit 0，stdout 一行 JSON 报告；失败：exit 2，stdout 一行 ok:false，诊断写 stderr。

use std::io::{Read, Write};
use std::path::Path;
use std::path::PathBuf;
use std::sync::mpsc::{RecvTimeoutError, channel};
use std::time::Duration;

use rps_pack::model::Request;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--version") | Some("version") => {
            println!("rps-pack {}", env!("CARGO_PKG_VERSION"));
        }
        Some("merge") => run_merge(&args),
        Some("serve") => run_serve(&args),
        _ => {
            eprintln!("用法: rps-pack merge --request - --output <path>");
            std::process::exit(64);
        }
    }
}

fn run_merge(args: &[String]) {
    let request_arg = flag(args, "--request").unwrap_or_else(|| "-".to_string());
    let output = match flag(args, "--output") {
        Some(value) if !value.trim().is_empty() => PathBuf::from(value),
        _ => fail("缺少 --output"),
    };

    let input = if request_arg == "-" {
        let mut buffer = String::new();
        if std::io::stdin().read_to_string(&mut buffer).is_err() {
            fail("读取 stdin 失败");
        }
        buffer
    } else {
        match std::fs::read_to_string(&request_arg) {
            Ok(text) => text,
            Err(error) => fail(&format!("读取请求文件失败 {request_arg}: {error}")),
        }
    };

    let request: Request = match serde_json::from_str(&input) {
        Ok(request) => request,
        Err(error) => fail(&format!("请求 JSON 解析失败: {error}")),
    };

    let project_root = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    match rps_pack::merge(&request, &project_root, &output) {
        Ok(report) => {
            let line = match serde_json::to_string(&report) {
                Ok(line) => line,
                Err(error) => fail(&format!("报告序列化失败: {error}")),
            };
            println!("{line}");
            let _ = std::io::stdout().flush();
            eprintln!(
                "rps-pack: {} 个条目，{} 字节，sha1 {}，用时 {}ms",
                report.entries, report.bytes, report.sha1, report.millis
            );
        }
        Err(error) => {
            eprintln!("rps-pack: {error}");
            let payload = serde_json::json!({ "ok": false, "error": error.to_string() });
            println!("{payload}");
            let _ = std::io::stdout().flush();
            std::process::exit(2);
        }
    }
}

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|arg| arg == name)
        .and_then(|index| args.get(index + 1))
        .cloned()
}

fn fail(message: &str) -> ! {
    eprintln!("rps-pack: {message}");
    let payload = serde_json::json!({ "ok": false, "error": message });
    println!("{payload}");
    let _ = std::io::stdout().flush();
    std::process::exit(2);
}

/// 常驻模式：stdin/stdout 上以 u32 小端长度前缀收发 JSON 帧，空闲超时或 stdin 关闭即退出。
///
/// 请求帧：{"output": "<产物路径>", "request": <merge 的请求对象>}
/// 响应帧：合并报告（ok:true）或 {"ok":false,"error":"..."}；出错不会杀掉进程。
fn run_serve(args: &[String]) {
    let idle = flag(args, "--idle-seconds")
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(300)
        .max(1);
    let (sender, receiver) = channel::<Vec<u8>>();
    std::thread::Builder::new()
        .name("rps-pack-serve-stdin".to_string())
        .spawn(move || {
            let stdin = std::io::stdin();
            let mut input = stdin.lock();
            loop {
                let mut header = [0u8; 4];
                if input.read_exact(&mut header).is_err() {
                    break;
                }
                let length = u32::from_le_bytes(header) as usize;
                if length == 0 || length > MAX_FRAME_BYTES {
                    break;
                }
                let mut frame = vec![0u8; length];
                if input.read_exact(&mut frame).is_err() {
                    break;
                }
                if sender.send(frame).is_err() {
                    break;
                }
            }
        })
        .expect("创建 serve 读线程失败");
    let stdout = std::io::stdout();
    loop {
        match receiver.recv_timeout(Duration::from_secs(idle)) {
            Ok(frame) => {
                let payload = serve_frame(&frame);
                let mut output = stdout.lock();
                if write_frame(&mut output, &payload).is_err() {
                    break;
                }
            }
            Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => break,
        }
    }
    std::process::exit(0);
}

const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

fn serve_frame(frame: &[u8]) -> Vec<u8> {
    let value: serde_json::Value = match serde_json::from_slice(frame) {
        Ok(value) => value,
        Err(error) => return error_json(format!("请求 JSON 解析失败: {error}")),
    };
    let output = match value.get("output").and_then(serde_json::Value::as_str) {
        Some(text) if !text.trim().is_empty() => Path::new(text).to_path_buf(),
        _ => return error_json("缺少 output".to_string()),
    };
    let request_value = value
        .get("request")
        .cloned()
        .unwrap_or(serde_json::Value::Null);
    let request: Request = match serde_json::from_value(request_value) {
        Ok(request) => request,
        Err(error) => return error_json(format!("请求 JSON 解析失败: {error}")),
    };
    let project_root = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    match rps_pack::merge(&request, &project_root, &output) {
        Ok(report) => serde_json::to_vec(&report)
            .unwrap_or_else(|error| error_json(format!("报告序列化失败: {error}"))),
        Err(error) => error_json(error.to_string()),
    }
}

fn error_json(message: String) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({ "ok": false, "error": message }))
        .unwrap_or_else(|_| b"{\"ok\":false,\"error\":\"serialize failed\"}".to_vec())
}

fn write_frame(output: &mut impl Write, payload: &[u8]) -> std::io::Result<()> {
    output.write_all(&(payload.len() as u32).to_le_bytes())?;
    output.write_all(payload)?;
    output.flush()
}
