# 合并器基准

复现 `docs/rps-pack-maxperf-plan.md` 里的数字。

## 工作负载

`gen2.py` 生成两套输入（默认写入 `.tmp/bench`，请求文件也在那里）：

| 工作负载 | 源数 | 内容 | 用途 |
| --- | --- | --- | --- |
| W1 超大 | 3 | 270 MB 载荷、469 条目 | 单包体积与 deflate 吞吐 |
| W2 超多 | 40 | 86 MB 载荷、5,063 条目、182 处冲突 | 条目元数据与冲突扫描 |

输入用随机字节生成，deflate 基本不可压缩，属最坏情况。

## 测时与峰值内存

```powershell
pwsh -NoProfile -File tools/bench/measure.ps1 -exe <exe> -argline "<参数>" -cwd <工作目录>
```

输出 JSON：`ms`（墙钟）、`peak`（每 20 ms 采样的峰值 WorkingSet64）、`exit`、`out`、`err`。

`-quiet` 只输出 `{ms,peak,exit}`，不带报告正文（W2 的报告有 435 KB，会把输出撑爆）。

小包（十几毫秒级）不要用 `measure.ps1`：`Start-Process` 的固定开销约 40–50 ms，会把真实耗时淹没。用 Python 计时：

```bash
python tools/bench/latency.py rust/rps-pack/target/release/rps-pack.exe runs=15 merge --request .tmp/bench/lat/req-micro.json --output .tmp/bench/lat/out-pm.zip
```

它打印 `wall ms`（min / median / max）与进程内自报的 `internal ms`，两者之差就是进程启动与计时的开销。

`run-bench.ps1` 是批量包装：重复 N 次，并解析合并器自报的 `millis` / `bytes` / `sha1`。

```powershell
pwsh -NoProfile -File tools/bench/run-bench.ps1 -Request .tmp/bench/request-large.json -Output .tmp/bench/out.zip -Exe rust/rps-pack/target/release/rps-pack.exe -Runs 3
```

## Java 基线

- `Bench.java`：单线程 ZipInputStream → Map → ZipOutputStream，level 6，代表老牌插件的写法。
- `DeflateBench.java`：只跑 deflate 的并行度微基准（1..N 线程），用来估算并行压缩上限。

```powershell
& "$env:JAVA_HOME/bin/java.exe" tools/bench/Bench.java out.zip .tmp/bench/large-a.zip .tmp/bench/large-b.zip .tmp/bench/large-c.zip
& "$env:JAVA_HOME/bin/java.exe" tools/bench/DeflateBench.java
```

## 注意

- 只比同一台机器、同一 JDK / Rust 版本；绝对值没有意义，比值才有。
- Windows 上用 `pwsh` 跑脚本；`java.exe` 直接执行单文件源码需要 JDK 11+。
