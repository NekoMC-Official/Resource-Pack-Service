# rps-pack 极限性能计划（Rust 自有路线）

> 状态：计划待批（2026-11-27）｜前置：`docs/rps-pack-rawcopy-plan.md`（两阶段 raw copy，S0–S7）
> 原则：**不参考任何竞品实现**。全部设计来自本仓源码、本机实测与 zip 规范语义；凡是没有实测支撑的数字一律标注「估算」。

---

## 0. 结论先行

raw copy 解决的是「不要重复压缩」，它把 W1 从 4.07 s 拉到 0.13–0.19 s。再往上，**Rust 已经不是瓶颈**：CPU 侧只剩哈希与元数据，实测 SHA-1 2.7 GB/s、CRC-32 44 GB/s；剩下的时间在**进程启动、I/O 路径、每条目固定开销、以及“本来不必做的工作”**。

本计划给出四个新战场和一条压缩率支线：

| 场景 | raw copy 计划后（预估） | 本计划目标 | 主要手段 |
| --- | --- | --- | --- |
| 微包（1 条 / 1 KB） | 16.6 ms 墙钟（启动主导） | **≤14 ms**（常驻模式 ≤3 ms） | 静态 CRT、裁剪启动路径、warm daemon |
| 小包（200 条 / 27 KB） | 21.4 ms 墙钟 | **≤18 ms**（常驻 ≤6 ms） | 直读、无并行、单次预分配 |
| W1 超大（270 MB / 469 条） | ~0.25 s / 15–30 MB | **0.16–0.20 s / ≤25 MB** | mmap + 顺序标志 + 预分配 + 写时哈希 |
| W2 超多（84 MB / 5,063 条） | ~0.47 s / 55 MB | **0.30–0.40 s / ≤40 MB** | 自写顺序 writer、紧凑元数据、并行扫描 |
| 巨量（200 源 / 40 万条） | 未测，zip crate 固定开销约 8 s（估算） | **≤1.5 s / ≤150 MB** | 自写 writer（每条目 ~1.5 µs）+ 索引缓存 |
| 单条巨型（1 GB） | 受 64 MB 条目上限阻断 | **≤1.2 s，支持 zip64** | 流式哈希、非临时写、取消解压上限 |

一句话：**先不做事，再少拷贝，最后才谈并行**。

> v4 增补：§17 多核、§18 jar 体积、§19 包体积与压缩率、§20 海量/瞬时推送、§21 内存管理。全部带验收门槛。

---

## 1. 本机实测基线（2026-11-27，Ryzen 7 9700X / Windows 11 / Zulu 25 / Rust 1.100 release）

### 1.1 CPU 原语

| 原语 | 实现 | 实测吞吐 | 270 MB 耗时 |
| --- | --- | --- | --- |
| SHA-1 | `sha1` 0.11（自动走 SHA-NI，`compress.rs` 的 `target_feature = "sha"` 分支） | **2,660 / 2,735 / 2,748 MB/s** | **≈0.10 s** |
| CRC-32 | `crc32fast` 1.5.6（PCLMUL） | **45,964 / 44,353 / 43,575 MB/s** | ≈0.006 s |
| deflate（重压） | flate2 + zlib-rs level 6 | W1 全量重压约 3.4 s（旧总量 4.07 s 的 84%） | 仅合并条目还需要 |

结论：**哈希与校验已经贴着内存带宽，没有“再优化算法”的空间**；唯一能做的是“少算”和“别让它挡住 I/O”。

### 1.2 进程与固定开销

| 测量 | 墙钟 | 自报内部耗时 |
| --- | --- | --- |
| proto：1 条 / 1 KB | 15.5–17.1 ms（中位 16.6） | 0–1 ms |
| proto：200 条 / 27 KB | 20.8–22.4 ms（中位 21.4） | 4–5 ms |
| 正式二进制：错误路径（参数非法，立刻退出） | 16.1–17.6 ms（中位 16.6） | — |

**Windows 上进程创建 + 装载 ≈16 ms**，比任何算法都贵。200 条小包的工作时间 4–5 ms，折合 **每条目 ~22 µs**——这是 zip crate 的固定开销，不是 I/O。

### 1.3 raw copy 参考（原型）

| 负载 | 墙钟 | 峰值 RSS | 产物 |
| --- | --- | --- | --- |
| W1 270 MB / 469 条 | 132–191 ms | 14–15 MB | 270,173,078 B |
| W2 84.7 MB / 5,063 条 | 468–480 ms | 55 MB | 44,053,660 B |

（W2 的 55 MB 主要来自 40 个 1 MB BufReader；降到 128 KB 即可。）

---

## 2. 性能模型：钱花在哪

~~~
总耗时 = 进程启动(16 ms)
       + 扫描(Σ 源文件中央目录)
       + 决策(条目数 × 元数据比较)
       + 写出(Σ 压缩字节 / 写带宽 + 条目数 × 每条目固定开销)
       + 哈希(输出字节 / 2.7 GB/s，可与写重叠)
       + 压缩(仅合并条目)
~~~

- **超大包**：写带宽与哈希带宽决定上限。270 MB / 1.8 GB/s ≈ 0.15 s，哈希 0.10 s，重叠后 ≈0.15–0.20 s。再快只能靠平台 I/O（预分配、顺序标志、非临时写）或减少拷贝次数。
- **超多包**：条目数 × 固定开销决定上限。22 µs/条 × 5,063 ≈ 0.11 s，但 W2 实测 0.47 s 说明扫描/读取也占一半；40 万条时固定开销会放大到 8 s 量级（估算），必须把每条目成本压到 ~1.5 µs。
- **小包**：16 ms 启动 > 全部工作。只有两条路：让进程更便宜（静态 CRT、裁剪），或者**根本不要每次启动**（常驻合并进程）。
- **压缩率**：raw copy 已把不可压缩资产排除在外；剩下能优化的只有合并 JSON 的 level 与极小块的选择。

---

## 3. 五个战场与总体架构

### 3.1 三档自适应

| 档位 | 判定 | 策略 |
| --- | --- | --- |
| Tiny | 源总大小 ≤ 4 MB 且条目 ≤ 512 | 直接 `fs::read`（不 mmap）、单线程、无 rayon、预分配输出、跳过索引缓存 |
| Huge | 任一条目 > 8 MB 或总量 > 64 MB | mmap + 流式 raw copy + 分块哈希 + 非临时写（>32 MB 段） |
| Many | 条目 > 2,000 或源 > 8 | 并行扫描（`std::thread::scope`）、紧凑元数据、自写 writer、索引缓存 |

档位在 `engine.rs` 入口处一次判定，后续模块只读 `Mode` 枚举；**不做运行中切换**，避免两套行为漂移。

### 3.2 模块图（在 raw copy 计划之上）

~~~
engine.rs        判定档位 + 编排
  ├─ scan.rs     中央目录扫描（可并行）；mmap / 直读两种源
  ├─ resolve.rs  去重/策略/合并链（单线程，顺序敏感）
  ├─ write/
  │    ├─ mod.rs     顺序 writer 接口（自有实现，替换 zip crate 写出）
  │    ├─ zip64.rs   中央目录 / EOCD / zip64 extra
  │    ├─ deflate.rs 仅合并条目的 deflate（flate2 zlib-rs）
  │    └─ io_win.rs / io_linux.rs / io_mac.rs   平台 I/O
  ├─ hash.rs     流式 SHA-1（写时哈希）
  ├─ cache.rs    索引缓存 + 构建指纹
  └─ daemon.rs   常驻模式（--serve，P6）
~~~

### 3.3 为什么自写 writer（关键决策）

zip crate 9.8.2 的写出路径每条目都有 HashMap 插入、头部 Vec 分配与 UTF-8 校验；实测 22 µs/条。自写顺序 writer：

- 条目元数据存一个 `Vec<CdEntry>`（增长式，无 per-entry 分配）；
- 头部直接写进 1 MB 缓冲，零临时 Vec；
- raw copy 时压缩字节直接从 mmap 切片写出（Windows 上再叠加平台路径）；
- 天然支持“先压缩到内存缓冲再按顺序写”，从而可以**有序并行**（完成顺序无关）；
- zip64 由 `zip64.rs` 统一处理（>65,535 条目 / >4 GB 必须）。

代价：约 500 行 + 20 个测试；但它是超多包与平台 I/O 的前提，也让我们彻底摆脱第三方 writer 的顺序限制（zip crate 的 `par_append_data` 按完成顺序提交，会破坏名称升序与确定性）。

---

## 4. 超大包（Huge）

1. **流式 raw copy**：源条目压缩字节不经过 `Vec`；`ZipFile` 的 `Read` 直接喂给 writer 的缓冲/平台拷贝。
2. **取消 64 MB 解压上限**：raw copy 不需要解压；仅在需要 SHA-1 去重时流式读取，故上限改为「压缩字节 ≤ 系统可寻址」并由 zip64 兜底。`MAX_ENTRY_BYTES` 只约束需要解码的合并条目（保留 64 MB）。
3. **分块哈希**：64 KB 块 + SHA-NI；与写同时进行（同一块数据先写后哈希，CPU 与 I/O 重叠）。
4. **mmap 源**：`memmap2`（feature `mmap`）；Tiny 档回退 `fs::read`。Windows 上 mmap 避免 read 的系统调用与拷贝。
5. **非临时写**：>32 MB 的连续段用 `_mm_stream_si128` 写缓冲（x86_64），避免污染 L2/L3；小段仍走普通拷贝。仅在 Huge 档启用。
6. **预分配**：输出按「已知压缩字节总和 + 头部估算」预分配（Windows `SetFileInformationByHandle` / Linux `fallocate` / macOS `F_PREALLOCATE`），减少碎片与元数据更新。
7. **zip64**：单条 >4 GB 或条目 >65,535 自动切换；与 Minecraft 客户端兼容（Java ZipFile 支持 zip64）。
8. **峰值内存**：`O(N 条目元数据 + 一个 1 MB 写缓冲 + 64 KB 哈希块)`，与包大小无关。

---

## 5. 超多包（Many）

1. **并行扫描**：`std::thread::scope`，按源分片（每线程一组源），结果写回预分配的 `Vec<SourcePack>`；顺序由源序号决定，天然确定。线程数 = min(核数, 源数)，默认 4，环境变量 `RPS_THREADS` 可覆盖。
2. **紧凑元数据**：
   - 条目名只存一次（合并后唯一的 key），来源侧只存 `name_id: u32 + index: u32 + crc: u32 + size: u64`；
   - 名称字符串放 `String` arena（单块 `Vec<u8>` + 偏移），避免 40 万个独立 `String` 分配；
   - 合并阶段用 `hashbrown::HashMap` 或 `std::collections::HashMap` + 自定义 hasher（@rustc-hash@），最后按名称排序一次（`sort_unstable`）。
3. **索引缓存**：每个源的「条目元数据 + 大小 + mtime + 路径」序列化为紧凑二进制（@.rps-index@，放在输出目录），下次构建直接读缓存（40 源 × 2,000 条 ≈ 4 MB，读取 <10 ms），失效条件为 `size+mtime` 变化。
4. **构建指纹短路**：所有源 + 配置 + 版本计算指纹（SHA-256 前 16 字节），与上次相同且 `merged.zip` 存在 → 直接返回缓存报告（`millis ≈ 3`），不打开任何 zip。
5. **去重在大规模下的成本**：CRC+size 预筛仍是 O(1) 哈希查找；只有 CRC/size 命中才流式计算 SHA-1，且结果缓存（`HashMap<(u32,u32), String>`）。
6. **排序成本**：40 万名称的 `sort_unstable` ≈ 25–40 ms（估算），可接受；若成为瓶颈，改 radix sort（P6 可选）。
7. **内存预算**：40 万条 × 48 B 元数据 ≈ 19 MB + 名称 arena ≈ 24 MB + 索引缓存 4 MB → 峰值 ≤150 MB。

---

## 6. 小包与小条目（Tiny）

1. **启动预算**：Windows 进程创建 ≈16 ms。可做：
   - `-C target-feature=+crt-static`（省掉 CRT DLL 装载，估 1–2 ms）；
   - `panic = "abort"`、`strip = true`、`opt-level = 3`、`lto = "fat"`（已具备 LTO）；
   - 去掉启动时的 rayon/criterion 类初始化（本来就没有），确保首条指令前无堆分配。
2. **Tiny 档直读**：`fs::read` 比 mmap 快（小文件 mmap 有两次系统调用 + 缺页）；≤4 MB 一律直读。
3. **预分配输出**：按「源压缩字节和 × 1.02 + 条目 × 60 B」一次 `set_len`，写指针顺序推进，避免扩容。
4. **每条目成本目标**：自写 writer 把 22 µs 压到 **≤1.5 µs**（200 条 → 0.3 ms）。
5. **小包直接产出**：Tiny 档不做索引缓存、不做并行、不做平台拷贝，路径最短。
6. **常驻模式（可选，P6）**：`rps-pack --serve` 通过 stdin/stdout 长度前缀协议接收请求，进程常驻；插件在首次构建时拉起，空闲 5 分钟或父进程退出即终止。小包端到端从 ~16 ms 降到 **~1–3 ms**。协议与安全边界见 §10。

---

## 7. 压缩率支线（速度之外）

| 条目类型 | 策略 | 理由 |
| --- | --- | --- |
| 未合并（passthrough） | 保留源压缩字节 | 已是源作者的最优解，重压只会变差或变慢 |
| 合并 JSON（lang/sounds/font/atlases） | level 9（zlib-rs） | 合并条目通常 <1 MB，level 9 比 6 多 2–4% 压缩率，CPU 可忽略 |
| 极小 JSON（<512 B） | `store`（不压缩） | 压缩后反而更大（deflate 头 + 熵编码开销） |
| 极小文本（512 B–8 KB） | 可选 zopfli（总预算 200 ms） | 压缩率 +3–6%；超预算自动回退 level 9 |
| 已压缩资产 | 不重压（raw copy） | PNG/OGG/JPEG 再压只增 CPU |

度量：固定语料（真实整合包样本 + 合成 W1/W2）比较「输出字节 / 构建耗时 / 解压后字节」三元组，任何策略必须在 **体积收益 ≥1% 或耗时收益 ≥10%** 时才保留；否则删除代码（避免配置膨胀）。

---

## 8. 平台专有特性

> 全部通过 `#[cfg(target_os = ...)]` 隔离，未实现的平台走通用路径；不引入平台专属依赖到公共代码。

### 8.1 Windows

| 手段 | API | 预期 | 风险 |
| --- | --- | --- | --- |
| 顺序读提示 | `FILE_FLAG_SEQUENTIAL_SCAN`（`OpenOptionsExt::custom_flags`） | 大文件读 +5–10%（估算） | 低 |
| 输出预分配 | `SetFileInformationByHandle(FileAllocationInfo)` | 减少碎片，写 +3–8%（估算） | 需 windows-sys 依赖或裸 FFI |
| 大段非临时写 | `_mm_stream_si128` | 270 MB 写 +5–12%（估算） | 需 16 B 对齐；仅 Huge 档 |
| mmap 源 | `memmap2`（底层 CreateFileMapping） | 省一次拷贝 | 文件被替换时的映射语义 |
| 常驻进程 | 命名管道或 stdio | 小包 16 ms → 1–3 ms | 生命周期管理（P6） |

### 8.2 Linux

| 手段 | API | 预期 | 风险 |
| --- | --- | --- | --- |
| 内核区间拷贝 | `copy_file_range`（源 fd → 输出 fd） | raw copy 段 +20–40%（估算） | 需自写 writer 才能绕过 zip crate；跨文件系统回退 |
| 顺序预读 | `posix_fadvise(SEQUENTIAL)` | +5% | 低 |
| 预分配 | `fallocate` | +3–8% | 低 |
| 异步 I/O | io_uring（可选，P6 研究） | 大量小读 +10–20%（估算） | 复杂度高、内核版本要求 |

### 8.3 macOS

| 手段 | API | 预期 | 风险 |
| --- | --- | --- | --- |
| 区间拷贝 | `fcopyfile` | 同 copy_file_range | 低 |
| 预分配 | `F_PREALLOCATE` | 写 +3–8% | 低 |

> CI 只在 Linux 跑；Windows/macOS 的平台路径用单元测试 + 本机基准验证，未验证的路径默认关闭（feature gate 默认关），避免“纸面优化”。

---

## 9. 并行与确定性

- **并行只用于**：源扫描（按源分片）、可合并 JSON 的解析校验（按条目）、以及可选的有序压缩（按条目，结果写回索引位）。
- **不并行**：去重决策、中央目录写出、哈希（单流）。
- **有序提交**：并行任务产出 `(slot_index, bytes)`，writer 按 slot 顺序消费；缓冲上限 = 线程数 × 单条上限（默认 8 × 64 MB 上限，实际按待合并条目大小动态收紧）。
- **确定性**：并行不改变字节（每条目压缩参数固定），顺序由 `BTreeMap`/排序保证；`same_input_twice_produces_same_sha1` 必须继续通过。
- **线程数**：默认 `min(4, available_parallelism)`；`RPS_THREADS=1` 可强制串行（排障用）。

---

## 10. 常驻合并进程（P6，可选但收益最大）

- 协议：`--serve`，stdin/stdout 上 `[u32 len][JSON request]`，响应 `[u32 len][JSON report]`；单客户端、单请求串行。
- 生命周期：父进程 stdin EOF 即退出；空闲 300 s 退出；启动时输出协议版本，插件不匹配则回退到一次性进程。
- 安全：只接受工作目录内的路径（与一次性模式相同校验）；不监听网络端口。
- 收益：小包 16 ms → 1–3 ms；W2 省 16 ms（占比小）。
- 风险：僵尸进程、管道死锁、版本错配。缓解：写超时 + 看门狗线程 + 首请求带版本；插件侧 `ProcessHandle.onExit` 清理。
- 决策门：若小包场景在真实服务器上占比 <30%，不做。

---

## 11. 增量与缓存

| 机制 | 触发 | 收益 | 失效条件 |
| --- | --- | --- | --- |
| 构建指纹短路 | 源 + 配置 + 版本未变 | 全量构建 → ~3 ms | 任一源 size/mtime 变 |
| 源索引缓存 | 源文件未变 | 扫描 → 直接读 4 MB 索引 | size/mtime 变、格式版本变 |
| 输出 sha1 复用 | 指纹未变 | 省 0.1 s | 同上 |
| watch 防抖 | 300 ms 窗口内多次 mtime | 避免连续重建 | — |

指纹 = SHA-256(排序后的 `(路径, size, mtime_ns)` + 策略 + 格式 + 引擎版本)，存于 `merged/fingerprint`；不依赖文件内容哈希（避免为了判断“变没变”而先读一遍）。

---

## 12. 分片计划（已被 §22 统一步骤取代，保留作历史）

> 前置：raw copy 计划 S0–S7 已完成（scan/resolve/write 骨架 + 报告）。以下每片独立验收，**任一片收益低于阈值即停止并记录**。

### P1 自写顺序 writer（2.5 人日，最高优先）

- 范围：`write/mod.rs` + `zip64.rs` + `deflate.rs`；替换 zip crate 的写出（读仍用 zip crate 的 `by_index_raw`）。
- 验收：现有 20 个集成测试全绿；`single_source_passthrough` 名称升序/时间戳/权限不变；70,000 条小条目包可被 `jar tf` 与 Java `ZipFile` 正常读取；每条目写出成本 ≤2 µs（微基准）。
- 回滚：writer 走 trait 注入，`--legacy-writer` 环境变量回退。

### P2 三档自适应 + Tiny 直读 + 预分配（1 人日）

- 验收：micro ≤4 ms 内部、small200 ≤8 ms 内部；W1 不退化（≤0.25 s）。
- 回滚：`Mode` 固定为通用档。

### P3 超大包（mmap / 流式 / zip64 / 非临时写）（1.5 人日）

- 验收：1 GB 单条包 ≤1.2 s、峰值 ≤40 MB；4.5 GB 包可生成并被 Java 读取（跳过耗时的全量校验，抽样验证）。
- 回滚：feature `huge-io` 关闭即回退到 BufReader。

### P4 超多包（并行扫描 / 紧凑元数据 / 索引缓存 / 指纹）（2 人日）

- 验收：40 万条合成负载 ≤1.5 s、≤150 MB；二次构建（无变化）≤10 ms；W2 ≤0.40 s。
- 回滚：`RPS_THREADS=1` + 关闭索引缓存。

### P5 压缩率支线（0.5 人日）

- 验收：真实样本 + 合成语料上，level 9 / store 阈值 / zopfli 三项各自达到「体积 ≥1% 或耗时 ≥10%」门槛才保留；否则删除该分支。
- 回滚：单常量回退 level 6。

### P6 平台 I/O + 常驻模式（2 人日，可选）

- 平台：Windows 顺序标志/预分配/非临时写；Linux copy_file_range/fadvise/fallocate；macOS fcopyfile。
- 常驻：`--serve` + 插件侧生命周期。
- 验收：各平台基准提升 ≥5% 才保留；常驻模式小包端到端 ≤3 ms，且 24 h 无僵尸进程。
- 回滚：feature 默认关。

---

## 13. 测试与基准矩阵

| 维度 | 用例 | 判定 |
| --- | --- | --- |
| 功能 | 现有 20 个 Rust 集成测试 + 12 个新增 | 全绿 |
| 确定性 | `same_input_twice_produces_same_sha1`、`parallel_equals_serial` | 字节一致 |
| 规模 | micro / small200 / W1 / W2 / 70k 条 / 1 GB 单条 / 40 万条 | 达标即通过 |
| 兼容 | Java `ZipFile` + 26.3 客户端实际加载 | 成功 |
| 平台 | Windows 本机；Linux CI（仅通用路径）；macOS 手动 | 平台特性默认关 |
| 回归 | JVM 93 + paper 7 + tools 27 | 全绿 |
| 微基准 | 每条目写出成本、扫描吞吐、哈希吞吐 | 记录到 docs |

所有基准跑 5 次取中位，脚本与结果放 `.tmp/bench`（gitignore），结论回写文档。

---

## 14. 风险与备选

| # | 风险 | 缓解 | 备选 |
| --- | --- | --- | --- |
| R1 | 自写 writer 引入 zip 兼容 bug | 20 个测试 + Java 读取 + 26.3 客户端实机 | 保留 zip crate 回退开关 |
| R2 | zip64 边界（65,535 条 / 4 GB） | 专项测试 + 70k 条包 | 条目上限 60k 并在报告里提示 |
| R3 | 平台 API 不可用/权限 | feature 默认关 + 运行时探测 | 通用路径 |
| R4 | 非临时写破坏小段性能 | 只对 >32 MB 段启用 | 关闭 |
| R5 | 常驻进程僵死 | 看门狗 + EOF 退出 + 空闲超时 | 不做常驻 |
| R6 | 索引缓存与源不同步 | size+mtime_ns 指纹 + 格式版本号 | 关闭缓存 |
| R7 | 并行扫描内存放大 | 每源元数据上限 + 分片消费 | 串行扫描 |
| R8 | 优化后 sha 变化 | 一次性 ETag 变更 | 无 |

---

## 15. 交付与提交

- 每片一个 commit，均在 `feat/fabric-server-mod`（或后续 `perf/rps-pack-maxperf`）上，不 push 除非用户明确要求。
- 文档：本计划 + `docs/perf-benchmark.md` 追加「实施后」列 + `docs/design.md` 写出路径说明。
- 任何未达门槛的优化：删除代码，在本文档记录「试过、无收益」。

---

## 16. 执行清单

- [ ] P1 自写 writer + zip64 + 测试
- [ ] P2 三档 + Tiny 直读 + 预分配
- [ ] P3 超大包（mmap/流式/zip64/非临时写）
- [ ] P4 超多包（并行扫描/紧凑元数据/索引缓存/指纹）
- [ ] P5 压缩率支线（按门槛取舍）
- [ ] P6 平台 I/O + 常驻模式（可选）
- [ ] 更新基准文档并汇报

---

## 17. 多核调度（v2 增补）

先给结论：**多核只对「扫描 + 解析/合并 + 压缩」这三段有效，对写 zip 与 SHA-1 无效。** 本插件的负载里，W1 型（几百 MB、条目少）几乎没有并行收益，W2 型（几十个源、几千条目）收益 10–20%，40 万条型与 lang 密集型才是 2–3.4×。

### 17.1 先算 Amdahl（用实测与 §2 模型）

| 负载 | 主要阶段占比 | 可并行比例 | 4 线程理论上限 |
| --- | --- | --- | --- |
| W1 270 MB / 469 条 | 写 0.15 s + 哈希 0.10 s（重叠），扫描/决策 <0.02 s | <10% | **1.0–1.05×** |
| W2 40 源 / 5,063 条 | 扫描 0.05、决策 0.15、写 0.20、哈希 0.02 | ~30% | **1.10–1.15×** |
| 40 万条 | 扫描约 1.2 s（总 1.5 s） | ~80% | **2.0–2.5×** |
| lang 密集（100 MB JSON） | 解析 + 合并 + deflate 约 1.0 s | ~85% | **3.0–3.4×** |

反推：把并行塞进 W1 只会多付线程启动与调度成本，因此 **Tiny/Huge 档默认单线程**，Many 档或检测到可合并 JSON 体积 >8 MB 才起线程池。

### 17.2 线程模型（不引入 rayon）

- 用 std::thread::scope，线程数 auto = clamp(可用核数 / 2, 1, 4)（16 核 → 4）；环境变量 RPS_THREADS 可覆盖，=1 即完全串行。
- 留一半核给 JVM：Paper/Fabric 主线程跑 tick、GC 线程并发，合并器是子进程，抢满核会让服务器掉 TPS；默认 4 是折中，不是上限。
- 线程只创建一次，作用域结束即回收；Tiny 档不创建任何线程（避免 ~0.2 ms/线程的创建成本与额外 RSS）。
- 两种分片：源级静态分片（每个源一个任务，天然均衡）+ 条目级动态领取（AtomicUsize 游标，每次领 64 条，解决长尾）；不按字节切，因为 zip 条目必须整体处理。
- 有序提交环：环形缓冲容量 = 线程数 × 2，工作线程把结果放进自己的 slot 并标记完成，写线程严格按 slot 顺序消费；缓冲满即阻塞（背压），因此内存上界 = 环容量 × 单条最大字节，与线程数线性、与源数量无关。
- 工作线程 panic 用 catch_unwind 转成错误码回传，不让整个合并器 abort。
- 不做 CPU 亲和与 NUMA 绑定：Windows 上收益不稳定，Linux 服务器上由调度器处理更好，留作 P6 之后的实验项。

### 17.3 并行边界表

| 阶段 | 并行 | 粒度 | 上限 | 确定性 |
| --- | --- | --- | --- | --- |
| 读源中央目录（扫描） | 是 | 每源 | 8 | 结果按 (priority, index) 重排，与串行同序 |
| 冲突决策 / 去重 | 是 | 每 64 条 | 线程数 | 归并到单一有序表后决策，顺序固定 |
| JSON 解析与合并 | 是 | 每文件 | 线程数 | 合并结果与输入顺序绑定，按序提交 |
| 条目压缩（仅需重压的） | 是 | 每 64 条 | 线程数 | 按 slot 顺序写入 |
| 写 zip（中央目录 + 本地头） | **否** | — | 1 | 顺序流，名称升序断言依赖它 |
| 全包 SHA-1 | **否** | — | 1 | 单流不可分；已与写重叠（§4） |

### 17.4 扩展性与验收门槛

测量方法：同一负载跑 1 / 2 / 4 / 8 线程（RPS_THREADS），每档预热 1 次取 3 次中位，记录墙钟与峰值 RSS。

| 指标 | 门槛（4 线程） | 不达标怎么办 |
| --- | --- | --- |
| 扫描阶段加速比 | ≥2.8× | 先查是否被 mmap 缺页或单源主导拖累，再考虑把大源再切块 |
| 合并 + 压缩阶段 | ≥3.0× | 检查 slot 争用与锁粒度，必要时改无锁队列 |
| W2 端到端 | ≥1.10× | 说明并行段太小，回退单线程（别为 5% 付复杂度） |
| 40 万条端到端 | ≥2.0× | 说明写阶段已成瓶颈，转 P4 的索引缓存 |
| W1 端到端 | 退化 ≤3% | 立即在 Huge 档禁用线程池 |

### 17.5 明确不做

- 并行写 zip：中央目录与本地头必须顺序产出，强行并行会破坏字节级确定性（same_input_twice_produces_same_sha1 会红）。
- 并行 SHA-1：单流哈希不可分；分块哈希再合并不是 SHA-1。
- 多进程分片再合并：Windows 进程启动 16 ms × N，小包直接亏，大包也要额外落盘。
- rayon / crossbeam：rayon 约 +400 KB jar（见 §18），crossbeam 的 channel 在 4 线程、64 条粒度下不比环形缓冲快。

## 18. jar 体积预算（v2 增补）

### 18.1 基线（本机实测，2026-11-27）

| 产物 | 字节 | 构成 |
| --- | --- | --- |
| paper/build/libs/resource-pack-service-0.1.0.jar | 2,510,148 | 插件类 + core + kotlin-stdlib + 内嵌 rps-pack.exe |
| fabric/build/libs/resource-pack-service-fabric-0.1.0.jar | 2,659,120 | 上述 + snakeyaml，JiJ 三个嵌套 jar |
| rust/rps-pack/target/release/rps-pack.exe | 785,920 | LTO + strip，已开 |

### 18.2 预算与守门

- 硬上限：paper ≤ 2,750,000 B（+240 KB，9.6%）、fabric ≤ 2,900,000 B（+241 KB，9.1%）。超过即视为引入不该有的依赖。
- CI 增加一步 Assert jar size：读 build/libs 下两个 jar 的字节数，超限 exit 1；与现有 Assert bundled merger 并列。
- 每完成一个切片，把两个 jar 的体积记进 docs/perf-benchmark.md 的表格；单次改动增长 >50 KB 必须在 commit message 说明理由。
- 体积按「压缩后」算（jar 本身是 zip），所以代码行数增长的影响远小于依赖增长：新增 2,000 行 Rust 约 +30–60 KB，新增一个大依赖可能 +400 KB。

### 18.3 取舍表

| 候选 | 体积代价 | 决策 |
| --- | --- | --- |
| rayon | 约 +400 KB | **不用**，用 std::thread::scope |
| windows-sys / winapi | 约 +50–150 KB | **不用**，平台调用写裸 FFI（extern "system"）或 cfg 直接跳过 |
| memmap2 | 约 +25 KB | 用，仅 Huge 档需要；若最终没用到则连依赖一起删 |
| hashbrown | 约 +80 KB | 不用，std HashMap + 现有 hasher 足够 |
| zopfli | 约 +120 KB | **默认不编入**（feature off），只做实验构建 |
| zlib-ng | C 源码数百 KB + 构建链 | 不引入，继续 zlib-rs |
| simd-json | 约 +200 KB 且 unsafe | 不引入，serde_json 在 lang 合并上已够 |
| 静态 CRT（crt-static） | 约 +100–200 KB | 需实测；只有启动增益 ≥1 ms 且体积增量 ≤150 KB 才保留 |

### 18.4 已有裁剪（保持）

strip、lto = "fat"、codegen-units = 1 已开；panic = "abort" 在 release profile 打开（测试用 dev profile，catch_unwind 不受影响，但工作线程的 catch_unwind 需要 release 下仍能捕获 —— 若不行则退回 unwind，用体积换正确性）；不新增第四方 JiJ 依赖，core / kotlin-stdlib / snakeyaml 已是白名单。

### 18.5 与多核的联动

多核方案若改用 rayon，直接吃掉 §18.2 一半预算，所以 §17 选 std::thread 不只是风格问题，也是体积决策；同理，平台优化优先裸 FFI 而不是 windows-sys，既省体积也免去离线 registry 缺包的风险（见 §1 约束）。

## 19. 统一包体积与压缩率（v3 增补）

### 19.1 现状与实测基线

| 负载 | 来源合计（zip 字节） | 当前 merged.zip | 比例 | 说明 |
| --- | --- | --- | --- | --- |
| W1 270 MB / 469 条 | 270,173,442 | 270,174,433 | **100.0004%** | 随机字节不可压，deflate 只加头开销 |
| W1 raw-copy 原型 | 270,173,442 | 270,173,078 | 99.9999% | 原样搬运，连重压都省了 |
| W2 40 源 / 5,063 条 | 86,484,750 | 44,065,368 | **51.0%** | 182 处冲突只留胜者，去重与覆盖贡献一半 |

当前实现是「全部解压 → 全部按 level 6 重压」，所以：源包如果是 level 9 压过的，重压会**变大**；源包如果是 stored（level 0）的，重压会变小。两种都应该避免——正确做法是逐条目决定，而不是全局一刀切。

### 19.2 尺寸模型

merged.zip ≈ Σ(胜者条目的压缩字节) + 本地头/中央目录（每条约 100–150 B + 名称长度）。三个杠杆按收益排序：

1. **胜者选择与去重**（最大，已实现）：冲突只留一个、相同 CRC+size+sha1 只留一份；W2 已经靠它砍掉 49%。
2. **每条目方法选择**（本次新增）：压得好的原样搬，压得差的重新压，不可压的改 store。
3. **文本再压**（小头）：lang/JSON 用 level 9 或 zopfli 预算，几 MB 级别才值得。

### 19.3 逐条目策略（auto）

扫描阶段从中央目录就能拿到 method / crc / size（原始）/ compressed_size / name，**不需要解压**即可判定：

| 条件 | 动作 | 理由 |
| --- | --- | --- |
| method = Deflated 且 compressed/raw ≤ 0.92 | **passthrough**（原字节搬运） | 源已压好，重压只会更大或持平，还白烧 CPU |
| method = Stored 且 raw ≥ 512 B | 按 level 6 重压；结果 ≥ raw 则改 store | stored 通常意味着源没压，值得压 |
| Deflated 但 compressed/raw > 0.92（不可压数据） | passthrough 或 store（取小） | 再压是负收益 |
| 文本类（.json/.mcmeta/.txt/.lang/.properties）且 raw > 4 KB | level 9；总预算 200 ms 内可选 zopfli | 文本是唯一还有 5–15% 空间的地方 |
| 媒体类（.png/.jpg/.ogg/.webp 等） | passthrough | 已压缩格式，deflate 纯浪费 |
| 合并生成的 JSON（原本就要重写） | 直接 level 9 写出 | 没有源字节可搬 |

配置：merge.recompress = auto | never | always（默认 auto；never = 全 passthrough，always = 旧行为）。报告新增字段 rawBytes / ratio / recompressed，JVM 侧在 /rps status 显示「压缩率 51.0%（重压 37 条）」。

### 19.4 目标与验收

- **不劣于两者较优值**：merged ≤ min(全 passthrough, 全 level 6) × 1.01。这是硬门槛，任何策略只要越线就回退。
- W1：≤ 来源合计 + 0.5%（不可压数据不允许被 deflate 撑大）。
- W2 与真实整合包：≤ 全 level 6 基线 × 1.00（passthrough 只会更小）。
- 文本密集型（100 MB lang）：比全 level 6 再小 3–10%，耗时增量 ≤200 ms。
- 报告里每个条目记录「passthrough / recompressed / stored」计数，便于回归对比。

### 19.5 压缩率对推送的乘数效应

1000 名玩家 × 270 MB = **270 GB** 出口流量；体积每降 10%，同样的推送就少 27 GB。所以 §19 和 §20 是同一件事的两端：先让包小，再让推得稳。

## 20. 超多玩家统一推送与瞬时推送（v3 增补）

### 20.1 先分清三件事

| 层 | 成本 | 能否「瞬时」 |
| --- | --- | --- |
| 下发 push 包（每玩家一个 ClientboundResourcePackPushPacket） | 几十字节，主线程 <0.1 ms/千人 | **能**，一个 tick 内发完 |
| 客户端下载（HTTP 传 merged.zip） | 1000 × 包体积；1 Gbps 约 36 min / 10 Gbps 约 3.6 min | 不能，受出口带宽物理限制 |
| 客户端解压与应用 | 每客户端本地 CPU/IO | 不能，但可并行、与下载重叠 |

所以「瞬时推送」的正确定义是：**推送决策与发包在 1 秒内完成，下载按出口带宽跑满、不雪崩、不重试风暴**。目标不是让 270 GB 瞬间传完。

### 20.2 发包侧（dispatch）

- 一次收集 onlinePlayers，先过滤：**已 LOADED 且 hash 相同的跳过**（DeliveryStatusStore 里已有 hash），避免无意义重推——这是最大的一笔省流。
- 过滤后按 tick 分批（每 tick ≤200 人），避免主线程一次性构造 1000 个任务造成 tick 抖动；Fabric 侧合成单个 server task 遍历，不逐人 runOnServer。
- 进服推送加 0–1500 ms 随机抖动（join-delay 之外），防止 200 人同时进服把出口打满。
- push 只发一次；同一玩家同一 sha1 在「已 PENDING/LOADED」状态时不重复发。

### 20.3 传输侧（FanoutController，放 core，平台无关）

- **并发窗口** max-concurrent（默认 64）：同时在下载的玩家数上限；其余排队，FIFO，新进服优先于重建重推。
- **出口限速** egress-bps（默认 0 = 不限）：在 HTTP 处理里用令牌桶包一层输出流，按字节放行；与并发窗口配合，保证「慢客户端不拖垮快客户端」。
- **渐进升窗**：从 min(8, N) 开始，每 5 s +25%，同时满足失败率 <1% 且连接建立 p95 <50 ms 才继续；失败率超 2% 立刻降半。
- **失败处理**：FAILED_DOWNLOAD 5 s 后重试一次（hash 未变才重试），仍失败标记并停止；DECLINED 不重试（尊重玩家选择）；INVALID_URL 不重试，直接告警。
- **HTTP 安全网**：最大并发流 = N + 16，超出返回 503 并记日志；keep-alive 打开；继续用同一 FileChannel + transferTo（零拷贝）。
- 不做按玩家下发不同包：那会破坏「统一资源包」的语义，也让客户端缓存失效。

### 20.4 配置

delivery.fanout.max-concurrent（默认 64）、delivery.fanout.egress-bps（默认 0）、delivery.fanout.ramp（默认 true）、delivery.fanout.retry（默认 1）、delivery.fanout.jitter-ms（默认 1500）、delivery.http.max-connections（默认 256）。全部可在 reload 时热更，重建不打断进行中的下载（HTTP 句柄持有旧文件）。

### 20.5 可观测性

/rps status 增加一行：排队 / 下载中 / 已完成 / 失败 / 已服务字节 / 当前出口速率 / 压缩率；fanout 进行中每 10 s 打一条日志；结束打一条汇总（耗时、成功数、失败数、平均速率）。失败玩家列进 /rps rejected（已有）。

### 20.6 验收

| 项 | 门槛 |
| --- | --- |
| 1000 个假玩家的推送决策 + 发包 | <200 ms，0 次重复 push |
| 200 并发下载 270 MB（loopback） | 0 错误，p95 TTFB <20 ms，进程 CPU <30% |
| 限速精度 | 设定值 ±5% |
| 5% 下载失败注入 | 各重试 1 次后正确标记，不无限重试 |
| 同 hash 重复重建 | 0 次重推（全员跳过） |
| 进服风暴（100 人同时进） | 抖动生效，出口不出现 100 并发尖峰 |

### 20.7 明确不做

- 组播/多播：公网不可用，托管商普遍禁用。
- P2P 分发：客户端不支持，且引入信任问题。
- 增量补丁：原版客户端只认整包 sha1，没有 delta 机制。
- 每玩家定制包：破坏统一包语义与缓存。

## 21. 内存管理（v4 增补）

### 21.1 目标：按档设硬上限

| 负载 | 当前实测峰值 | raw-copy 原型 | **目标（含 4 线程）** |
| --- | --- | --- | --- |
| W1 270 MB / 469 条 | 547 MB | 14–15 MB | **≤60 MB** |
| W2 40 源 / 5,063 条 | 59–100 MB | 55 MB | **≤80 MB** |
| 40 万条 | 未测（zip crate 估算 >1 GB） | — | **≤150 MB** |
| 1 GB 单条 | 受 64 MB 条目上限阻断 | — | **≤48 MB 流式窗口** |
| JVM 侧（插件本体） | 常驻 <100 MB（未见尖峰） | — | 堆增量 **≤64 MB** |

547 MB 的成因不是「Rust 吃内存」，而是**逐条目解压进 Vec + 再复制进 Blob + 冲突时算 sha1**：同一份数据在内存里出现 2–3 次。21.2 的铁律就是把「份数」压到 1。

### 21.2 五条铁律

1. **不整包读入**：禁止对 zip 整体 read_to_end；只有需要重写的合并 JSON 才允许进内存，且单文件 ≤16 MB、合计 ≤64 MB。
2. **每次构建一个 arena**：手写 bump arena（Vec<u8> + offset，无新依赖），构建结束整体释放；条目名、命名空间、临时字符串都往里放，把分配次数从 O(条目数) 降到 O(线程数)。
3. **处处有界**：有序提交环 = 线程数 × 2；索引缓存 LRU ≤32 MB；冲突明细 ≤5,000 条；fanout 队列 = 在线玩家数；任何队列都不允许无界增长。
4. **大文件只映射窗口**：Huge 档用只读 mmap + 顺序访问提示（Windows FILE_FLAG_SEQUENTIAL_SCAN，Linux fadvise SEQUENTIAL），**扫完立即 drop 映射**，不同时保留 40 个映射；madvise DONTNEED 及时归还。
5. **复用而非新建**：每线程一个 64 KB scratch buffer、一个 CRC/SHA1 实例；条目名用 interned id + &str 切片，不 per-entry 建 String。

### 21.3 数据结构（内存账）

| 结构 | 单条大小 | 40 万条 | 说明 |
| --- | --- | --- | --- |
| EntryMeta（紧凑） | 32–40 B | **13–16 MB** | name_id u32 / source_id u16 / priority i16 / method u8 / crc u32 / raw u64 / comp u64 / offset u64 |
| 胜者索引表 | 4 B | 1.6 MB | Vec<u32> 指向 EntryMeta |
| 名称 arena | 去重后约 40 B/名 | **<1 MB** | 40 源 × 2,000 名重复率极高，intern 后只留一份 |
| 冲突明细 | 上限 5,000 × ~200 B | ≤1 MB | 与总条目数无关 |
| 合并 JSON 峰值 | 单文件 ≤16 MB | ≤64 MB 合计 | 解析后立即合并、立即释放 |

对照现状：Blob 每条目持有解压后的 Vec<u8>（W2 平均 17 KB × 5,063 ≈ 86 MB，再加 map 与 sha1 副本）。改成 EntryMeta 后，W2 的元数据只有 **约 0.2 MB**，其余内存全是流式缓冲。

### 21.4 分配器与线程

- **不引入 mimalloc / jemalloc**：jar +100–300 KB（§18），而 arena 之后分配次数已经很低，实测收益 <5% 不划算；先做 arena，再用数据决定。
- 线程栈用 Builder 显式设 **512 KB**（默认 2 MB × 4 = 8 MB 纯栈开销）。
- 大缓冲用 try_reserve，失败返回可读错误而不是 abort；与 catch_unwind 配合保证进程体面退出。
- Windows 输出：SetEndOfFile 预分配目标大小，避免写时反复扩文件 + page cache 双份；不使用需要特权的 SetFileValidData。
- 读写缓冲：BufReader 128 KB / BufWriter 1 MB（当前 zip crate 默认值偏小，是 W2 55 MB 的主要来源之一）。

### 21.5 JVM 侧

- Hashing.sha1Hex 已是流式（core/src/main/java/moe/nekomc/resourcepack/core/Hashing.java:16-26，8 KB 缓冲）→ 缓冲改 64 KB；并且 D4 落地后直接复用 Rust 返回的 sha1，**省掉对 270 MB 的第二次全文件读**（约 0.1 s + 一次 page cache 压力）。
- 发布器只持有 Path 与 PackSnapshot（record），**绝不持有包字节**；旧包文件等 HTTP 句柄释放后再删（延迟删除/引用计数），避免「已删除但仍被读」。
- DeliveryStatusStore 已有 200 条上限；fanout 队列长度 = 在线玩家数，不复制包数据。
- reload 时清空上一代 registry/community 引用，避免两代对象同时驻留。
- 堆不用调；观测指标是「插件堆增量 ≤64 MB」，超过说明有意外缓存。

## 22. 统一实施步骤（汇总，取代 §12）

按依赖排序，每步单独可交付、单独 commit（不 push）；每步结束时既有 20 个集成测试必须全绿。

### 阶段 0：基准固化（0.5 天）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S0 | 把 .tmp 里的基准脚本收进仓库（tools/bench 或 rust/rps-pack/bench），生成 W1 / W2 / Tiny 三档数据；记录时间与峰值 RSS 基线；CI 加 jar 体积断言（§18.2） | 一条命令复现基线；CI 有 size 守门；基线写入 perf-benchmark 表 |

### 阶段 1：内核重写（3 天，P1）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S1 | 自写顺序 zip writer：本地头 / 中央目录 / EOCD / zip64；时间戳统一 DateTime::default；名称升序 | 20 个既有测试全绿；70k 条 zip64 可读；同输入两次 sha1 完全一致 |
| S2 | 扫描与元数据：只读中央目录，EntryMeta（32–40 B）+ 名称 arena + 胜者索引；CRC+size 预筛、冲突时才流式 SHA-1；覆盖策略 override/first/strict | W2 元数据 RSS ≤10 MB；冲突数与原实现一致（182）；无解压扫描 |
| S3 | JSON 合并 + mcmeta 重建 + 报告：合并放到写出阶段，level 9；报告增加 rawBytes / ratio / recompressed / peakRss | JSON 与 mcmeta 测试全绿；报告字段契约测试（JVM 侧解析）通过 |
| S4 | 逐条目压缩策略：passthrough / 重压 / store 三态（§19.3） | W1 ≤ 来源 +0.5%；W2 ≤ 全 level 6 基线；merged ≤ min(两种全量策略)×1.01 |

### 阶段 2：三档与多核（2 天，P2–P4）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S5 | Tiny 档：单线程、fs::read 直读、预分配、零线程 | 1 条微包 ≤14 ms；200 条/27 KB ≤18 ms |
| S6 | Huge 档：mmap 窗口、>64 MB 单条流式、非临时写、zip64 | 270 MB ≤0.20 s / ≤60 MB；1 GB 单条 ≤1.2 s / ≤48 MB |
| S7 | Many 档 + 多核：thread::scope、有序提交环、64 条动态领取、RPS_THREADS | 扫描 ≥2.8×、合并+压缩 ≥3.0×（4 线程）；W2 ≥1.10×；W1 退化 ≤3% |

### 阶段 3：运行期（2 天）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S8 | 增量与缓存：构建指纹短路、源索引 LRU ≤32 MB、sha 复用、watch 300 ms 防抖 | 源未变时二次构建 ≤3 ms；缓存内存 ≤32 MB |
| S9 | （可选）常驻合并进程 --serve：长度前缀协议、空闲 300 s 退出、拉起失败自动回退 | 小包端到端 ≤3 ms；崩溃不影响主流程 |
| S10 | FanoutController：并发窗口、出口令牌桶、渐进升窗、抖动、失败重试一次、指标 | 1000 假玩家发包 <200 ms；200 并发 loopback 0 错误；限速 ±5% |

### 阶段 4：对接与收尾（1.5 天）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S11 | JVM 对接：复用 Rust 返回的 sha1（去掉第二次全文件读）、新配置项、/rps status 展示压缩率与 fanout、reload 语义 | 端到端一次构建 + 推送通过；JVM 堆增量 ≤64 MB |
| S12 | （可选）平台 I/O：Windows / Linux / macOS 裸 FFI，feature 默认关 | 与通用路径相比提升 ≥10% 才保留，否则删除 |
| S13 | 全量基准与文档：W1 / W2 / Tiny / 40 万条 / 1 GB 五档复测，更新 perf-benchmark、optimization-research、README | 五档数字齐全；jar 体积在 §18 上限内；回归 >20% 不允许发布 |

### 依赖与顺序

S0 → S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8 → S10 → S11 → S13；S9 与 S12 为可选支线，不阻塞主线。

### 总量

主线约 **8.5 天**（S0 0.5 + S1–S4 3 + S5–S7 2 + S8 1 + S10 1 + S11–S13 1.5，含缓冲）；加 S9、S12 约再 2 天。第一片 S1 是风险最高的一步（字节级确定性与 zip64），也是后面所有优化的地基。

### 每步的停止条件

- S1 后字节级确定性测试失败 → 停止，先修 writer，不进入 S2。
- S4 后压缩率目标差 >2% → 该条目类别回退全量重压，记录原因再继续。
- S7 后 W1 退化 >3% → Huge 档禁用线程池，多核只保留 Many 档。
- S10 后 200 并发出现错误 → 先降默认并发窗口，再查 HTTP 层。
- 任一步 RSS 回归 >20% → 停止该步，先定位分配点。

## 23. S0 / S1 实施记录（2026-11-27）

### 23.1 S0 基准固化

- `tools/bench/`：`gen2.py`、`measure.ps1`、`run-bench.ps1`、`Bench.java`、`DeflateBench.java`、`README.md`；W1 / W2 数据仍生成到 `.tmp/bench`（gitignored）。
- `tools/check_jar_size.py`：Paper ≤ 2,750,000 B、Fabric ≤ 2,900,000 B；CI 在 `Assert bundled merger` 之后新增 `Assert jar sizes` 步骤。
- 当前实测：Paper 2,510,148 B、Fabric 2,659,120 B，均在预算内。

### 23.2 S1 自写 writer

- 新增 `rust/rps-pack/src/writer.rs`（约 380 行）：本地头 → 数据 → 中央目录 → [zip64 EOCD + locator] → EOCD；时间戳固定 1980-01-01、权限 0644、UTF-8 标记、条目按 `BTreeMap` 名称升序（调用方已排序）；单条目 ≥ 4 GiB 报错，留待 S6。
- `engine.rs` 的 `write_zip` 改为调用 writer；`Cargo.toml` 增加 `flate2`（zlib-rs）与 `crc32fast`；删除 `Entry::zip64_*` 死代码。
- 新增 `tests/writer.rs` 4 例：回读内容与默认时间戳、非 ASCII 名、同输入字节一致、70,000 条触发 zip64 目录。
- `cargo test`：30 项全绿（lib 4 + merge 20 + mcmeta 2 + writer 4），0 失败。

### 23.3 实测（同机同输入，3 次）

| 工作负载 | 耗时 | 峰值 RSS | 产物 | 对照 zip crate |
| --- | --- | --- | --- | --- |
| W1 270 MB / 469 条 | 4,010–4,297 ms | 547 MB | 270,174,433 B | 4,073 ms / 547 MB / 同尺寸 |
| W2 86 MB / 5,063 条 | 1,015–1,111 ms | 58–100 MB | 44,065,368 B | 973 ms / 59–100 MB / 同尺寸 |

结论：S1 只替换写出实现，压缩级别与头部布局保持一致，尺寸与耗时符合预期地基本不变（W2 慢 4–14% 属噪声）。真正的 20× 收益来自 S2 / S4 的不重压缩，S1 的价值是拿到可控的字节布局，为 raw copy 与 zip64 打地基。

*实测数据来自本机 2026-11-27 的基准（`.tmp/bench`，脚本已固化在 `tools/bench/`）；「估算」项在实施时必须有实测替换，否则不写进结论。*

## 24. 实施记录：S2–S4（中央目录扫描 + 直拷 + 自适应 store）

状态：**已落盘、测试与实测通过**（2026-11-27）。核心是「不解压、不重压缩」。

### 24.1 结构变化

- 新增 `src/scan.rs`（约 215 行）：`EntryMeta { index, name, crc, size, compressed, offset, raw_method }`，`SourceReader::open` 只用 `by_index_raw` 读中央目录，提供 `read / sha1 / raw_slice`；`pack.mcmeta` 单独解压解析。
- `engine.rs` 重写为 scan → resolve → write：`Winner { body: Raw { source, index, compressed } | Owned(Vec<u8>) }`；冲突先用 CRC+size 预筛，必要时才流式 SHA-1；`pack.mcmeta` 仍重建后以 Owned 写入。
- 删除 `archive.rs` 与 `crc.rs`（全量解压路径、手写 CRC 表都不再需要）。
- `writer.rs` 新增 `add_raw`（搬运原始压缩字节并校验长度）与 `add_auto`（先压缩，压不动就 store 并返回 false）。
- `model.rs` 报告新增 `rawBytes`、`ratio`、`recompressed`、`stored`；旧消费端忽略未知字段，协议向后兼容。

### 24.2 实测（同机同输入，3 次，release）

| 工作负载 | S1 基线 | S2/S4 | 提升 |
| --- | --- | --- | --- |
| W1 270 MB / 469 条 | 4,073 ms / 547 MB | 242–285 ms（中位 253）/ 7.8 MB | 16× 快、70× 内存 |
| W2 86 MB / 5,063 条 | 973 ms / 59–100 MB | 228–242 ms（中位 237）/ 16 MB | 4.1× 快、4–6× 内存 |

- W2 报告：`entries 5063 / conflicts 182 / deduped 0 / rawBytes 43,343,236 / ratio 1.016 / recompressed 1 / stored 0`；条目数与 `jar tf` 一致，冲突数与旧实现完全一致。
- 测试：lib 4 + merge 20 + mcmeta 2 + writer 4 = 30 项全绿，含新增 `stored_entries_are_passed_through`（store 条目直拷、不被重压）。
- **mtime 坑**：`ops_edit_file` 会保留 mtime，cargo 因此复用旧二进制（表现为报告缺字段、构建 0.08s）；改完 Rust 源码必须 `touch src/*.rs tests/*.rs` 再构建。

### 24.3 尚未做

- S5 Tiny、S6 Huge（mmap / 非临时写）、S7 Many 多核、S8 增量短路、S9 `--serve`、S10 推送、S11 JVM 对接、S13 五档复测。
- 报告中的 `peakRss` 未由 Rust 侧采集（Windows 需额外 API），目前由 `tools/bench/measure.ps1` 外部测量。

## 25. 实施记录：S5 / S6（小包与 I/O 单趟化）

状态：**已落盘、测试与实测通过**（2026-11-27）。这一轮没有引入新依赖，全部是 I/O 与内存路径的收紧。

### 25.1 改动

- **单趟 SHA-1**：本地头在写之前就已有 crc 与压缩后大小（deflate 结果先落内存），因此取消回填，所有写入都过 `emit()`，边写边算 SHA-1；`finish()` 返回 `WriteStats { bytes, sha1, recompressed, stored }`，`engine.rs` 不再回读输出文件（删掉 `file_sha1` 与 `metadata().len()` 两趟）。
- **直拷缓冲**：`add_raw` 用 1 MB scratch 循环搬运，替代 `io::copy` 的默认小缓冲；输出 `BufWriter` 保持 1 MB（试过 4 MB，峰值内存 +4 MB 而耗时无收益，已回退）。
- **顺序读提示**：`scan::open_source` 在 Windows 上对 **≥ 8 MiB** 的来源加 `FILE_FLAG_SEQUENTIAL_SCAN`；小包（多文件随机 seek）不加，避免伤缓存命中率。
- **自适应 store**：`add_auto` 压不动就退回 store；报告里的 `recompressed / stored` 由 writer 直接统计。
- 工具：新增 `tools/bench/latency.py`（Python 计时，规避 PowerShell 启停开销），`measure.ps1` 增加 `-quiet`。

### 25.2 实测（Python 计时，同机，release）

| 工作负载 | 墙钟（min / 中位 / max） | 进程内 | 结论 |
| --- | --- | --- | --- |
| micro（1 条 / 1 KB） | 13.5 / **14.5** / 20.9 ms | 0 ms | 启动占 13+ ms，S5 目标 14 ms 已到边界 |
| small200（201 条 / 27 KB） | 15.7 / **16.4** / 18.5 ms | 2 ms | 目标 18 ms 达标 |
| W1 270 MB / 469 条 | 232.5 / **235.9** / 237.9 ms | 220 ms | 目标 200 ms 未达，残差是 270 MB 的顺序搬运本身 |
| W2 86 MB / 5,063 条 | 239.8 / **254.0** / 337.2 ms | 232 ms | 目标 300–400 ms 达标 |

### 25.3 判断

- **微包的下限是进程创建，不是合并逻辑**：内部耗时 0 ms，14.5 ms 全是 `CreateProcess` + 装载 + 退出。要突破必须靠 S9 的常驻 `--serve`（预计 1–3 ms）。
- **W1 的残差**是纯内存/页缓存搬运：270 MB 读 + 270 MB 写 ≈ 236 ms，即约 1.15 GB/s。再快需要非临时写（S12）或 io_uring/重叠 I/O，不属于 S6。
- **单趟哈希在热缓存下没有可测收益**（旧路径的回读也被页缓存吸收），价值在冷缓存与「少一趟失败面」，保留。
- `measure.ps1` 的 40–50 ms 固定开销此前把微包数字整体抬高，之后的小包测量一律用 `latency.py`。

## 26. 审计修复：S1–S6 复审（2026-11-28）

只读审计覆盖 writer / scan / engine / model、JVM 消费端、CI 与脚本文档。结论是**两个 P0 都出在新增的直拷主路径上**，且现有 30 项测试与全部基准 fixture 恰好绕过了它们——性能数字可信，但不能当正确性证据。以下逐条修复。

### 26.1 P0

- **P0-1 请求下标被当成 reader 下标**：`readers` 按 priority 顺序 push，`order[rank]` 是**请求下标**，却曾被存进 `Body::Raw.source`，后续拿它去索引 `readers`。只要请求里的 priority 不单调（真实路径：registry 的 priority 0 与社区层 priority 0 混排），就会从另一个来源读内容与 SHA-1。修法：`Body::Raw.source` 只存 **rank**，`from_meta(meta, rank, source, readers)`；请求下标只用于取 `Source` 元信息。
- **P0-2 紧凑位置被当成 archive 索引**：`EntryMeta.index` 是 `by_index_raw` 的 archive 索引，而 `SourceReader.entries` 跳过了目录条目，两者不相等；写出阶段用 `entries.get(index)` 取 offset，遇到带目录条目的包会读错条目或直接报「来源索引失效」。修法：扫描阶段就把 `offset` 存进 `Winner::from_meta`（`Body::Raw { source, index, offset, compressed }`），写出只用 offset，不再二次查表。

### 26.2 P1

- **来源 CRC 校验可选**：`Request.verify_sources`（`#[serde(default)]`，默认 false）开启后，直拷条目会解压并按声明的 CRC/大小核对；默认关闭以保住直拷收益，需要时（排障、CI 抽检）可开。
- **报告 sha1 契约测试**：新增「`stats.sha1` / `report.sha1` 必须等于产物文件真实 SHA-1，且 `bytes` 等于文件长度」，避免「两次都错得一样」的自洽断言。
- **报告截断**：JVM 侧 `STDOUT_LIMIT` 由 1 MB 提到 8 MB（5000 条冲突明细约 1–2 MB，原上限会把 JSON 截断成解析失败）。

### 26.3 P2

- 加密条目在扫描阶段直接报错（原来会把密文当 deflate 数据直拷、并清掉加密位）。
- zip64 判定统一为 `>=`：条目数 65535、中央目录大小/偏移恰为 `0xFFFFFFFF` 时不再写出没有 zip64 EOCD 的歧义目录。
- 格式区间报错的来源名与区间重新配对（`ranges` 改存 `(id, low, high)`），缺 `pack.mcmeta` 的来源不再让后续 id 错位。
- `..` 校验改为按路径分段判定，`a..b.png` 这类合法文件名不再被误杀。
- 读取句柄改为独立 `File::open`（不再 `try_clone` 共享游标），消除「archive 读」与「raw 直拷」互相影响游标的隐患。
- CI 增加 `cargo check --target x86_64-pc-windows-msvc`，覆盖 `FILE_FLAG_SEQUENTIAL_SCAN` 的 Windows 分支编译。
- 文档计数与写入顺序表述订正（§23.2）。

**保留的既有语义**：请求里的 `format` 仍然直接覆盖交集结果——这是现有测试 `requested_format_overrides_intersection_max` 锁定的行为，改成夹紧会破坏契约，因此只记录风险、不在本轮改动。

### 26.4 回归与实测

- `cargo test --offline`：**34 项全绿**（lib 4 + merge 23 + mcmeta 2 + writer 5），新增 `out_of_order_priorities_read_from_winning_source`、`directory_entries_do_not_shift_raw_offsets`、`corrupting_source_data_is_detected_when_verifying`、`finish_reports_real_sha1_and_size`。
- 产物字节未变：W1 `sha1 d1a42866…` / 270,173,083 B / 469 条；W2 `sha1 62e20fa2…` / 44,053,664 B / 5,063 条 / 182 处冲突。
- 修复后 release 实测：W1 217 / **238** / 315 ms，W2 260 / **261** / 373 ms（冷热缓存波动，与 §25 同量级）。
- 全量 `gradle build` 通过；jar 体积守卫通过：Paper 2,514,796 B、Fabric 2,663,771 B。
### 26.5 第二轮复审（同日）

复审确认两个 P0 均已修复（`engine.rs` 只以 rank 访问 readers；`Body::Raw` 自带 offset，全仓无 `entries.get(index)` 取偏移），无残余 P0。其余问题本轮一并修掉：

- **P1 解压校验无上限**：`verify_raw` 现在边解压边累计，超过 `MAX_ENTRY_BYTES`（64 MiB）立即报错，压缩炸弹不再能通过校验路径吃满 CPU/内存。
- **P1 反斜杠绕过**：`..` 判定改为同时按正斜杠与反斜杠切段，反斜杠形式的路径穿越在扫描与 writer 两侧都会被拒。
- **P2 报告静默截断**：`RustPackMerger.readLimited` 超 8 MB 直接抛「rps-pack 报告超过 8388608 字节，已拒绝解析」，不再把截断的 JSON 交给解析器。
- **P2 Stored 大小不一致**：`compressed_size != size` 的 Stored 条目退回解压路径，避免直拷写出坏包。
- **P2 冲突明细截断顺序**：先按严重度（High→Medium→Low）再按路径排序后取前 5000 条，高严重度冲突不会被字典序挤掉。
- **P2 报告新增 `rawCopied`**：直拷条目计数（W1 468、W2 5062，余下是重建的 `pack.mcmeta` 与需要重压的条目）。协议只做加法，JVM 侧用 `has()`/`getAsXxx` 读取，旧字段语义不变。
- **P2 zip64 阈值统一**：本地头与中央目录都以 `>=` 判定，避免 0xFFFFFFFF 边界写出歧义目录。

验证：`cargo test --offline` **36 项全绿**（lib 4 + merge 24 + mcmeta 2 + writer 6），新增 `zip64_directory_starts_at_65535_entries`（65,534 条不写 EOCD64、65,535 条写）与 `stored_low_priority_source_yields_to_high_priority`（低层 Stored、高层 Deflate 时仍取高层字节且只直拷 1 条）。产物未变：W1 270,173,083 B / 469 条 / 468 直拷，墙钟 231 / 315 ms；W2 44,053,664 B / 5,063 条 / 182 冲突 / 5,062 直拷，墙钟 362 / 261 ms。全量构建 BUILD SUCCESSFUL，jar 体积守卫通过：Paper 2,521,536 B、Fabric 2,670,516 B。
## 27. S8：输入未变短路与 watch 防抖（2026-11-28）

### 27.1 实现

- **构建指纹**：新增 `core/src/main/java/moe/nekomc/resourcepack/pack/SourceFingerprint.java`（SHA-256、字段间以 0 字节分隔，null 与空串等价）。`PackPublisher.fingerprintOf` 收进影响产物内容的配置（`pack-format`、`description`）与每个参与来源的元信息（id、文件路径、enabled、priority、声明 sha1、version、文件大小 + 修改时间）。
- **短路**：`PackPublisher.build` 在创建 staging 之前比较指纹；与上一轮成功构建相同且 `merged.zip` 存在时直接 `completeWaiters(buildResult(), round)` 并返回，不再起 `rps-pack` 进程、不重写状态、不重复下发。指纹只在成功路径末尾更新，失败不会污染。
- **watch 防抖**：`SourceWatcher` 增加 `DEBOUNCE_MILLIS = 300` 与 `pendingChange`：变动先记账，距上次触发不足 300 ms 就留到下一轮，连续写入只触发一次重建；回调抛错时快照不落、`pendingChange` 保留，下一轮重试。
- 短路只发生在内存态：插件重启后指纹为空，第一次构建必然重建，所以升级 jar 不会复用旧产物。

### 27.2 验证

- 新增 `SourceFingerprintTest`（3 例：同输入同摘要且长度 64、字段顺序/拼接不可混淆、null 与空串等价）。
- `gradle build`：core 19 套件 / **96 项**、paper 2 套件 / 7 项，0 失败；共 103 项。
- 短路效果只能在真服观察：`watch` 周期内改一个无关文件不会再来一次外部进程；`/rps build` 在输入未变时返回当前版本而不是重建（需要强制重建时 touch 来源文件即可改变指纹）。


## 28. S9：常驻合并会话 `--serve`（2026-11-28）

### 28.1 协议与实现

- **为什么**：微包 14.5 ms 里 13+ ms 是 Windows 进程创建/装载（§25.3），合并逻辑本身 0 ms。常驻会话把这个固定成本摊到一次启动上。
- **Rust 侧**：`rust/rps-pack/src/main.rs` 新增 `serve` 子命令（`run_serve` / `serve_frame` / `write_frame` / `error_json`）。stdin/stdout 上收发 **u32 小端长度前缀 + JSON** 帧：
  - 请求：`{"output":"<产物路径>","request":<与一次性模式相同的请求对象>}`
  - 响应：合并报告（`ok:true`）或 `{"ok":false,"error":"..."}`；单帧出错**不会**退出进程。
  - `--idle-seconds` 默认 300（最小 1）；空闲超时或 stdin 关闭即退出 0。读线程 `rps-pack-serve-stdin` 阻塞读帧，主线程用 `recv_timeout` 守空闲上限，写出后立即 flush。
  - 帧上限 16 MiB（`MAX_FRAME_BYTES`），超限直接断开，避免内存被无界帧吃掉。
- **JVM 侧**：
  - `core/src/main/java/moe/nekomc/resourcepack/merge/RustPackServeSession.java`：启动 `rps-pack serve --idle-seconds 300`（工作目录 = 插件数据目录，stderr 追加到 `rps-pack-serve.log`），读线程把响应帧放进有界队列，`call(frame, timeoutMs)` 同步收发；超时/断流/协议异常都会标记 broken 并关闭进程。
  - `core/src/main/kotlin/moe/nekomc/resourcepack/merge/RustPackServe.kt`：按**可执行文件绝对路径**池化会话（`PackPublisher` 每次构建都 new 一个 `RustPackMerger`，会话不能挂在 merger 实例上）；启动失败或任一调用异常就把该 binary 标为 unsupported，**永久回退一次性进程**，并在 JVM 退出时统一关闭。
  - `RustPackMerger.merge` 先构造 `{"output", "request"}` 帧交给池，拿到报告就直接解析；返回 null（旧二进制没有 serve、进程起不来、超时）时走原一次性路径，超时/stderr/8 MiB 报告上限等既有保护不变。
- **兼容性**：旧版 rps-pack 不认识 `serve`（退出码 64），首次调用即失败并回退，功能不受影响，只是没有 1 ms 的收益。

### 28.2 验证

- Rust 新增 `rust/rps-pack/tests/serve.rs`：坏帧返回 `ok:false` 后进程仍能服务；同一进程连续两帧各自产出文件且 `entries=2`；关闭 stdin 后退出码 0。`cargo test --offline` **37 项全绿**（lib 4 + merge 24 + mcmeta 2 + writer 6 + serve 1）。
- JVM 新增 `RustPackMergerTest.serveSessionIsReusedAcrossMerges`：两次合并后 `RustPackServe.activeSessions()` 不增长且 ≥1，证明走的是常驻会话而不是每次新建。带 `RPS_PACK_BIN` 跑该类 **5 项全过、0 跳过**（默认构建里没有 `core/build/dist/bin` 时这组会按既有约定 skip）。
- 全量 `gradle build` BUILD SUCCESSFUL；core 19 套件 / 97 项 + paper 2 套件 / 7 项 = **104 项**（本地默认 4 项因缺少二进制而 skip，CI 设置 `RPS_PACK_BIN` 后不 skip）。
- 常驻帧往返（Python 驱动同一进程，1 KB 单条目、20 帧）：**中位 0.945 ms**、均值 0.965、min 0.767、max 1.325 —— 相比一次性的 13.5–14.5 ms 降低一个数量级，达到 §10 的 1–3 ms 目标。
- 首次合并仍需进程启动与二进制解出（`BundledRust.ensure`），这部分发生在插件启用/首次构建，不影响后续每次重建。
