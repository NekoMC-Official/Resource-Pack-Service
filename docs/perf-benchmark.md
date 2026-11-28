# 资源包合并性能对比报告（2026-11-27）

> 对象：本仓库 `rps-pack`（Rust 合并器，Resource Pack Service 0.1.0 内置）  
> 基线：同语义 Java 单线程实现（`ZipInputStream` + 内存去重 + `ZipOutputStream` level 6）——RoseResourcepack 一类老牌插件采用的写法  
>
> **2026-11-28 补注**：本文数字是优化前的基线（当时的实现会把整包解压进内存再重压，`archive.rs` 已在 S2 删除）。当前实现走「扫中央目录 + 原始字节直拷 + 写时单趟 SHA-1，S14 起在独立线程与写重叠」，同夹具 W1 约 140 ms / 12 MB、W2 约 90 ms / 27 MB，见 `docs/rps-pack-maxperf-plan.md` §2–§35。本文保留用于说明「为什么要改」。

---

## 0. 一句话结论

| 问题 | 结论 |
| --- | --- |
| 超大资源包（GB 级字节量） | 比「Java 单线程重打包」快 **1.20×**，产物小 0.005%；但峰值内存高 18%（547 MB / 270 MB 输入）。**压缩核（zlib-rs）单核比 JVM Deflater 快 15–30%**，这是快的主因。 |
| 超多资源包（几十个来源、上万条目） | 与 Java 单线程 **打平**（973 ms vs 924 ms，墙钟 ≈1.00×），但 **峰值内存低 3.9–6.6×**（100 MB vs 390 MB），因为 Rust 端没有每条目双份拷贝。 |
| 对 ResourcePackManager（4 线程并行写入器） | **实测远快于它**（§9，RPM 2.4.14 完整管线）：W1 147 ms vs 3.0 s（**20.6×**）、W2 108 ms vs 15.1 s（**141×**）、19.6k 条目 67 ms vs 25.3 s（**380×**）、高重叠 5,063 条 127 ms vs 18.8 s（**149×**）；峰值内存只有它的 1/11–1/25。旧「估算 1.5–1.9 s / 0.45–0.6 s」是只测压缩核的外推，作废。 |
| 对 ResourcepacksPlugins | **不构成竞争**：它不合并、不打包，只按代理/世界下发 URL，合并成本为 0，但前提是你已经有一个合并好的包。 |
| 对 RoseResourcepack | 我们快 1.20×，且自动侦测重建 + HTTP ETag/304 + 逐玩家回执；它是手动 `/roserp zip` + 单线程 `ZipOutputStream`，代码中未见 ETag。 |

---

## 1. 测试环境与口径

| 项 | 值 |
| --- | --- |
| CPU | AMD Ryzen 7 9700X（8 核 16 线程） |
| 系统 | Windows 11 |
| JVM | Zulu 25（`C:/Program Files/Zulu/zulu-25`） |
| Rust | rustc 1.100.0，release（LTO + strip） |
| 计时 | ① 进程墙钟（PowerShell `Stopwatch` 包住整个子进程）② 工具自报 `millis`（只含合并逻辑） |
| 峰值内存 | 20 ms 轮询 `Process.WorkingSet64` 取最大值（含进程启动与读入） |
| 取样 | 每档先跑 1 次预热（丢弃），再取样 3–5 次取中位数 |
| 输入 | 随机字节（不可压缩，deflate 的最坏情况）+ 真实结构的小 JSON（模型/语言文件） |

---

## 2. 工作负载

### W1 超大资源包（少量来源、单包很大）

| 项 | 值 |
| --- | --- |
| 来源数 | 3（`large-a` / `large-b` / `large-c`，priority 0/1/2） |
| 原始字节 | 270,173,442 B（257.6 MiB） |
| 条目数 | 469（每包 8–4 个 15 MB 二进制 + 150 个 JSON） |
| 最大单条目 | 15 MB（低于 rps-pack 的 64 MB 单条目上限） |

### W2 超多资源包（大量来源、大量条目、重度重叠）

| 项 | 值 |
| --- | --- |
| 来源数 | 40（`many-00` … `many-39`，priority 全 0，同权按来源顺序决定同名条目的胜者） |
| 原始字节 | 86,484,750 B（82.5 MiB） |
| 条目数 | 5,063（每个来源 300 JSON + 4 × 512 KB 二进制；每包 180 条共享命名空间，合并后重复率 58.5%） |
| 冲突 | 182 条（180 model + 2 texture），最热的条目有 39 个 loser |
| 合并报告 | 435 KB stdout（含每冲突的 winnerSha1 + 39 个 loserSha1） |

---

## 3. 结果

### 3.1 W1 超大资源包

| 指标 | rps-pack（Rust） | Java 单线程基线 | 对比 |
| --- | --- | --- | --- |
| 自报合并耗时（中位） | **4,073 ms** | 4,881 ms | **1.20× 更快** |
| 进程墙钟（中位） | 4,135 ms | 4,965 ms | 1.20× |
| 峰值 RSS | 547 MB | 455–472 MB | 高 16–20% |
| 产物大小 | **270,174,433 B** | 270,189,028 B | 小 0.005% |
| 单核压缩速度 | ≈ 66 MB/s | ≈ 57 MB/s（纯 deflate 4,195 ms） | 1.15–1.3× |

### 3.2 W2 超多资源包

| 指标 | rps-pack（Rust） | Java 单线程基线 | 对比 |
| --- | --- | --- | --- |
| 自报合并耗时（中位） | 973 ms | **924 ms** | 慢 5% |
| 进程墙钟（中位） | 999 ms | 997 ms | ≈ 1.00× |
| 峰值 RSS | **59–100 MB** | 388–391 MB | **低 3.9–6.6×** |
| 产物大小 | **44,065,368 B** | 44,225,805 B | 小 0.36% |
| 冲突报告 | 182 条 / 435 KB | 无 | 我们多做的功 |

> W2 里 Rust 慢的那 5% 基本就是冲突扫描 + 每冲突 SHA-1 的成本：180 个冲突条目 × 39 个 loser，加上 435 KB 报告序列化。

### 3.3 压缩阶段并行度（回答「RPM 会不会更快」）

同一批输入，只测「读入 + 逐条目 deflate」，不做 IO 落盘：

| 负载 | 单线程 deflate | 4 线程 deflate | 提速 |
| --- | --- | --- | --- |
| W1（270 MB） | 4,195 ms | 1,220 ms | **3.44×** |
| W2（84.7 MB） | 1,349 ms | 394 ms | **3.42×** |

ResourcePackManager 的 `ParallelZipWriter` 正是 4 worker + 8 MB 单条目内存阈值 + 背压队列（`MAX_PENDING_ENTRIES = 8`），因此：

- 这只说明它有并行压缩的潜力；完整管线还要把每个来源解压到暂存目录、逐条重压，成本远高于压缩核本身。
- 一对一实测见 §9：RPM 完整管线 W1 3.0 s、W2 15.1 s，本仓为 147 ms / 108 ms；「会输 2–3×」的旧结论作废。

> 2026-11-28 补：以上是只测压缩核的外推。RPM 完整管线的一对一实测见 §9（W1 3.0 s、W2 15.1 s），比估算慢一个数量级——暂存落盘与 JSON 合并都没有计入。

---

## 4. 三个竞品的实现事实（源码核对）

| 项目 | 合并方式 | 并行 | 分发 | 自动重建 |
| --- | --- | --- | --- | --- |
| **我们（rps-pack）** | Rust 单趟读全部来源 → `BTreeMap` 覆盖/深度合并 → 单线程 deflate 写出（`engine.rs` `DEFLATE_LEVEL = 6`，后端 `deflate-flate2-zlib-rs`） | ❌ 单线程 | 内置 `PackHttpServer`：`ETag` + `If-None-Match` → `304`（`PackHttpServer.java:114-119`），`FileChannel` 定长流式 | ✅ watch 5 s + 异步重建，不占主线程 |
| **RoseResourcepack** | Java `ZipOutputStream` 单线程（`packer/Packer.java`，`BUFFER_SIZE = 64 KB`），从文件夹或已有 zip 重新打包 | ❌ | 自建 HTTP（`host/Hosting.java`，1 acceptor + worker pool），未见 ETag/304 | ❌ `/roserp zip` 手动触发 |
| **ResourcepacksPlugins** | **不合并**（只按代理/服务器/世界下发 URL） | — | 依赖外部 URL | — |
| **ResourcePackManager** | Java `ParallelZipWriter`：4 worker、8 MB 内存阈值、8 条在途队列、`Deflater.DEFAULT_COMPRESSION`，超阈值转磁盘直写 | ✅ 4 线程 | `PackHttpServer`（587 行）：强 SHA-1 ETag、304、`FileChannel`、线程池 | ✅ 包变化后重混 |

---

## 5. 分析

### 5.1 大包：瓶颈是压缩核，不是语言

W1 中 deflate 占 Java 基线总耗时的 86%（4,195 / 4,881 ms）。Rust 端 zlib-rs 单核比 JVM 自带 zlib 快 15–30%，所以总耗时领先 1.20×——**这与「Rust 一定比 Java 快」无关，纯粹是压缩库的单核效率**。若 Java 侧换成 libdeflate 或 zstd，差距会消失。

### 5.2 多包：瓶颈是条目元数据与内存分配

W2 只有 84.7 MB，却要处理 5,063 个条目、40 个 zip 中央目录、182 个冲突。此时：

- 双方耗时都在 1 s 左右，差距被小文件 IO 与分配抹平；
- 内存差距被放大：Java 基线每个条目在 `ZipInputStream` 缓冲与 `Map` 里各存一份，峰值 390 MB（≈4.6× 输入）；Rust 端 `BTreeMap<String, Blob>` 只存一份，峰值 59–100 MB；
- 我们额外做冲突比对 + 每冲突 SHA-1 + 435 KB 报告，代价约 5%。

### 5.3 内存模型与硬上限（重要风险）

- `rust/rps-pack/src/archive.rs:13` `MAX_ENTRY_BYTES = 64 MB`，`:15` `MAX_TOTAL_ENTRIES = 20_000`；
- 合并全程驻留内存：峰值 ≈ 2.0×原始大小（W1 547 MB / 270 MB）；
- 触上限不是 OOM，而是**直接报错**：真实服务器若有一个 80 MB 的 `.ogg` 或合并图集，`/rps build` 会失败；
- 40 个来源 × 300 文件的重叠场景，报告可涨到数 MB（每冲突保留全部 loser 的 SHA-1）。

### 5.4 我们的真实优势不在合并，在「运行期」

1. **ETag/304**：客户端重复进服只花一个 304（RoseRP 无，需整包重下 18–270 MB）；
2. **自动侦测重建**：watch 5 s，改文件即重混，RoseRP 要手动敲命令；
3. **异步**：合并在调度器异步线程，不卡主线程 tick；
4. **逐玩家回执**：LOADED/DECLINED/FAILED 状态落盘，必装弹窗语义可控（`announce-required`）；
5. **双端同源**：Paper/Folia 与 Fabric 共用同一 `:core` 与同一 Rust 合并器。

---

## 6. 建议（按性价比排序）

| 优先级 | 动作 | 预期 |
| --- | --- | --- |
| P0 | 给 `rps-pack` 引入 entry 级并行 deflate（rayon，限流 4–8 在途，参考 RPM 的 8 MB 阈值） | W1 4.07 s → 1.4–1.7 s；W2 0.97 s → 0.35–0.5 s，追平/超过 RPM |
| P0 | 冲突报告瘦身：`loserSha1s` 截断为前 5 个 + 计数；冲突明细默认只保留前 200 条 | W2 报告 435 KB → ~10 KB，序列化/传输成本几乎归零 |
| P1 | 超大包流式化：累计超阈值（如 128 MB）转磁盘暂存，避免 2× 驻留 | 内存峰值降到 ~50 MB 量级，GB 级包可用 |
| P1 | 放宽/参数化单条目 64 MB 上限，超限走「原样透传不重压缩」分支 | 避免真实大包直接构建失败 |
| P2 | HTTP 增加 `Range` 与 `Content-Length` 复用（当前已定长 + 304） | 断点续传、弱网更稳 |
| P2 | 基准纳入 CI（可选门限），防并行化后退化 | 长期可回归 |

---

## 7. 复现方式

```text
.tmp/bench/gen2.py            生成 W1/W2 输入（40 个 many-*.zip、3 个 large-*.zip）
.tmp/bench/Bench.java         Java 单线程基线（zip 读入 + 去重 + deflate 6 写出）
.tmp/bench/DeflateBench.java  压缩阶段单线程 vs 4 线程微基准
.tmp/bench/measure.ps1        墙钟 + 峰值 WorkingSet64 采样
.tmp/bench/request-large.json / request-many.json    rps-pack 请求
```

```bash
# 与 ResourcePackManager 的一对一对照（先看 tools/bench/rpm/README.md）
# powershell -File tools/bench/rpm/build.ps1
# RPS_PACK_BIN=rust/rps-pack/target/release/rps-pack.exe python tools/bench/rpm/bench.py --rounds 3

# 我们的合并器
rust/rps-pack/target/release/rps-pack.exe merge \
  --request .tmp/bench/request-large.json --output .tmp/bench/merged-large-rust.zip

# Java 基线
"C:/Program Files/Zulu/zulu-25/bin/java.exe" -Xmx4G -cp .tmp/bench Bench \
  large-a.zip large-b.zip large-c.zip

# 压缩阶段并行度
"C:/Program Files/Zulu/zulu-25/bin/java.exe" -Xmx4G -cp .tmp/bench DeflateBench \
  large-a.zip large-b.zip large-c.zip
```

---

## 8. 口径与局限

1. Java 基线是**按 RoseRP 写法重写的同语义实现**，不是 RoseRP 原码（原码还带 Bukkit 依赖，无法独立跑）；因此 1.20× 反映「同类实现」的差距，不是对 RoseRP 二进制的一对一测量。
2. ResourcePackManager 已在 2026-11-28 做一对一实测（§9）：用它的 MixEngine 完整管线，跳过需要 MC 平台的 Bukkit 部分，仅以 RpmHarness 驱动。§3.3 的 1.5–1.9 s / 0.45–0.6 s 是只测压缩核的外推，已被 §9 作废；§0 与 §3.3 的对应结论已改写。
3. 输入用随机字节模拟「已压缩的 PNG/OGG」，对 deflate 是最坏情况；纯 JSON 包（可压 4–10×）的绝对耗时会更低，但相对关系不变。
4. 所有数据来自同一台机器、暖文件缓存；跨机器绝对值会有 ±20% 波动。
5. 竞品 commit：RoseResourcepack / ResourcepacksPlugins / ResourcePackManager 均为 2026-11-27 clone 的默认分支 HEAD（`git log -1` 见 `.tmp/rose`、`.tmp/rpp`、`.tmp/rpm`）。

---

## 9. 与 ResourcePackManager 的一对一实测（2026-11-28）

### 9.1 方法与夹具

| 项 | 值 |
| --- | --- |
| 对照对象 | ResourcePackManager 2.4.14，commit 1aa7718，MixEngine 完整管线：逐包解压到暂存目录 → Gson 深度合并 JSON → ParallelZipWriter（4 worker）重压 → SHA-1 |
| 驱动 | tools/bench/rpm/：RpmHarness.java（只调 MixEngine，SILENT 日志）、build.ps1（编译 + 取 gson）、bench.py（两臂分段采样：先直拷 3 轮，再 RPM 3 轮） |
| 机器 | Ryzen 7 9700X / Windows 11 / Zulu 25 -Xmx4G / rps-pack release（LTO + strip） |
| 夹具 | w1 / w2 由 tools/bench/gen_tiers.py 生成，many20k 由 gen_many20k.py 生成，s7 由 gen_overlap.py 生成（40 源、去重后 5,063 条、182 条冲突）；三份生成器都在仓内，固定种子与 zip 时间戳。w1 / w2 / many20k 可逐字节复现；s7 的纹理随机源与本节实测那次不同，只保证规模与条目数一致 |
| 取样 | 每档 3 轮取中位；峰值工作集 3 ms 采样 |
| 顺序 | 两臂分段：先连跑 3 轮直拷，再连跑 3 轮 RPM。交替跑会被 RPM 留下的数百 MB 脏页污染——W1 直拷从 147 ms 涨到 281 ms，那是测量误差而不是真实差距。跨档仍残留少量污染（下一档直拷首轮偏慢），取中位吸收 |
| 可比性 | RPM 用 ZipOutputStream 重压，rps 直拷原始 deflate 字节，产物大小与 sha1 不同；本表只比耗时与内存，不比字节 |
| 平台 | 仅 Windows（峰值采样走 psapi，classpath 用分号分隔） |

### 9.2 结果

| 档 | 来源 | 条目 | 链式下载字节 | RPM 墙钟（中位） | rps 墙钟（中位） | 倍数 | RPM 峰值 | rps 峰值 | RPM 产物 | rps 产物 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| w1 | 3 | 469 | 270,166,692 | 3,018 ms | **147 ms** | **20.6×** | 139 MB | **12.6 MB** | 270,182,276 B | 270,166,333 B |
| w2 | 40 | 12,161 | 86,150,367 | 15,147 ms | **108 ms** | **141×** | 386 MB | **26.8 MB** | 86,556,897 B | 86,143,428 B |
| many20k | 4 | 19,601 | 3,642,408 | 25,292 ms | **67 ms** | **380×** | 384 MB | **15.5 MB** | 4,308,294 B | 3,641,861 B |
| s7 | 40 | 5,063 | 86,484,750 | 18,790 ms | **127 ms** | **149×** | 375 MB | **25.6 MB** | 44,255,247 B | 44,053,665 B |

倍数按未取整的中位相除（例如 s7 = 18,790.1 / 126.5 = 148.5），用表内取整后的数字复算会有 1 以内的偏差。

逐轮范围（墙钟）：w1 RPM 2,981–3,786 / rps 143–160；w2 15,087–15,420 / 97–156；many20k 23,565–26,760 / 64–90；s7 16,095–20,956 / 126–271。RPM 的方差主要来自 JVM 预热与 Gson 合并，rps 的离群值是系统后台负载。

### 9.3 客户端链式字节 vs 合并包

| 档 | 链式（来源数） | 合并包 | 差 |
| --- | --- | --- | --- |
| w1 | 270,166,692 B（3 次请求） | 270,166,333 B | −359 B（0.0001%） |
| w2 | 86,150,367 B（40 次请求） | 86,143,428 B | −6,939 B（0.008%） |
| s7 | 86,484,750 B（40 次请求） | 44,053,665 B | **−49.1%** |
| many20k | 3,642,408 B（4 次请求） | 3,641,861 B | −547 B |

合并真正省的是**请求数**与**跨包重名**。新夹具（w2 / many20k）几乎不重名，字节只有千分之几的差；s7 那类「同一批模型/材质被多个插件分别打包」的场景才省一半。

### 9.4 为什么差距比旧报告大

1. RPM 把每个来源整包解压进暂存目录（w2 约 12,000 个文件、many20k 约 19,600 个），再逐条重压；rps 扫中央目录后直拷原始 deflate 字节，单趟 SHA-1 自 S14 起在独立线程与写出重叠。
2. RPM 的写入器只有 4 个 worker，而且 JSON 冲突要走 Gson 深度合并；many20k 产物才 4.3 MB，25 秒里大部分是逐条小文件落盘与合并。
3. 旧报告 §3.3 的 1.5–1.9 s / 0.45–0.6 s 是「只测压缩核 4 线程」的外推，没有计入暂存落盘与 JSON 合并，因此比实测低一个数量级。

### 9.5 结论

> 本机单进程、暖缓存下，rps-pack 比 ResourcePackManager 2.4.14 的完整管线快 **20.6×（W1）、141×（W2）、380×（many20k）、149×（s7）**，峰值内存只有它的 1/11–1/25。线上还要叠加 HTTP 出口与客户端下载，端到端的倍数会被摊薄，但这已经能说明「合并这步不是瓶颈」。

---

*报告生成：2026-11-27；2026-11-28 补 §9（竞品一对一实测）并作废 §3.3 的外推估算；脚本：tools/bench/rpm/ 与 tools/bench/gen_tiers.py*
