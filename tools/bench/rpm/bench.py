#!/usr/bin/env python3
"""ResourcePackManager vs rps-pack 对照基准。

两臂分段跑同一批夹具（先 rps-pack 3 轮，再 RPM 3 轮）：RPM 走 MixEngine 完整管线（解压暂存 + JSON 合并 + 4 线程重压），
rps-pack 走中央目录扫描与原始字节直拷。每轮采样峰值工作集，输出墙钟 / 内部耗时 / 内存 / 产物字节。

用法：
    python tools/bench/rpm/bench.py --rounds 3 --json .tmp/rpm-bench/result.json

前置：
    tools/bench/rpm/build.ps1            # 编译 RPM mixer 与 RpmHarness
    tools/bench/gen_tiers.py .tmp/bench-s13   # 生成 w1/w2/many20k 夹具
"""
import argparse
import ctypes
import json
import shutil
import statistics
import subprocess
import tempfile
import time
from ctypes import wintypes
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RPM_BENCH = ROOT / ".tmp/rpm-bench"
PACKS = ROOT / ".tmp/bench-s13/packs"
S7 = ROOT / ".tmp/bench-s7"


class ProcessMemoryCounters(ctypes.Structure):
    _fields_ = [
        ("cb", wintypes.DWORD),
        ("PageFaultCount", wintypes.DWORD),
        ("PeakWorkingSetSize", ctypes.c_size_t),
        ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t),
        ("PeakPagefileUsage", ctypes.c_size_t),
    ]


def peak_working_set(pid: int) -> int:
    handle = ctypes.windll.kernel32.OpenProcess(0x0410, False, pid)
    if not handle:
        return 0
    counters = ProcessMemoryCounters()
    counters.cb = ctypes.sizeof(ProcessMemoryCounters)
    ok = ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
    ctypes.windll.kernel32.CloseHandle(handle)
    return counters.PeakWorkingSetSize if ok else 0


def run(cmd, cwd, outdir=None):
    if outdir is not None and outdir.exists():
        shutil.rmtree(outdir)
    started = time.perf_counter()
    # 报告可能几十 MB（冲突明细），必须落到文件而不是管道：
    # Popen + 不读 stdout 的采样循环会在管道缓冲（64 KiB）填满时把子进程卡死。
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as sink:
        proc = subprocess.Popen(
            [str(part) for part in cmd],
            cwd=str(cwd),
            stdout=sink,
            stderr=subprocess.STDOUT,
            text=True,
        )
        peak = 0
        while proc.poll() is None:
            peak = max(peak, peak_working_set(proc.pid))
            time.sleep(0.003)
        sink.seek(0)
        out = sink.read()
    return (time.perf_counter() - started) * 1000, peak, out, "", proc.returncode


def parse_fields(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def tier_specs():
    tiers = {}
    if (PACKS / "large-a.zip").exists():
        tiers["w1"] = {
            "packs": [PACKS / f"large-{suffix}.zip" for suffix in "abc"],
            "request": ROOT / ".tmp/bench-s13/request-w1.json",
        }
    if (PACKS / "w2-39.zip").exists():
        tiers["w2"] = {
            "packs": [PACKS / f"w2-{index:02d}.zip" for index in range(40)],
            "request": ROOT / ".tmp/bench-s13/request-w2.json",
        }
    if (PACKS / "many20k-03.zip").exists():
        tiers["many20k"] = {
            "packs": [PACKS / f"many20k-{index:02d}.zip" for index in range(4)],
            "request": ROOT / ".tmp/bench-s13/request-many20k.json",
        }
    if (S7 / "request.json").exists():
        tiers["s7"] = {
            "packs": sorted((S7 / "packs").glob("*.zip")),
            "request": S7 / "request.json",
        }
    return tiers


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--tiers", default=",".join(tier_specs()))
    parser.add_argument("--java", default=r"C:/Program Files/Zulu/zulu-25/bin/java.exe")
    parser.add_argument("--heap", default="-Xmx4G")
    parser.add_argument("--classes", default=str(RPM_BENCH / "classes"))
    parser.add_argument("--gson", default=str(RPM_BENCH / "lib/gson-2.11.0.jar"))
    parser.add_argument("--exe", default=str(ROOT / "rust/rps-pack/target/release/rps-pack.exe"))
    parser.add_argument("--json", default=str(RPM_BENCH / "result.json"))
    args = parser.parse_args()

    specs = tier_specs()
    results = {}
    for name in [item.strip() for item in args.tiers.split(",") if item.strip()]:
        if name not in specs:
            raise SystemExit(f"未知档位：{name}（可用：{', '.join(specs)}）")
        spec = specs[name]
        rows = []
        # 两臂分段跑，不交替：RPM 每档会把整包解压进暂存目录并重写数百 MB，
        # 紧接着测直拷会读到冷页缓存（W1 从 135 ms 涨到 280 ms），那是测量污染而非真实差距。
        for round_no in range(1, args.rounds + 1):
            output = RPM_BENCH / f"rps-{name}.zip"
            wall, peak, out, err, code = run(
                [args.exe, "merge", "--request", spec["request"], "--output", output], ROOT
            )
            if code != 0:
                raise SystemExit(f"rps-pack 失败（退出码 {code}）：{out[-500:]}")
            try:
                report = json.JSONDecoder().raw_decode(out.lstrip())[0]
            except json.JSONDecodeError as error:
                raise SystemExit(f"rps-pack 输出无法解析：{error}；{out[:300]}")
            rows.append({
                "arm": "rps", "round": round_no, "wall_ms": round(wall, 1),
                "peak_mb": round(peak / 1048576, 1), "internal_ms": report.get("millis", -1),
                "bytes": report.get("bytes", 0), "entries": report.get("entries", 0),
                "rawCopied": report.get("rawCopied", 0), "sha1": report.get("sha1", ""), "code": code,
            })

        for round_no in range(1, args.rounds + 1):
            outdir = RPM_BENCH / f"out-{name}"
            cmd = [
                args.java, args.heap, "-cp", f"{args.classes};{args.gson}", "RpmHarness",
                outdir, "merged", *spec["packs"],
            ]
            wall, peak, out, err, code = run(cmd, RPM_BENCH, outdir)
            fields = parse_fields(out)
            if code != 0 or "millis" not in fields:
                raise SystemExit(f"RpmHarness 失败（退出码 {code}）：{out[-500:]}")
            rows.append({
                "arm": "rpm", "round": round_no, "wall_ms": round(wall, 1),
                "peak_mb": round(peak / 1048576, 1), "internal_ms": int(fields.get("millis", -1)),
                "bytes": int(fields.get("bytes", 0)), "sha1": fields.get("sha1", ""), "code": code,
            })

        chain_bytes = sum(pack.stat().st_size for pack in spec["packs"])
        results[name] = {"sources": len(spec["packs"]), "chain_bytes": chain_bytes, "rows": rows}
        print(f"== {name}: {len(spec['packs'])} 源，链式下载 {chain_bytes} 字节")
        for row in rows:
            print("  ", row)

    Path(args.json).parent.mkdir(parents=True, exist_ok=True)
    Path(args.json).write_text(json.dumps(results, indent=2), encoding="utf-8")

    print("\n中位：")
    for name, data in results.items():
        for arm in ("rpm", "rps"):
            rows = [row for row in data["rows"] if row["arm"] == arm]
            if not rows:
                continue
            print(
                f"  {name:8s} {arm} 墙钟 {statistics.median([r['wall_ms'] for r in rows]):8.1f} ms"
                f"  峰值 {statistics.median([r['peak_mb'] for r in rows]):6.1f} MB"
                f"  产物 {statistics.median([r['bytes'] for r in rows]):,} B"
            )


if __name__ == "__main__":
    main()
