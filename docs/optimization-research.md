# rps-pack 并行化与内存优化调研（2026-11-27）

> 结论先行（截至 2026-11-27）：**能多线程，但更值钱的是「不压缩」**。两阶段（只读中央目录 + 原始字节拷贝）原型在 W1 上把 4.07 s / 547 MB 打到 **0.18 s / 15 MB**（22×、36×），在 W2 上 0.97 s / 100 MB → **0.47 s / 55 MB**。做完这一步，并行 deflate 只剩「需要深度合并的少量 JSON」可优化，ROI 大幅下降。
>
> **2026-11-28 补注**：文中的两阶段原型已按 S2–S4 落进产品（`scan.rs` + `writer.rs`），条目上限也从 `20_000` 放宽到 `500_000`；当前实测见 `docs/rps-pack-maxperf-plan.md` §23–§35。

---

## 1. 现状与瓶颈定位

### 1.1 当前数据流（源码事实）

```text
archive.rs  逐来源打开 zip → 逐条目 read_to_end → Vec<u8>（单条目 ≤ 64 MB，累计 ≤ 20,000 条）
engine.rs   BTreeMap<String, Blob{ bytes, crc, sha1: OnceLock, layer, source, priority }>
            → 冲突比对（CRC/大小相同才算同内容，否则记冲突 + 懒算 SHA-1）
            → write_zip：单线程 ZipWriter，逐条目 write_all(bytes) 现场 deflate(level 6)
```

关键事实：

- `rust/rps-pack/src/archive.rs:13` `MAX_ENTRY_BYTES = 64 MB`、`:15` `MAX_TOTAL_ENTRIES = 20_000`；
- `rust/rps-pack/src/engine.rs:501-533` `write_zip`：`BufWriter<File>` + `ZipWriter`，每个条目 `write_all(&blob.bytes)`，压缩在写线程串行发生；
- `rust/rps-pack/src/crc.rs`：手写表驱动 CRC-32（无 PCLMUL/SIMD），W1 上要扫 270 MB；
- `Cargo.toml`：`zip 9`（`deflate-flate2-zlib-rs`）、`sha1 0.11`，无 rayon、无 mmap；
- `core/.../pack/PackPublisher.kt`：每次 `rebuild()` 都重新 spawn 合并进程、全量重算，**没有任何「输入没变就跳过」的判断**；watch 的 stamp 是 `mtime:size`，touch 一下就会全量重建。

### 1.2 瓶颈占比（实测）

| 负载 | 合并总耗时 | 纯 deflate（Java 单线程，同输入） | 说明 |
| --- | --- | --- | --- |
| W1（270 MB，469 条） | 4,073 ms | 4,195 ms | deflate ≈ 100%，纯 CPU |
| W2（84.7 MB，5,063 条） | 973 ms | 1,349 ms | deflate 占比被条目 IO/冲突扫描摊薄 |

也就是说：**W1 的时间全花在「重新压缩一遍本来就已经压缩过的 PNG/OGG」上**，这是最大的浪费——而且这些条目根本不需要重新压缩。

---

## 2. 问题一：能多线程合并吗？

能。但先要区分「合并」里哪一段可以并行：

| 阶段 | 可否并行 | 说明 |
| --- | --- | --- |
| 读来源 / 解压条目 | ✅ | 每个来源独立，随便并行；也可与写并行做流水线 |
| 冲突判定 | ✅ | 按名字分片，各线程独立比 CRC/大小 |
| JSON 深度合并 | ✅ | 每个文件独立（lang/sounds.json 等） |
| **压缩 + 写 zip** | ⚠️ 受限 | `zip` crate 的 `ZipWriter` 是顺序流，压缩在写线程内部完成，**没有「写入已压缩字节」的 API** |

### 2.1 三种落地方案

**方案 A：分片 zip + 原始字节合流（不改 zip 格式，最快能跑起来）**

1. 把条目按目标顺序切成 N 片（N = 核数，按字节量均衡）；
2. 每片用一个 `ZipWriter` 并行压缩成临时 zip；
3. 最后一遍：按顺序从各分片 `ZipWriter::raw_copy_file_rename` 把**已压缩字节**拷进最终 zip（`zip 9.8.2` 已提供该 API，实测存在）；
4. 删临时文件。

- 收益：压缩阶段吃满多核（微基准 4 线程 **3.42–3.44×**），W1 预计 4.07 s → **1.4–1.7 s**；
- 代价：临时空间 ≈ 输出大小、双倍写 IO、确定性要靠「分片顺序 + 片内顺序」保证；
- 风险：低（不碰 zip 格式），但要处理磁盘不足与清理。

**方案 B：自写 zip writer + 流水线并行（RPM 的做法）**

- 自己写 local header / data descriptor / central directory（约 300–400 行），用 `flate2` 直接产出 raw deflate；
- 工作线程池压缩，主线程按序写头与数据，在途队列限流（RPM：`MAX_WORKERS = 4`、`MAX_PENDING_ENTRIES = 8`、单条目 >8 MB 转磁盘直写）；
- 收益：无临时文件、内存可控、可做「大体量直写」；
- 代价与风险：最高（zip64、UTF-8 标志、确定性、CRC 都要自己兜），测试成本大。

**方案 C：只并行「需要重压缩的条目」（rayon，最小改动）**

- 现状里绝大多数条目是「原样覆盖」，真正需要重压缩的只有 lang/sounds/font/atlases 的深度合并结果与 mcmeta；
- 把这些条目并行压缩到内存/临时文件，其余走方案 A 的第 3 步或原始拷贝；
- 收益：做完第 3 节的 raw copy 之后，这部分本来就从「全部条目」缩到「少量条目」，收益有限（除非服务器有巨量 lang 合并）。

### 2.2 建议

**先做第 3 节的 raw copy，再评估是否需要方案 A。** 理由：raw copy 直接消掉了 90%+ 的压缩工作，剩下的并行收益从 3.4× 缩到「只对 5% 的条目有效」。若将来出现「几千个 lang 文件深度合并」的场景，再上方案 A/C。

---

## 3. 问题二：内存能怎么优化？

### 3.1 根因

当前实现把**每个条目的完整解压字节**留在 `BTreeMap` 里直到写完，所以峰值 ≈ 2× 输入：

| 负载 | 输入 | 当前峰值 RSS | 倍率 |
| --- | --- | --- | --- |
| W1 | 270 MB | 547 MB | 2.03× |
| W2 | 84.7 MB | 100 MB | 1.18× |

### 3.2 方案：两阶段 + 原始字节拷贝（原型已验证）

**阶段 1（元数据）**：只读各来源 zip 的中央目录，记录 `name → (来源序号, index, crc32, size, compressed_size)`，**不解压任何条目**。

**阶段 2（写出）**：按确定性顺序遍历条目：

| 条目类型 | 处理方式 | 内存 |
| --- | --- | --- |
| 单一来源、无需改写（绝大多数） | `raw_copy_file_rename` 直接拷压缩字节 | O(缓冲区) |
| 同名冲突且 policy=override/first | 只拷胜者，冲突只记元数据（无内容比较需求） | O(缓冲区) |
| lang/sounds/font/atlases 深度合并 | 仅这些条目解压 → 合并 → 重新压缩 | 单条目大小 |
| pack.mcmeta | 重新生成 | 极小 |

冲突判定用中央目录里的 `crc32 + size` 即可（当前代码已是这个语义），**不需要为了判冲突而解压**。SHA-1 只在「报告需要」且「确实有冲突」时才算，且可以只算胜者 + 截断 loser 列表。

### 3.3 原型实测（`.tmp/proto/`，约 120 行 Rust，只做 raw copy + mcmeta）

| 负载 | 当前实现 | 原型（raw copy） | 提升 |
| --- | --- | --- | --- |
| W1 耗时（进程内） | 4,073 ms | **132–191 ms** | **21–31×** |
| W1 墙钟 | 4,135 ms | 154–239 ms | 17–27× |
| W1 峰值 RSS | 547 MB | **14–15 MB** | **36×** |
| W2 耗时（进程内） | 973 ms | **468–480 ms** | 2.0× |
| W2 峰值 RSS | 100 MB | **55 MB** | 1.8× |
| 产物大小 | 270,174,433 B | 270,173,078 B | 基本一致 |

> 原型不含 JSON 深度合并、冲突报告、SHA-1、max-bytes 守卫，所以真实落地会慢一些。按条目结构估算：
> **W1 0.25–0.40 s（≈10–16×）、峰值 15–30 MB；W2 0.55–0.75 s（≈1.3–1.8×）、峰值 10–20 MB。**
>
> W2 原型峰值 55 MB 的来源已经定位：40 个来源各开了一个 1 MB 的 `BufReader`（40 MB）。把缓冲改成 64–128 KB，或只保留最近使用的 4 个 archive 句柄（LRU），峰值可降到 ~10 MB。

### 3.4 配套的内存优化点

| 优化 | 效果 | 成本 |
| --- | --- | --- |
| 中央目录扫描 + raw copy（上表） | 峰值 2×→~1.1×，W1 内存 36× | 中（改 `archive.rs` / `engine.rs` 主线） |
| `BufReader` 容量 1 MB → 64–128 KB，archive 句柄 LRU | W2 峰值 55 MB → ~10 MB | 低 |
| 深度合并的 JSON 条目设单文件流式上限，超限走「原样覆盖 + 警告」 | 防单文件炸弹 | 低 |
| `zip64`：`large_file(true)`（当前 `large_file(false)`） | 支持 >4 GB 总量 / 单文件 | 低 |
| 写前用元数据预检 `max-bytes`（当前是合并完才检查） | 早失败，不浪费一次全量合并 | 低 |
| `crc32fast`（PCLMUL/SIMD，已是 zip 的传递依赖）替代手写表 CRC | CRC 计算 5–10×（W1 可省 ~0.2 s） | 极低 |
| 全局分配器换 `mimalloc` | 大量小条目分配，预计 5–15% | 低 |
| 源 zip 用 `memmap2` 映射 | 少一次内核拷贝，读路径 +5–10% | 低 |

---

## 4. 问题三：还有哪些地方可以优化？

### 4.1 服务端行为（比合并器更影响体感）

| 项 | 现状 | 优化 | 收益 |
| --- | --- | --- | --- |
| 无变更重建 | 每次 `rebuild()` 全量重算，watch 只按 `mtime:size` 判变化 | 记录来源集合的 `(path, size, mtime, sha1)` 摘要，未变直接跳过；watch 加 300 ms 防抖 | 空转重建从 4 s 降到 0 ms |
| 报告体积 | 182 冲突 × 39 loser 的 SHA-1 → 435 KB stdout | `loserSha1s` 截断（前 5 + 计数）、明细默认前 200 条、`report.sha1` 开关 | 报告 KB 级，JVM 侧 gson 解析开销归零 |
| HTTP 分发 | ETag/304 已有、`FileChannel` 定长流式 | 增加 `Range`（断点续传/弱网）、`Accept-Ranges` | 大包重连更稳 |
| 构建进程 | 每次 spawn（约 30 ms）+ JVM 侧 gson/JSON 构造 | 合并器常驻（stdin/stdout 长连接）或改为本地库调用 | 小包重建省 30–80 ms |
| 并发构建 | `buildLock` 串行 + `pending` 合并请求 | 保持（正确性优先）；可加「构建中再次变更 → 结束后自动再跑一轮」 | 已有 pending 语义，确认即可 |

### 4.2 合并器算法

| 项 | 说明 | 收益 |
| --- | --- | --- |
| 跳过「已压缩、单来源」条目的重压缩 | 见第 3 节 | **最大**（W1 20×+） |
| 按条目类型决定压缩级别（JSON=6、二进制=store/1） | 已压缩数据 deflate 白费 CPU；zlib 会自动判定部分情况，但显式跳过更快 | 中 |
| 冲突比对改用「按名字分片 + rayon」 | 5,000 条目以上时明显 | 小 |
| 深度合并结果缓存（按来源 sha1 + 条目名） | 反复重建时复用 | 小（raw copy 后已不必要） |
| 命名空间/冲突统计一次遍历完成 | 当前 `namespace_warnings` 又扫一遍 `BTreeMap` | 极小 |
| 输出顺序按 BTreeMap（已是确定性） | 保持不变 | — |

### 4.3 不建议做的

- **不要为了并行而并行**：raw copy 之后压缩不再是瓶颈，方案 B 的自写 zip writer 维护成本高、收益低。
- **不要把合并放进服务器主进程**（JNI/内嵌 Rust）：会失去崩溃隔离，且与「插件 jar 内置二进制」的部署模型冲突。
- **不要用 zstd 替代 deflate**：zip 容器与客户端都只认 deflate。

---

## 5. 路线图（按 ROI 排序）

| 优先级 | 动作 | 预计结果 | 工作量 | 风险 |
| --- | --- | --- | --- | --- |
| **P0-1** | 两阶段合并：中央目录 + `raw_copy_file`，仅对需合并的 JSON 解压 | W1 4.07 s → 0.25–0.40 s，RSS 547 → ~20 MB；W2 0.97 → 0.55–0.75 s | 1–2 天 | 中（语义等价性、mcmeta、zip64） |
| **P0-2** | 报告瘦身 + SHA-1 按需 | 435 KB → ~5 KB；W2 再省 ~10% | 半天 | 低 |
| **P1-1** | 未变更跳过重建 + watch 防抖 | 空转 0 ms | 半天 | 低 |
| **P1-2** | `crc32fast` + `BufReader` 调优 + `large_file(true)` | W1 再省 5–10%；支持 >4 GB | 半天 | 低 |
| **P2-1** | 需合并条目并行化（rayon 或方案 A 分片） | 仅在「大量 lang 合并」场景有效 | 1–2 天 | 中 |
| **P2-2** | HTTP Range / 合并器常驻进程 / mimalloc | 体感与边角 | 1 天 | 低 |

做完 P0 后的对外口径可以改成：**「合并 270 MB 资源包约 0.3 s，内存 20 MB 级；重复构建近乎零成本」**——这已经明显优于 RoseResourcepack（单线程重打包 + 手动触发），并在合并吞吐上与 ResourcePackManager 的 4 线程写入器持平或更好（它的并行优势被「不重压缩」直接绕过）。

---

## 6. 原型与复现

```text
.tmp/proto/                    两阶段 raw-copy 原型（Cargo，zip 9.8.2，约 120 行）
.tmp/bench/gen2.py             W1/W2 输入生成
.tmp/bench/measure.ps1         墙钟 + 峰值 WorkingSet64
.tmp/bench/results.json        上一轮基准数据
.tmp/bench/results-optimization.json   本轮原型数据
```

```bash
# 原型（W1：3 个来源，按参数顺序后者优先）
.tmp/proto/target/release/proto-rawcopy.exe \
  merged-proto.zip large-a.zip large-b.zip large-c.zip

# 对照：现有合并器
rust/rps-pack/target/release/rps-pack.exe merge \
  --request .tmp/bench/request-large.json --output merged-rust.zip
```

---

## 7. 局限

1. 原型只覆盖「原样拷贝 + mcmeta」，没有 JSON 深度合并、policy=first 的内容比较、冲突报告 SHA-1、`max-bytes` 守卫、zip64；落地后的真实数字会高于原型（已在 §3.3 给出保守估算区间）。
2. W1/W2 是合成负载：二进制为随机字节（不可压缩，deflate 最坏情况），JSON 为小模型文件（不触发深度合并）。真实包里 `lang/en_us.json`、`sounds.json` 会走合并路径，那部分仍需解压 + 重压缩。
3. 多线程收益来自 4 线程 deflate 微基准（同机 16 线程），实际服务器若是 2–4 核 VPS，并行收益会降到 1.5–2×。
4. 所有数据来自单机暖缓存，跨机器绝对值会有波动；结论的方向（raw copy ≫ 并行压缩）不受影响。

---

*调研与原型：2026-11-27；未改动产品代码，`docs/` 之外无仓库变更。*
