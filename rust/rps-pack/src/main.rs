//! rps-pack：把多个资源包合成一个（CLI 入口）。
//!
//! 成功：exit 0，stdout 一行 JSON 报告；失败：exit 2，stdout 一行 ok:false，诊断写 stderr。

use std::io::{Read, Write};
use std::path::PathBuf;

use rps_pack::model::Request;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--version") | Some("version") => {
            println!("rps-pack {}", env!("CARGO_PKG_VERSION"));
        }
        Some("merge") => run_merge(&args),
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
