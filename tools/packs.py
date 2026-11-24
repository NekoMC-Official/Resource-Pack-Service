#!/usr/bin/env python3
"""校验、打包并合并仓库内的社区资源包。

只使用 Python 标准库，不执行资源包里的任何内容。
  build  校验 packs/ 并生成确定性 zip 与 manifest.json
  merge  调用 rps-pack 合并，输出报告与 PR 摘要
"""

import argparse
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

FIXED = (1980, 1, 1, 0, 0, 0)
MAX_ENTRIES = 20000
MAX_BYTES = 64 * 1024 * 1024
MAX_PACKS = 50
MAX_TOTAL_BYTES = 128 * 1024 * 1024
ALLOWED = set("abcdefghijklmnopqrstuvwxyz0123456789_-")


def die(message):
    print("错误：" + message, file=sys.stderr)
    raise SystemExit(1)


def read_meta(path):
    data = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            die(str(path) + " 不是合法的 key: value 行：" + line)
        key, value = line.split(":", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        data[key.strip()] = value
    return data


def check_pack(root):
    meta_path = root / "pack.yml"
    mcmeta_path = root / "pack.mcmeta"
    assets = root / "assets"
    if not meta_path.is_file():
        die(str(root) + " 缺少 pack.yml")
    if not mcmeta_path.is_file():
        die(str(root) + " 缺少 pack.mcmeta")
    if not assets.is_dir():
        die(str(root) + " 缺少 assets/ 目录")

    meta = read_meta(meta_path)
    for key in ("id", "name", "priority", "license"):
        if not meta.get(key):
            die(str(meta_path) + " 缺少 " + key)
    if meta["id"] != root.name:
        die(str(meta_path) + " 的 id 与目录名 " + root.name + " 不一致")
    if any(ch not in ALLOWED for ch in meta["id"]):
        die(str(meta_path) + " 的 id 只允许小写字母、数字、下划线和短横线")
    if not meta["priority"].lstrip("-").isdigit():
        die(str(meta_path) + " 的 priority 必须是整数")

    try:
        mcmeta = json.loads(mcmeta_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        die(str(mcmeta_path) + " 不是合法 JSON：" + str(exc))
    pack = mcmeta.get("pack") if isinstance(mcmeta, dict) else None
    if not isinstance(pack, dict):
        die(str(mcmeta_path) + " 缺少 pack 对象")
    if not isinstance(pack.get("pack_format"), int) and not pack.get("supported_formats"):
        die(str(mcmeta_path) + " 需要 pack.pack_format 或 pack.supported_formats")
    if "description" not in pack:
        die(str(mcmeta_path) + " 需要 pack.description")

    entries = []
    total = 0
    namespaces = set()
    for path in sorted(root.rglob("*")):
        if path.is_dir():
            continue
        rel = path.relative_to(root).as_posix()
        if ".." in Path(rel).parts or rel.startswith("/") or "\\" in rel:
            die(str(root) + " 含非法路径：" + rel)
        if path.is_symlink():
            die(str(root) + " 不允许符号链接：" + rel)
        data = path.read_bytes()
        total += len(data)
        entries.append((rel, data))
        parts = Path(rel).parts
        if len(parts) >= 3 and parts[0] == "assets":
            namespaces.add(parts[1])
    if not entries:
        die(str(root) + " 没有任何文件")
    if len(entries) > MAX_ENTRIES:
        die(str(root) + " 条目数超过上限 " + str(MAX_ENTRIES))
    if total > MAX_BYTES:
        die(str(root) + " 解压后大小超过上限 " + str(MAX_BYTES))
    if not namespaces:
        die(str(root) + "/assets 下没有命名空间目录")

    return {
        "id": meta["id"],
        "name": meta.get("name", meta["id"]),
        "priority": int(meta["priority"]),
        "description": meta.get("description", ""),
        "license": meta.get("license", ""),
        "format": pack.get("pack_format"),
        "namespaces": sorted(namespaces),
        "files": len(entries),
        "bytes": total,
        "entries": entries,
    }


def write_zip(target, entries):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel, data in sorted(entries, key=lambda item: item[0]):
            info = zipfile.ZipInfo(rel, date_time=FIXED)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)


def sha1_file(path):
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(262144)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def pack_dirs(root):
    if not root.is_dir():
        return []
    return sorted(
        item for item in root.iterdir()
        if item.is_dir() and not item.name.startswith("_") and (item / "pack.yml").is_file()
    )


def cmd_build(args):
    root = Path(args.root)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    packs = []
    seen = set()
    total_bytes = 0
    for folder in pack_dirs(root):
        if len(packs) >= MAX_PACKS:
            die("资源包数量超过上限 " + str(MAX_PACKS))
        info = check_pack(folder)
        if info["id"] in seen:
            die("资源包 id 重复：" + info["id"])
        seen.add(info["id"])
        total_bytes += info["bytes"]
        if total_bytes > MAX_TOTAL_BYTES:
            die("所有资源包解压后总大小超过上限 " + str(MAX_TOTAL_BYTES))
        target = out / (info["id"] + ".zip")
        write_zip(target, info["entries"])
        packs.append({
            "id": info["id"],
            "name": info["name"],
            "priority": info["priority"],
            "description": info["description"],
            "license": info["license"],
            "format": info["format"],
            "namespaces": info["namespaces"],
            "files": info["files"],
            "bytes": info["bytes"],
            "zip": target.name,
            "sha1": sha1_file(target),
            "zip_bytes": target.stat().st_size,
        })
        print("打包 " + info["id"] + "：" + str(info["files"]) + " 文件，" + str(info["bytes"]) + " 字节")
    if not packs:
        print("没有发现资源包（packs/*/pack.yml）")
    (out / "manifest.json").write_text(
        json.dumps({"packs": packs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return 0


def find_binary(value):
    candidate = Path(value)
    if candidate.is_file():
        return candidate
    exe = Path(value + ".exe")
    if exe.is_file():
        return exe
    die("找不到 rps-pack 二进制：" + value)


def human(size):
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return str(int(value)) + " B" if unit == "B" else str(round(value, 1)) + " " + unit
        value /= 1024
    return str(size) + " B"


def read_manifest(path):
    if not path.is_file():
        die("缺少 " + str(path) + "，先运行 build")
    return json.loads(path.read_text(encoding="utf-8")).get("packs", [])


def md_cell(value):
    text = str(value).replace("`", "'").replace("|", "\\|").replace("\n", " ").strip()
    return text


def cmd_merge(args):
    packs_dir = Path(args.packs)
    packs = read_manifest(packs_dir / "manifest.json")
    summary_path = Path(args.summary)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["<!-- rps-pack-report -->", "## 社区资源包构建报告", ""]
    if not packs:
        lines.append("没有发现任何资源包，本次跳过合并。")
        summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("没有资源包，跳过合并")
        return 0

    sources = []
    for pack in sorted(packs, key=lambda item: (-int(item["priority"]), item["id"])):
        sources.append({
            "id": pack["id"],
            "path": str((packs_dir / pack["zip"]).resolve()),
            "priority": int(pack["priority"]),
        })
    request = {
        "format": 0,
        "description": args.description,
        "policy": args.policy,
        "json_merge": True,
        "sources": sources,
    }
    binary = find_binary(args.binary)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        [str(binary), "merge", "--request", "-", "--output", str(out)],
        input=json.dumps(request, ensure_ascii=False).encode("utf-8"),
        capture_output=True,
        timeout=args.timeout,
    )
    out_text = proc.stdout.decode("utf-8", "replace")
    err_text = proc.stderr.decode("utf-8", "replace")
    report = None
    if out_text.strip():
        try:
            report = json.loads(out_text)
        except json.JSONDecodeError:
            report = None
    if proc.returncode != 0 or not report or not report.get("ok"):
        detail = (report or {}).get("error") or err_text.strip() or out_text.strip() or "未知错误"
        lines += ["合并失败：" + detail, "", "详情：", "", err_text.strip()[:4000], ""]
        summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        Path(args.report).write_text(
            json.dumps({"ok": False, "error": detail}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print("合并失败：" + detail, file=sys.stderr)
        return 1

    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines += ["| 资源包 | 优先级 | 文件 | 解压大小 | 命名空间 |", "|---|---:|---:|---:|---|"]
    for pack in packs:
        lines.append("| " + md_cell(pack["name"]) + " (" + md_cell(pack["id"]) + ") | " + str(pack["priority"]) + " | "
                     + str(pack["files"]) + " | " + human(pack["bytes"]) + " | "
                     + ", ".join(pack["namespaces"]) + " |")
    conflicts = report.get("conflicts") or []
    lines += [
        "",
        "合并结果：**" + str(report.get("entries", 0)) + "** 条目，去重 **" + str(report.get("deduped", 0))
        + "**，冲突 **" + str(len(conflicts)) + "**，输出 **" + human(report.get("bytes", 0))
        + "**，sha1 " + str(report.get("sha1", "")) + "。",
        "",
        "格式：" + str(report.get("format", "?")) + "（" + str(report.get("format_min", "?"))
        + "–" + str(report.get("format_max", "?")) + "）",
    ]
    if conflicts:
        lines += ["", "冲突明细（前 20 条）：", ""]
        for item in conflicts[:20]:
            lines.append("- " + str(item.get("path")) + "：" + str(item.get("winner"))
                         + " 覆盖 " + ", ".join(item.get("losers") or []))
    warnings = report.get("warnings") or []
    if warnings:
        lines += ["", "警告：", ""] + ["- " + str(item) for item in warnings[:20]]
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("合并完成：" + str(summary_path))
    return 0


def main():
    parser = argparse.ArgumentParser(description="社区资源包校验、打包与合并")
    sub = parser.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build", help="校验 packs/ 并生成 zip")
    build.add_argument("--root", default="packs")
    build.add_argument("--out", default="build/packs")
    build.set_defaults(func=cmd_build)

    merge = sub.add_parser("merge", help="调用 rps-pack 合并")
    merge.add_argument("--packs", default="build/packs")
    merge.add_argument("--binary", required=True)
    merge.add_argument("--out", default="build/community-pack.zip")
    merge.add_argument("--report", default="build/community-pack.report.json")
    merge.add_argument("--summary", default="build/pack-summary.md")
    merge.add_argument("--description", default="NekoMC 社区资源包")
    merge.add_argument("--policy", default="override", choices=["override", "strict", "first"])
    merge.add_argument("--timeout", type=int, default=120)
    merge.set_defaults(func=cmd_merge)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
