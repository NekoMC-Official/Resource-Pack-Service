"""用 Python subprocess 计时，避免 PowerShell 启停开销污染小包墙钟。

用法：
    python tools/bench/latency.py <exe> [runs=N] <args...>

measure.ps1 走 Start-Process + ReadToEndAsync，固定开销约 40–50 ms，
测微包会把真实耗时淹没；这里用 perf_counter 卡在 subprocess.run 前后，
并同时从 stderr 的「用时 Xms」取进程内自报耗时做对照。
"""
import statistics
import subprocess
import sys
import time

if len(sys.argv) < 2:
    raise SystemExit(__doc__)

exe = sys.argv[1]
rest = sys.argv[2:]
runs = 9
if rest and rest[0].startswith("runs="):
    runs = int(rest[0].split("=", 1)[1])
    rest = rest[1:]

wall = []
internal = []
for _ in range(runs):
    start = time.perf_counter()
    proc = subprocess.run([exe, *rest], capture_output=True)
    wall.append((time.perf_counter() - start) * 1000)
    for line in proc.stderr.decode("utf-8", "replace").splitlines():
        if "用时" in line:
            internal.append(int(line.split("用时")[1].split("ms")[0]))

wall.sort()
print("wall ms: min %.1f median %.1f max %.1f (n=%d)" % (wall[0], statistics.median(wall), wall[-1], runs))
if internal:
    internal.sort()
    print("internal ms: median %d" % statistics.median(internal))
