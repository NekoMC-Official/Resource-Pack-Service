# 资源包合并性能对比报告（2026-11-27）

> 对象：本仓库 `rps-pack`（Rust 合并器，Resource Pack Service 0.1.0 内置）  
> 基线：同语义 Java 单线程实现（`ZipInputStream` + 内存去重 + `ZipOutputStream` level 6）——RoseResourcepack 一类老牌插件采用的写法  
> 参考竞品：RoseResourcepack、ResourcepacksPlugins、ResourcePackManager（源码均取自 GitHub 默认分支，commit 见文末）

---

## 0. 一句话结论

| 问题 | 结论 |
| --- | --- |
| 超大资源包（GB 级字节量） | 比「Java 单线程重打包」快 **1.20×**，产物小 0.005%；但峰值内存高 18%（547 MB / 270 MB 输入）。**压缩核（zlib-rs）单核比 JVM Deflater 快 15–30%**，这是快的主因。 |
| 超多资源包（几十个来源、上万条目） | 与 Java 单线程 **打平**（973 ms vs 924 ms，墙钟 ≈1.00×），但 **峰值内存低 3.9–6.6×**（100 MB vs 390 MB），因为 Rust 端没有每条目双份拷贝。 |
| 对 ResourcePackManager（4 线程并行写入器） | **不占优**。按其 4 worker 设计做同输入微基准，压缩阶段可提速 **3.42–3.44×**；据此估算它在 W1 约 1.5–1.9 s、W2 约 0.45–0.6 s，快于我们。这是必须补的短板。 |
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
| 来源数 | 40（`many-00` … `many-39`，priority = 序号） |
| 原始字节 | 86,484,750 B（82.5 MiB） |
| 条目数 | 5,063（每个来源 300 JSON + 4 × 512 KB 二进制；45% 名字跨包重叠） |
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

- **估算**（非实测）：W1 约 1.5–1.9 s，W2 约 0.45–0.6 s；
- 我们当前的单线程实现在多核机器上会输给它 2–3×。

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
2. ResourcePackManager **未实测**（其 `ParallelZipWriter` 依赖整套 Gradle 模块与 MC 依赖）；文中 1.5–1.9 s / 0.45–0.6 s 是由同输入 4 线程 deflate 微基准外推的估算，已在表中标注。
3. 输入用随机字节模拟「已压缩的 PNG/OGG」，对 deflate 是最坏情况；纯 JSON 包（可压 4–10×）的绝对耗时会更低，但相对关系不变。
4. 所有数据来自同一台机器、暖文件缓存；跨机器绝对值会有 ±20% 波动。
5. 竞品 commit：RoseResourcepack / ResourcepacksPlugins / ResourcePackManager 均为 2026-11-27 clone 的默认分支 HEAD（`git log -1` 见 `.tmp/rose`、`.tmp/rpp`、`.tmp/rpm`）。

---

*报告生成：2026-11-27；数据与脚本：`.tmp/bench/`（gitignored）*
