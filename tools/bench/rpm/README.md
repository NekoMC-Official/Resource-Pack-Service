# ResourcePackManager 对照基准

用 RPM 自己发布的合并管线（MixEngine.run：解压暂存 + JSON 合并 + ParallelZipWriter 4 线程 deflate）
跑同一批夹具，与 rps-pack 的输出并排比较。RPM 是 GPL 项目，这里只放一个驱动类，不复制它的源码。

## 准备

```powershell
git clone https://github.com/MagmaGuy/ResourcePackManager .tmp/rpm
# 固定到被测提交（本仓报告用的是 2.4.14 / 1aa7718）
git -C .tmp/rpm checkout 1aa7718ec4ae248df6b6b07568fcf873a3dfb111
pwsh -NoProfile -File tools/bench/rpm/build.ps1
```

build.ps1 会按需下载 gson 2.11.0 到 .tmp/rpm-bench/lib，用 JDK 17+ 编译 mixer/engine 与
RpmHarness.java。可用参数：-Proxy（默认 http://127.0.0.1:7890，传空串则不代理）、
-RpmRoot、-GsonJar、-OutDir；JDK 取 JAVA_HOME，缺省回退 C:/Program Files/Zulu/zulu-25。

## 夹具

```powershell
python tools/bench/gen_tiers.py .tmp/bench-s13     # w1 / w2 / many（400k 条目）
python tools/bench/gen_many20k.py .tmp/bench-s13   # many20k（19,601 条目）
python tools/bench/gen_overlap.py .tmp/bench-s7    # s7（40 源高重叠，182 条冲突）
```

bench.py 按存在情况自动发现档位：w1、w2、many20k、s7。夹具缺哪档就不跑哪档，
所以首次运行只需生成你关心的档。

## 运行

```powershell
python tools/bench/rpm/bench.py --rounds 3 `
  --exe rust/rps-pack/target/release/rps-pack.exe `
  --json .tmp/rpm-bench/result.json
```

每档先连跑 3 轮 rps-pack、再连跑 3 轮 RPM。交替跑会让 RPM 解压暂存留下的数百 MB 脏页
把直拷计时抬高近一倍（W1 147 → 281 ms），那是测量污染而不是真实差距；跨档之间仍有少量
残留（下一档首轮偏慢），中位数会吸收它。采样 PeakWorkingSetSize（3 ms 间隔），输出墙钟、
内部耗时、峰值内存、产物字节与 SHA-1。

## 口径

- RPM 侧是**完整管线**：先逐个解压到 outputDir 暂存目录、Gson 合并冲突 JSON、再重压写出；
  这既是它的生产路径，也是它比「只算 deflate」慢得多的原因。RpmHarness 传
  writeCollisionLog=false，RPM 少写一份冲突日志，对竞品略有利。
- rps-pack 侧走中央目录扫描与原始字节直拷，只有冲突 JSON 会被重写。
- 两臂产物字节不相等（容器布局、是否重压、JSON 合并顺序都不同），比较的是**同输入的耗时与内存**，
  不是产物等价。夹具固定了种子与 zip 时间戳，同一档位重复跑 SHA-1 稳定。
- 仅 Windows 可跑（峰值采样走 psapi，classpath 用分号分隔）。
- 数字来自单机暖缓存：Ryzen 7 9700X / Windows 11 / Zulu 25 / -Xmx4G。
