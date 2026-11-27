import json, os, struct, subprocess, sys, time, zipfile, statistics, tempfile
binary = r"D:\github-code\Resource Pack Service\rust\rps-pack\target\release\rps-pack.exe"
work = tempfile.mkdtemp(prefix="rps-serve-bench-")
src = os.path.join(work, "source.zip")
with zipfile.ZipFile(src, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 46, "description": "bench"}}))
    z.writestr("assets/minecraft/texts/tiny.txt", "x" * 1024)
proc = subprocess.Popen([binary, "serve", "--idle-seconds", "30"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, cwd=work)
def frame(out):
    return json.dumps({"output": out, "request": {"format": 0, "description": "d", "policy": "override", "json_merge": True,
        "sources": [{"id": "a", "path": src, "priority": 0}]}}).encode()
def send(payload):
    proc.stdin.write(struct.pack("<I", len(payload))); proc.stdin.write(payload); proc.stdin.flush()
def read():
    header = proc.stdout.read(4)
    n = struct.unpack("<I", header)[0]
    return json.loads(proc.stdout.read(n))
send(frame(os.path.join(work, "warm.zip"))); read()
times = []
for i in range(20):
    started = time.perf_counter()
    send(frame(os.path.join(work, f"out-{i}.zip")))
    report = read()
    times.append((time.perf_counter() - started) * 1000)
    assert report.get("ok"), report
proc.stdin.close(); code = proc.wait(timeout=10)
print(json.dumps({"frames": len(times), "median_ms": round(statistics.median(times), 3), "mean_ms": round(statistics.mean(times), 3),
                  "min_ms": round(min(times), 3), "max_ms": round(max(times), 3), "exit": code}, ensure_ascii=False))
