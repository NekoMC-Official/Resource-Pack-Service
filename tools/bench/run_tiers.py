"""跑 S13 五档基准：micro / small / W1 / W2 / many-400k。

用法：python run_tiers.py <基准目录> [--reps 3]
环境：RPS_PACK_BIN 指向 rps-pack 可执行文件。
峰值内存：Windows 用 psapi GetProcessMemoryInfo 轮询工作集，Linux 读 /proc/<pid>/status 的 VmHWM。
"""
import ctypes
import json
import os
import statistics
import subprocess
import sys
import threading
import time

base = os.path.abspath(sys.argv[1])
reps = 3
if "--reps" in sys.argv:
    reps = int(sys.argv[sys.argv.index("--reps") + 1])
exe = os.environ.get(
    "RPS_PACK_BIN",
    os.path.join(os.path.dirname(base), "..", "rust", "rps-pack", "target", "release", "rps-pack.exe"),
)
exe = os.path.abspath(exe)
tiers = ["micro", "small", "w1", "w2", "many"]
result = {}


class _ProcessMemoryCounters(ctypes.Structure):
    _fields_ = [
        ("cb", ctypes.c_ulong),
        ("PageFaultCount", ctypes.c_ulong),
        ("PeakWorkingSetSize", ctypes.c_size_t),
        ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t),
        ("PeakPagefileUsage", ctypes.c_size_t),
    ]


def peak_rss_bytes(pid):
    """当前为止的峰值工作集；进程已退出或平台不支持时返回 0。"""
    if os.name == "nt":
        handle = ctypes.windll.kernel32.OpenProcess(0x0410, False, pid)
        if not handle:
            return 0
        try:
            counters = _ProcessMemoryCounters()
            counters.cb = ctypes.sizeof(counters)
            if not ctypes.windll.psapi.GetProcessMemoryInfo(
                handle, ctypes.byref(counters), counters.cb
            ):
                return 0
            return int(counters.PeakWorkingSetSize)
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    try:
        with open("/proc/%d/status" % pid, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("VmHWM:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


for tier in tiers:
    request = os.path.join(base, "request-%s.json" % tier)
    output = os.path.join(base, "merged-%s.zip" % tier)
    if not os.path.isfile(request):
        result[tier] = {"error": "缺少 " + request}
        continue

    def once():
        started = time.perf_counter()
        proc = subprocess.Popen(
            [exe, "merge", "--request", request, "--output", output],
            stdout=subprocess.PIPE,
        )
        peak = [0]
        done = threading.Event()

        def sample():
            while True:
                peak[0] = max(peak[0], peak_rss_bytes(proc.pid))
                if done.wait(0.001):
                    break

        watcher = threading.Thread(target=sample, daemon=True)
        watcher.start()
        stdout, _ = proc.communicate()
        done.set()
        watcher.join()
        wall = (time.perf_counter() - started) * 1000
        if proc.returncode != 0:
            raise SystemExit(stdout.decode("utf-8", "replace"))
        return wall, peak[0], json.loads(stdout.decode("utf-8"))

    once()
    walls, peaks, internals, report = [], [], [], None
    for _ in range(reps):
        wall, peak, report = once()
        walls.append(wall)
        peaks.append(peak)
        internals.append(report["millis"])
    result[tier] = {
        "wall_median": round(statistics.median(walls), 1),
        "wall_min": round(min(walls), 1),
        "wall_max": round(max(walls), 1),
        "internal_median": statistics.median(internals),
        "peak_rss_mb": round(statistics.median(peaks) / (1024 * 1024), 1)
        if peaks and min(peaks) > 0
        else None,
        "bytes": report["bytes"],
        "entries": report["entries"],
        "deduped": report.get("deduped", 0),
        "conflicts": report.get("conflicts")
        if isinstance(report.get("conflicts"), int)
        else len(report.get("conflicts") or []),
        "rawCopied": report.get("rawCopied", 0),
        "sha1": report["sha1"],
    }

print(json.dumps(result, ensure_ascii=False, indent=2))
