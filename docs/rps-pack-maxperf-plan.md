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
| W2 超多（86 MB / 现夹具 12,161 条，旧夹具 5,063 条） | ~0.47 s / 55 MB | **0.30–0.40 s / ≤40 MB** | 自写顺序 writer、紧凑元数据、并行扫描 |
| 巨量（200 源 / 40 万条） | 未测，zip crate 固定开销约 8 s（估算） | **≤1.5 s / ≤150 MB** | 自写 writer（每条目 ~1.5 µs）+ 索引缓存 |
| 单条巨型（1 GB） | 受 64 MB 条目上限阻断 | **≤1.2 s，支持 zip64**（1 GB 目标 descoped，见 §34.5；stored 直拷到 1 GiB 已由 §38 交付） | 流式哈希、取消解压上限 |

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

- **超大包**：写带宽与哈希带宽决定上限。旧估算 270 MB / 1.8 GB/s ≈ 0.15 s、重叠后 ≈0.15–0.20 s 已作废（实测 235 ms，见 §34.3）。再快只能靠减少拷贝次数，或把哈希从临界路径上挪开：平台专有 I/O 已在 §34 评估后放弃，非临时写降级为待办；实测瓶颈是 SHA-1 与写入串行，见 S14。
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
  │    └─ io_win.rs（未采用）   平台 I/O 原计划，§34 评估后不建
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
| 顺序读提示 | `FILE_FLAG_SEQUENTIAL_SCAN`（`OpenOptionsExt::custom_flags`） | 现有 ≥8 MiB 来源句柄已用（§6）；给直拷/输出句柄新增的部分热缓存无收益（§34.3），未采用 | 低 |
| 输出预分配 | `SetFileInformationByHandle(FileAllocationInfo)` | 减少碎片，写 +3–8%（估算）；未采用 | 需 windows-sys 依赖或裸 FFI |
| 大段非临时写 | `_mm_stream_si128` | 270 MB 写 +5–12%（估算）；未采用，降级为待办（§34.5） | 需 16 B 对齐；仅 Huge 档 |
| mmap 源 | `memmap2`（底层 CreateFileMapping） | 省一次拷贝 | 文件被替换时的映射语义 |
| 常驻进程 | 命名管道或 stdio | 小包 16 ms → 1–3 ms | 生命周期管理（P6） |

### 8.2 Linux

| 手段 | API | 预期 | 风险 |
| --- | --- | --- | --- |
| 内核区间拷贝 | `copy_file_range`（源 fd → 输出 fd） | raw copy 段 +20–40%（估算，**已作废**：与单趟 SHA-1 冲突，见 §34） | 需自写 writer 才能绕过 zip crate；跨文件系统回退 |
| 顺序预读 | `posix_fadvise(SEQUENTIAL)` | 未采用（提示级、本机无法验证，见 §34.5） | 低 |
| 预分配 | `fallocate` | 未采用（见 §34.5） | 低 |
| 异步 I/O | io_uring（可选，P6 研究） | 大量小读 +10–20%（估算） | 复杂度高、内核版本要求 |

### 8.3 macOS

| 手段 | API | 预期 | 风险 |
| --- | --- | --- | --- |
| 区间拷贝 | `fcopyfile` | 未采用（与单趟 SHA-1 冲突，见 §34.5） | 低 |
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

### P6 平台 I/O + 常驻模式（2 人日，可选；平台 I/O 已评估、未采用，常驻模式即 S9）

- 平台（曾评估）：Windows 现有顺序标志（已用）/预分配/非临时写（待办）；Linux copy_file_range/fadvise/fallocate、macOS fcopyfile 均未采用（见 §34）。
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
- [ ] P3 超大包（mmap/流式/zip64/非临时写）（mmap 与非临时写 descoped，见 §39.4/§34.5；≥4 GiB zip64 见 §39.3）
- [ ] P4 超多包（并行扫描/紧凑元数据/索引缓存/指纹）（索引缓存 descoped，见 §39.2）
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
| W2 40 源 / 5,063 条（旧夹具；分摊为当时实测，现夹具见 §39.1） | 扫描 0.05、决策 0.15、写 0.20、哈希 0.02 | ~30% | **1.10–1.15×** |
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
| memmap2 | 约 +25 KB | **不用**（§39.4 mmap 不做，依赖不引入） |
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
| 1 GB 单条 | 受 64 MB 条目上限阻断 | — | **≤48 MB 流式窗口**（stored 条目已放宽到 1 GiB 直拷，见 §38；deflate/JSON 路径仍 64 MiB） |
| JVM 侧（插件本体） | 常驻 <100 MB（未见尖峰） | — | 堆增量 **≤64 MB** |

547 MB 的成因不是「Rust 吃内存」，而是**逐条目解压进 Vec + 再复制进 Blob + 冲突时算 sha1**：同一份数据在内存里出现 2–3 次。21.2 的铁律就是把「份数」压到 1。

### 21.2 五条铁律

1. **不整包读入**：禁止对 zip 整体 read_to_end；只有需要重写的合并 JSON 才允许进内存，且单文件 ≤16 MB、合计 ≤64 MB。
2. **每次构建一个 arena**：手写 bump arena（Vec<u8> + offset，无新依赖），构建结束整体释放；条目名、命名空间、临时字符串都往里放，把分配次数从 O(条目数) 降到 O(线程数)。
3. **处处有界**：有序提交环 = 线程数 × 2；索引缓存 LRU ≤32 MB（经 §39 评估不做）；冲突明细 ≤5,000 条；fanout 队列 = 在线玩家数；任何队列都不允许无界增长。
4. **大文件只顺序读**：Huge 档走顺序读 + Windows `FILE_FLAG_SEQUENTIAL_SCAN`（`scan.rs` 对 ≥8 MiB 源启用）；Linux fadvise SEQUENTIAL 随 §34 评估放弃，mmap 经 §39.4 评估不做。
5. **复用而非新建**：每线程一个 64 KB scratch buffer、一个 CRC/SHA1 实例；条目名用 interned id + &str 切片，不 per-entry 建 String。

### 21.3 数据结构（内存账）

| 结构 | 单条大小 | 40 万条 | 说明 |
| --- | --- | --- | --- |
| EntryMeta（紧凑） | 32–40 B | **13–16 MB** | name_id u32 / source_id u16 / priority i16 / method u8 / crc u32 / raw u64 / comp u64 / offset u64 |
| 胜者索引表 | 4 B | 1.6 MB | Vec<u32> 指向 EntryMeta |
| 名称 arena | 去重后约 40 B/名 | **<1 MB** | 40 源 × 2,000 名重复率极高，intern 后只留一份 |
| 冲突明细 | 上限 5,000 × ~200 B | ≤1 MB | 与总条目数无关 |
| 合并 JSON 峰值 | 单文件 ≤16 MB | ≤64 MB 合计 | 解析后立即合并、立即释放 |

对照现状：Blob 每条目持有解压后的 Vec<u8>（W2 现夹具平均约 7 KB × 12,161 ≈ 86 MB，旧夹具 17 KB × 5,063 也是同一量级，再加 map 与 sha1 副本）。改成 EntryMeta 后，W2 的元数据只有 **约 0.2 MB**，其余内存全是流式缓冲。

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
| S6 | Huge 档：>64 MB 单条流式、zip64（非临时写与 1 GB 目标 descoped，见 §34.5；stored 直拷 1 GiB 已由 §38 交付；mmap 与 ≥4 GiB zip64 经 §39 评估不做） | 270 MB ≤0.20 s / ≤60 MB；1 GB 单条 ≤1.2 s / ≤48 MB（descoped，stored 直拷部分已交付） |
| S7 | Many 档 + 多核：thread::scope、有序提交环、64 条动态领取、RPS_THREADS | 扫描 ≥2.8×、合并+压缩 ≥3.0×（4 线程）；W2 ≥1.10×；W1 退化 ≤3% |

### 阶段 3：运行期（2 天）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S8 | 增量与缓存：构建指纹短路、sha 复用、watch 300 ms 防抖（源索引 LRU 经 §39 评估不做） | 源未变时二次构建 ≤3 ms（缓存内存项随 LRU 一并取消，见 §39.2） |
| S9 | （可选）常驻合并进程 --serve：长度前缀协议、空闲 300 s 退出、拉起失败自动回退 | 小包端到端 ≤3 ms；崩溃不影响主流程 |
| S10 | FanoutController：并发窗口、出口令牌桶、渐进升窗、抖动、失败重试一次、指标 | 1000 假玩家发包 <200 ms；200 并发 loopback 0 错误；限速 ±5% |

### 阶段 4：对接与收尾（1.5 天）

| 步 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| S11 | JVM 对接：复用 Rust 返回的 sha1（去掉第二次全文件读）、新配置项、/rps status 展示压缩率与 fanout、reload 语义 | 端到端一次构建 + 推送通过；JVM 堆增量 ≤64 MB |
| S12 | （可选）平台 I/O：Windows / Linux / macOS 裸 FFI，feature 默认关 | 与通用路径相比提升 ≥10% 才保留，否则删除（已评估，未采用，见 §34） |
| S13 | 全量基准与文档：W1 / W2 / Tiny / 40 万条 / 1 GB 五档复测，更新 perf-benchmark、optimization-research、README | 五档数字齐全；jar 体积在 §18 上限内；回归 >20% 不允许发布 |
| S14 | 哈希与写重叠：独立哈希线程 + 小块环形缓冲（顺序不变，SHA-1 仍覆盖每个字节）；已完成，见 §35 | W1 从约 235 ms 降到 ≤160 ms，字节与 SHA-1 不变；失败可退回同步哈希 |

### 依赖与顺序

S0 → S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8 → S10 → S11 → S13；S9 与 S12 为可选支线，不阻塞主线；S14 来自 §34.4 的实测（S13 之后）。

### 总量

主线约 **8.5 天**（S0 0.5 + S1–S4 3 + S5–S7 2 + S8 1 + S10 1 + S11–S13 1.5，含缓冲）；加 S9 约再 1 天；S12 已评估、未采用（只花评估时间，不排实施）；S14 已完成（§35）。第一片 S1 是风险最高的一步（字节级确定性与 zip64），也是后面所有优化的地基。

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
- **W1 的残差**：270 MB 读 + 270 MB 写 + 单趟 SHA-1 ≈ 236 ms。§34.4 的关闭哈希对照显示其中约 100 ms 是 SHA-1、且与写入串行；下一步是把哈希与写重叠（S14）。平台专有 I/O 已评估放弃，非临时写降级为待办。
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

## 29. S7：并行中央目录扫描（2026-11-28）

### 29.1 为什么只并行「扫描」

W2 的内部耗时在 S2 之后只剩三段：扫描中央目录（解压前的元数据）、resolve（冲突判定与 JSON 合并）、写出（直拷 + SHA-1）。扫描是唯一一块**天然按来源切分、结果与顺序无关**的工作；resolve 依赖前序胜者，写出是有序流，都不能乱序。所以这一轮只把 `open_sources` 并行化，其余保持单线程——避免为了 5% 付并发的复杂度。

### 29.2 实现

- `rust/rps-pack/src/scan.rs`：
  - `pub struct SourceSpec { id, priority, path }`：只描述一个来源，便于按 spec 顺序收集结果。
  - `pub fn scan_threads(sources: usize) -> usize`：来源少于 4 个直接返回 1（线程启动比扫描还贵）；否则默认 `available_parallelism()/2` 并夹在 1..=4（留一半核给 JVM 与写盘）；环境变量 `RPS_THREADS` 可覆盖并夹在 1..=8，用于复现与压测。
  - `pub fn open_sources(specs, threads)`：单线程走原来的顺序循环；多线程用 `std::thread::scope`，按 `specs.chunks(ceil(len/threads))` 切片，每片配一个 `Option<Result<SourceReader>>` 槽位。**结果按 spec 顺序收集，错误按 spec 顺序报告**，因此产物与线程数无关（同一 fixture 在 1/2/4 线程下 SHA-1 完全相同）。
- `engine.rs`：把原来的顺序 open 循环换成为 `Vec<SourceSpec>` 后调用 `open_sources(&specs, scan_threads(specs.len()))`；不引入新依赖（不用 rayon/crossbeam）。
- **诊断开关**：设置 `RPS_TRACE=1` 时，在写出之前向 stderr 打一行 `[rps-pack] scan=<n>ms total=<n>ms`，用于区分扫描与写出的成本。成功路径上 JVM 不读 stderr；常驻会话把 stderr 追加到 `rps-pack-serve.log`，因此这个开关不会污染协议输出。

### 29.3 实测（W2 等价 fixture：40 源 / 5,063 条 / 182 冲突 / 44,053,665 B）

交错 9 轮（每轮依次跑 1/2/4 线程，抵消机器漂移），计的是进程内自报 millis：

| 线程数 | 中位 | min | max | 相对 1 线程 |
| --- | --- | --- | --- | --- |
| 1 | 221 ms | 209 | 320 | 1.00× |
| 2 | 187 ms | 184 | 257 | 1.18× |
| 4 | **175 ms** | 169 | 194 | **1.26×** |

- 达到 §17.4 的「W2 端到端 ≥ 1.10×」门槛；扫描段本身从 `scan=59ms` 降到 `scan=19ms`（约 3.1×），说明剩余时间确实在 resolve 与写出上，继续加线程不会更快。
- 早先非交错的 5 轮里 4 线程曾显示更慢（254 vs 235 ms），是杀软扫描与页缓存冷热造成的方差；交错取样后趋势稳定，故以本表为准。
- Tiny / Huge 档来源数少（Huge 典型 3 个），`scan_threads` 直接返回 1，不承担线程开销。

### 29.4 测试

新增 `rust/rps-pack/tests/scan.rs` 3 例：

- `parallel_scan_matches_sequential_scan`：6 个来源分别用 1 线程与 4 线程扫描，逐字段比较 `id / priority / entries(index,name,crc,size,compressed,offset,raw_method) / mcmeta`。
- `parallel_scan_reports_first_failing_source_in_order`：中间来源缺失时，错误必须指向该来源（而不是并行完成更早的另一个错误）。
- `scan_threads_keeps_small_source_sets_single_threaded`：1/3 个来源保持单线程。

`cargo test --offline`：**40 项全绿**（lib 4 + merge 24 + mcmeta 2 + writer 6 + serve 1 + scan 3）。

## 30. S10：海量/瞬时推送（2026-11-28）

### 30.1 目标与设计

大型服一次更新要把同一个包推给上千人。直接对着在线列表 push 会在一瞬间打出上千个下载请求：出口带宽被抢满、客户端超时、服务端线程池排队。S10 把「推给谁、什么时候推、同时推几个」从各个平台的 onJoin 回调里收拢到一个控制器：

- **入队去重**：同一个玩家在队列里只会有一条任务（pending 集合），重复调用不会叠加。
- **已加载过滤**：入队时看一次 delivery.loadedSha1(player)，客户端已经持有同一 SHA-1 的包就直接丢弃，不浪费一次下载；派发时不再二次过滤，避免队列与异步 publish 之间摘要错位（见 30.6）。
- **窗口控制**：同一时刻最多 max-concurrent 个下载在途；玩家回执（LOADED / DECLINED / FAILED / INVALID_URL）释放窗口。
- **渐进升窗**：ramp: true 时从 8 起步，每 5 秒按本窗口失败率调整——失败率大于 2% 减半（不低于 8），小于 1% 增加 25%，直到 max-concurrent。
- **进服抖动**：join: true 的任务在 [0, jitter-ms] 内随机延迟，避免整服同时登录时一起拉包；广播/force 推送不抖动。
- **失败重试**：FAILED / INVALID_URL 在固定的 5s 重试延迟后重排一次，次数由 retry 控制；DECLINED（玩家自己拒绝）不重试。
- **单 tick 上限**：每个 tick 最多派发 200 个，50ms 一个 tick，即峰值 4000 次派发/秒，远高于实际回执速度，但能防止一次性把队列灌进线程池。

### 30.2 实现

- 新增 core/src/main/kotlin/moe/nekomc/resourcepack/delivery/FanoutController.kt：
  - 两个 PriorityQueue<Task>（joinQueue / broadcastQueue），排序键 = (readyAt, seq)；进服任务优先，但没到期时不会挡住已就绪的广播任务。Task(player, sha1, attempts, readyAt, seq)。
  - inflight: HashMap<UUID, Inflight(sha1, at, attempts)> 记录在途下载，pending: HashSet<UUID> 做队列去重。
  - enqueue(players, sha1, join) / onStatus(player, status, sha1) / playerLeft(player) / stats() / close()；所有状态在单锁内变更，push 在锁外调用，避免平台调度器回调时持锁；tick 用 ticking 标志防重入（Fabric 的 TickScheduler 是线程池）。
  - 在途超过固定的 120s 没有回执的任务计一次 failed 并释放窗口——客户端断线、退回主菜单这类没有回执的情况不会永久占住窗口；玩家退服时 playerLeft 立即回收窗口并撤掉未派发任务。
  - 迟到回执（回执 sha1 与在途任务不一致）只释放窗口并计 dropped，不消耗新代的重试额度。
  - push 返回 0（平台拒绝/玩家不在线）时立即清掉在途并计 dropped，不占用窗口。
- PackDelivery.java 新增 default String loadedSha1(UUID player)，默认空串 = 不知道，控制器就当没加载过。
- PackPublisher：fanoutRef 懒建（用 config 的四个参数），supersede 时 getAndSet(null)?.close()；广播路径改为入队，没有控制器时回退旧的逐个 push。
- Paper PackPusher / Fabric FabricPackDelivery：进服推送走 enqueue(List.of(uuid), lastSha1, true)；onStatus 把平台回执转发给控制器；onQuit 调 playerLeft；各自实现 loadedSha1（读本地状态表里的 hash）与 push(UUID, String sha1, boolean force)，让过滤、发送、回执绑定同一个摘要。
- /rps push 的全体分支先走 fanout().enqueue，逐个 push 只在没有控制器时作为回退。
- /rps status 新增一行「推送队列」：排队 / 下载中 / 已推送 / 已完成 / 失败 / 窗口。
- **force 语义**：push.mode: force 用空 sha1 入队，跳过已加载过滤（force 就是要重推），但仍受窗口与单 tick 上限约束，不会绕过保护。

### 30.3 配置（paper/src/main/resources/config.yml，Fabric 复用同一份）

```yaml
delivery:
  fanout:
    max-concurrent: 64
    jitter-ms: 1500
    retry: 1
    ramp: true
```

max-concurrent 夹在 1..1024，jitter-ms 夹在 0..10000，retry 夹在 0..3；ramp 缺省 true。

### 30.4 验证

- core/src/test/java/moe/nekomc/resourcepack/delivery/FanoutControllerTest.java 4 例：已加载同 SHA-1 跳过（dropped=1、pushed=1）、窗口 2 时两个在途+一个排队、回执释放后继续派发、FAILED 重试一次后放弃、重复入队去重。
- 同目录 FanoutControllerLifecycleTest.java 6 例：迟到回执只释放窗口且不吃重试、push 返回 0 立即回收、退服腾窗口、120s 超时与 close 后不发包、未到期进服任务不挡广播、失败率超 2% 降窗（用注入时钟推进）。
- 全量 gradle build BUILD SUCCESSFUL；core **21 套件 / 107 项** + paper 2 套件 / 7 项 = **114 项**，0 失败（4 项 RustPackMerger 用例需要 RPS_PACK_BIN，未设时跳过）。
- tools/check_jar_size.py：Paper 2,603,687 B ≤ 2,750,000；Fabric 2,751,160 B ≤ 2,900,000。
- 本轮不动 Rust 侧，rps-pack 的 40 项测试与基准数字不受影响。

### 30.5 明确未做（留 S10b）

- HTTP 出口的并发连接上限与 503 安全网、egress-bps 全局限速：需要先有按连接的字节计数，属于 PackHttpServer 的改造，单独一轮。
- 按玩家退避（同一个人连续失败时指数退避）与推送指标导出（Prometheus）：等有真实线上数据再定。
- 跨服（BungeeCord/Velocity）统一排队：当前控制器在每个后端进程内独立工作，代理层只负责转发 URL；真正全局排队要在代理插件里做。

### 30.6 审计修复（第 1 轮复审后）

只读审计给出 1 条 P0 + 6 条 P1 + 9 条 P2，逐条处理如下：

| 条目 | 处理 |
| --- | --- |
| P0 退服玩家占窗口 | 控制器新增 playerLeft(player)：在锁内清 inflight / pending / 两个队列；Paper onQuit 与 Fabric onQuit 都调它。 |
| P1-1 回执不校验 sha1 | onStatus 先比在途摘要，不一致只释放窗口并计 dropped，不消耗重试。 |
| P1-2 过滤与实发摘要错位 | 去掉派发时的二次过滤；PackDelivery 新增 push(UUID, String sha1, boolean force) 默认方法，Paper / Fabric 用传入摘要发送并记录 pushedSha1。 |
| P1-3 重试路径重复入队 | pending.add 成功才重排；派发前复查 inflight.containsKey。 |
| P1-4 降窗死代码 | push=0、超时、FAILED / INVALID_URL 三处自增 windowFailures；分母仍是本窗口派发数。 |
| P1-5 未到期 join 阻塞广播 | 拆成 joinQueue / broadcastQueue 两个队列，takeReady 分别看队头。 |
| P1-6 默认流量绕过控制器 | /rps push 的全体分支改为 enqueue；单个目标仍直连 push（一次一人，不构成拥塞）。 |
| P2-1 publisher 双取 | onStatus 先取局部变量再调 fanout；Fabric 的同一处也已改成局部变量。 |
| P2-2 Fabric 回执去重只对 LOADED 完整 | 保留：去重键仍是 (status, hash)，PENDING / FAILED 的重复回执会再进一次状态记录；控制器对无在途任务的回执直接忽略，不产生副作用。 |
| P2-3 Fabric tick 可重入 | ticking 标志的置位覆盖整拍（含锁外的 push 循环），第二拍在锁内直接返回；异常路径在 catch 里复位。 |
| P2-4 Fabric 异步线程读玩家档案 | push 不再在调用线程查玩家；日志与发包一起在主线程执行，玩家已离线时通知控制器 playerLeft 释放窗口。 |
| P2-5 窗口下限越过上限 | floorWindow = min(maxConcurrent, 8)，降窗结果不会超过配置。 |
| P2-6 计数非原子 | stats() 在锁内读取。 |
| P2-7 文档写了不存在的配置键 | 本节改为「固定 5s 重试 / 固定 120s 超时」。 |
| P2-8 config.yml 无末尾换行 | 补回换行。 |
| P2-9 close 后仍可能发一包 | push 前复查 closed；push=0 的回收不再依赖 at 匹配，改为无条件幂等删除。 |

明确保留的一处已知限制：Fabric 的重复回执去重仍以 (status, statuses.hash) 为准，只对 LOADED 完整（P2-2）。

## 31. S11：JVM 对接收尾（2026-11-28）

### 31.1 去掉二次整包 SHA-1

`PackPublisher.build` 原先在合并器返回后再调 `Hashing.sha1Hex(created)`，把刚写好的整包重读一遍。写出现在由 `ZipArchiveWriter` 边写边算（`WriteStats.sha1`），Rust 侧 `finish_reports_real_sha1_and_size` 已锁定「报告摘要 == 文件真实摘要」，因此 JVM 直接取 `result.sha1()` 即可。270 MB 的包省掉一次约 0.1 s 的整包读取。

### 31.2 rawCopied 进合并结果与 /rps status

`Report.rawCopied` 早已由 Rust 写出，但 JVM 没解析。现在 `MergeResult` 增加 `int rawCopied` 字段，`RustPackMerger` 读 `rawCopied`（缺省 0），`summary()` 输出「直拷 N」；`/rps status` 的「合成摘要」直接展示该字符串，无需改命令层。

### 31.3 验证

- `.tmp/build-all.ps1` BUILD SUCCESSFUL，EXIT=0。
- core 21 套件 / 107 项（4 项需 `RPS_PACK_BIN` 跳过）+ paper 2 套件 / 7 项，0 失败；Rust 42 项（writer 7）。
- jar：Paper 2,604,313 B ≤ 2,750,000；Fabric 2,751,810 B ≤ 2,900,000。

### 31.4 审计修复（S11 复审后）

| 编号 | 问题 | 处理 |
| --- | --- | --- |
| P1-1 | 摘要一致性测试只覆盖 deflate/store，未覆盖直拷热路径 | `tests/writer.rs` 新增 `add_raw_participates_in_sha1_and_round_trips`：直拷一条 stored 条目，断言报告摘要等于产物自算 SHA-1、内容可原样读回。 |
| P1-2 | 报告里的 `sha1` 直接采信，自制/旧版二进制会污染快照与 ETag | `RustPackMerger.sha1(...)` 校验 40 位小写十六进制；不合规时记 warning 并用 `Hashing.sha1Hex(output)` 按产物重算。 |
| P1-3 | JVM 测试只断言两次合并摘要相同 | `sameInputProducesSameSha1` 追加两条「报告摘要 == 磁盘文件 SHA-1」断言。 |
| P2-4 | `rawCopied` 与摘要文本零覆盖 | `ConflictReportTest` 用 rawCopied=3 构造结果，断言 `summary()` 含「直拷 3」。 |

保留：`PackHttpServer` 首建前后的 ETag 仍有两个来源（发布后统一走快照值）；
`Hashing.sha1Hex(Path)` 因为成为回退路径而不再是死代码；docs 的「4 项跳过」由
`RustPackMergerTest.binary()` + `Assumptions.assumeTrue` 门控。

## 32. S13：五档复测与 40 万条目档（2026-11-28）

### 32.1 条目上限与基准工具

计划里的「40 万条目」此前被 `scan.rs` 的 `MAX_TOTAL_ENTRIES = 20_000` 挡住，本轮放宽到 `500_000`（`rust/rps-pack/README.md` 同步；约 175MB 元数据预算）。新增两个工具：

- `tools/bench/gen_tiers.py`：生成 micro（1 条 1KB）、small（200 条 JSON）、W1（3 源 270MB / 469 条）、many-400k（4 源 × 100,000 条小 JSON，共 400,001 条）与各自的 `request-<tier>.json`。
- `tools/bench/run_tiers.py`：每档预热 1 次后计时，输出墙钟中位/最小/最大、内部 millis、字节数、条目数、冲突数、直拷数、SHA-1。

### 32.2 写出与内存的三处修正

| 问题 | 现象 | 处理 |
| --- | --- | --- |
| 每条目重开 `Vec` 缓冲 | 40 万条目写出阶段分配开销占大头 | writer 复用 `header` / `central` 缓冲；`local_header` 与 `central_record` 改为 append 到复用缓冲。 |
| 直拷每源一次 `seek(Start)` | 换成 `BufReader` 后 many 档反而 14.2s | `raw_slice` 在 `offset - raw_pos <= RAW_BUFFER` 时用 `seek_relative` 保留 1MB 预读；只有跨大段才重新定位。 |
| 条目元数据与来源标签重复 | many 档峰值 325MB | ① 按来源 `mem::take` 元数据，名字直接移进胜者表；② `Winner.source` 改 `Arc<str>`；③ 条目超过 5 万时扫描后释放全部中央目录，`read`/`sha1` 按需重开并缓存；④ `write_zip` 按值消费胜者表，BTreeMap 节点随写出逐个释放。 |

### 32.3 五档结果（Ryzen 7 9700X / Windows 11 / release，3 次）

| 档位 | 条目 | 产物 | 墙钟中位 | 内部 millis | 峰值 RSS |
| --- | --- | --- | --- | --- | --- |
| micro | 2 | 1,328 B | 15.6 ms | 0 | 启动底噪（CreateProcess ≈13ms） |
| small | 201 | 36,671 B | 16.1 ms | 2 | 同上 |
| W1 | 469 | 270,166,333 B | 239.0 ms | 222 | 12 MB |
| many-400k | 400,001 | 72,720,257 B | 960.4 ms | 942 | **142 MB**（原 325MB） |
| W2（40 源，旧夹具） | 5,063 | 44,053,665 B | 182.3 ms | 163 | 16 MB |

这一组是旧随机夹具的历史记录：W1 与 W2 的 SHA-1 当时与 S11 基线一致（`c7ec9b44…` / `d7106d16…`）。S13 起 `gen_tiers.py` 改用固定种子（`random.Random(7)`），夹具可逐字节重建，SHA-1 仅在同一份脚本下可比。many 档 400,000 条全部直拷，冲突 0。内存目标 ≤150MB 达成，时间目标 ≤1.5s 达成。W2 保留中央目录是有意取舍：5k 条目下重开成本（约 20ms）高于省下的内存。

### 32.4 测试

`tests/scan.rs` 新增 `reader_reopens_after_release_and_reads_same_bytes`：释放中央目录后 `read` 仍能按需重开并返回同样内容；`release_scan_metadata` 之后再次读取同样成立。Rust 共 43 项（lib 4 + merge 25 + mcmeta 2 + writer 7 + serve 1 + scan 4）。

### 32.5 已知边界

- 单条目仍受 64MB 解压上限约束；400k 档的内存上界按「每条目约 350B（名字 + 胜者 + 映射开销）」估算，条目再翻倍需要改用名称 arena 或分片落盘。
- 条目超过 5 万且含大量 `lang`/`sounds` 合并时会触发重开，最坏情况每源多一次中央目录解析；当前实测 W2 规模不触发。

## 33. S13 审计修复（2026-11-28）

### 33.1 两个 P1

- **单视图读取**：`read` / `sha1` 不再依赖重新打开的中央目录，而是直接用扫描期记下的 `(offset, compressed, method)` 走 `raw_slice` + `DeflateDecoder`（store 直读）。`ZipArchive` 只在「扫描时无法直拷的压缩方法（bzip2/zstd 等）」这一条回退路径上才按需重开，且重开后校验 `by_index(index).name` 与扫描结果一致，不一致报「扫描后被改动」。这样扫描到写出之间源 zip 被替换也不会出现「哈希读新文件、直拷读旧句柄」的分叉。
- **大档覆盖**：`tests/merge.rs` 新增 `large_directory_reaches_zip64_and_keeps_every_entry`（70,000 条 stored 条目，条目数超过 65,535 才会真正写出 zip64 EOCD），断言 `report.entries`、zip crate 回读条目数、产物字节含 `PK\x06\x06`、`report.raw_copied == COUNT` 与首尾内容；`tests/scan.rs` 新增 `raw_slice_handles_forward_backward_and_repeated_ranges`，按前跳、回退、同区间重复读、再前跳的顺序逐条比对解压结果，并与 fixture 原文对照。「默认路径不再做 zip 层 CRC 校验，需要时用 `verify_sources`」也记在这里。

### 33.2 P2 处理

| 项 | 处理 |
| --- | --- |
| `raw_pos` 乐观更新 | `RawSlice` 在 `Drop` 时用 `len - Take::limit()` 纯算术回写实际消费量，不额外 seek；`raw_slice` 另加 `debug_assert` 对照 `BufReader::stream_position()` |
| 单条目长度不校验 | `read_capped` / `hash_capped` 增加「声明 N 字节、实际 M 字节」报错；`read_raw` 改传 `Winner.size` |
| 每条目 `Arc::from(&str)` 分配 | `merge()` 预建每源一个 `Arc<str>`，`from_meta` 只 `Arc::clone` |
| 每源 1MB 直拷缓冲 | `RAW_BUFFER` 降到 256KB，200 源约 50MB，不再与 150MB 目标冲突 |
| 每条目一次 `from_utf8_lossy` 分配 | 改为 `std::str::from_utf8` 借用，非法名直接报错（名字本已通过扫描校验） |
| 每层无条件释放中央目录 | 恢复「每 rank 处理完即释放」，与新的读取路径一致；`MANY_ARCHIVE_THRESHOLD` 与 `Body::Raw.index` 随之删除 |
| 条目上限护栏 | 并行扫描改为 `AtomicUsize` 全局计数，在分配条目前就 `fetch_add` 判限；上限 500k（约 175MB 元数据预算），独立单测未加，属配置级护栏 |

### 33.3 复测（release，同机）

| 档 | 条目 | 产物 | 墙钟中位 | 内部 | 峰值 RSS |
| --- | --- | --- | --- | --- | --- |
| micro | 2 | 1,328 B | 17.2 ms | 1 | 2.4 MB |
| small | 201 | 36,671 B | 17.3 ms | 1 | 3.9 MB |
| W1 | 469 | 270,166,333 B | 234.5 ms | 218 | 8.7 MB |
| W2（40 源） | 12,161 | 86,143,428 B | 116.3 ms | 99 | 24.6 MB |
| many-400k | 400,001 | 72,720,257 B | 879.2 ms | 862 | **158.1 MB** |

测量方法：`tools/bench/gen_tiers.py` 生成夹具（W2 = 40 源 × (300 JSON + 4 × 512KB)，名称按源区分，因此 40 源累计 12,161 条、无同名冲突）；`tools/bench/run_tiers.py` 每档预热 1 次取 3 次中位，墙钟为 Python `perf_counter`（不含工具自身的 40–50ms `Start-Process` 开销），峰值 RSS 由 `psapi.GetProcessMemoryInfo`（Windows）/`VmHWM`（Linux）每 1ms 轮询取最大值（15ms 级的 micro/small 只有启动底噪，不代表稳态；取不到时输出 `null`）。many 档 400,000 条全部直拷、冲突 0；峰值比 §32.3 的 142MB 高，原因是读取路径改为按需解压后，写出阶段同时持有胜者表与 writer 的中央目录条目，仍在 200MB 预算内。夹具由 `gen_tiers.py` 用固定种子重建（条目时间戳固定为 2024-01-01），连续两次生成的 zip SHA-256 相同；旧 §32.3 的随机夹具数值只作历史对照。

### 33.4 验证

Rust 48 项全绿（lib 5 + merge 26 + mcmeta 2 + writer 7 + serve 1 + scan 7），无编译警告；`.tmp/build-all.ps1` EXIT=0，core 21 套件 / 107 项（4 项需 `RPS_PACK_BIN` 跳过）+ paper 2 / 7；jar 体积 Paper 2,600,361 B、Fabric 2,747,858 B，均低于守卫上限。

## 34. S12 平台 I/O：评估后保持默认（2026-11-28）

S12 原计划给每个平台加专有 I/O 原语（Windows `FILE_FLAG_SEQUENTIAL_SCAN`、Linux `copy_file_range` / `fadvise` / `fallocate`、macOS `fcopyfile`）。本轮先用 A/B 实测 Windows 侧还能不能靠提示位再榨出一点，再用「关掉 SHA-1」的对照定位 W1 的真实瓶颈；结论是**提示位无收益、瓶颈不在 I/O 而在哈希，代码保持原样，另立 S14 做写与哈希的重叠**。

### 34.1 现状

- 读取：`scan.rs` 的 `open_source` 对 ≥8 MB 的来源已带 `FILE_FLAG_SEQUENTIAL_SCAN`（中央目录句柄）。直拷句柄走普通 `File::open`。
- 写出：`writer.rs` 用 `BufWriter::with_capacity(1 MiB)` + `write_all` 顺序写，1 MiB 直拷缓冲 + 256 KiB 读取缓冲。
- W1 一进一出 540 MB / 235 ms 折算约 2.3 GB/s，但这不是「I/O 到顶」的证据：同一线程里 `write_all` 之后紧接着 `hasher.update`，270 MB 的单趟 SHA-1（本机实测约 2.7 GB/s，即约 100 ms）与用户态拷贝都在同一临界路径上。见 34.4 的关闭哈希对照。

### 34.2 A/B 方法

两个只差这两处提示位的二进制（同一源码树、同 release 配置、LTO）：

- base：HEAD（`678377c`）原样。
- hint：直拷句柄改用 `open_source`（大文件带顺序提示）+ 输出文件 `OpenOptions` 带同一提示位。

`.tmp/ab/ab.py` 对 w1 / w2 / many 三档各跑 10 次运行（base / hint 各 5 次，奇偶轮交换先后），取墙钟中位；产物按 `ab-<tier>-<key>.zip` 写在夹具目录里，避免互相覆盖。测量条件是**重复运行的热缓存**：同一夹具连续跑，`FILE_FLAG_SEQUENTIAL_SCAN` 的预期收益在冷缓存首次读，本轮的结论只覆盖热缓存，冷缓存与首次构建未测。原始脚本、base / hint 两个二进制（另有一个仅实验用的 hash-off 构建，见 34.4）与两份中位数记录都在 `.tmp/ab/`（临时目录，不进仓库）；逐次原始样本未留存，表中是当轮记录的中位，跨会话漂移见 34.3 的说明。

### 34.3 结果（release，同机交错）

| 档 | base 中位 | hint 中位 | base 内部 | hint 内部 |
| --- | --- | --- | --- | --- |
| W1 | 250.6 ms | 251.9 ms | 233 ms | 234 ms |
| W2（40 源） | 127.5 ms | 131.9 ms | 108 ms | 110 ms |
| many-400k | 941.9 ms | 959.9 ms | 924 ms | 939 ms |

热缓存下提示位没有可测收益：三个档的差异（+0.5% ~ +3.4%）小于同一台机器跨会话的整体漂移——§33.3 的内部耗时为 218 / 99 / 862 ms，本轮为 233 / 108 / 924 ms，三档同比例上浮 7% ~ 9%，因此不能判定提示位更慢，只能判定「测不出收益」。原因也说得通：直拷阶段本就按中央目录顺序推进，内核预读已在最优状态；给输出句柄加读提示位没有语义。

### 34.4 关掉 SHA-1 的对照：瓶颈不在 I/O

为了区分「I/O 到顶」和「哈希/拷贝到顶」，另外编了一个实验二进制：在 `emit` 里加一个仅实验用的 `RPS_NO_HASH` 开关，跳过 `hasher.update`，其余代码、release 配置、LTO 完全相同（报告里的 sha1 会退化成空串的 `da39a3ee…`，正好可确认开关生效；这个开关不会进产品代码）。同样三档各 5 次交替：

| 档 | 正常（带 SHA-1） | 关闭 SHA-1 | 差值 |
| --- | --- | --- | --- |
| W1 | 238.8 ms | 134.3 ms | **−104.5 ms（−44%）** |
| W2（40 源） | 149.5 ms | 123.1 ms | −26.4 ms（−18%） |
| many-400k | 879.5 ms | 933.9 ms | 落在噪声内（元数据/写入主导） |

这一组和 34.3 不是同一构建：hash-off 二进制自身的基线是 238.8 / 149.5 / 879.5 ms，只取同一二进制内的差值（W1 −104.5 ms、W2 约 −26 ms），不要和 34.3 的 250.6 / 127.5 / 941.9 ms 直接换算百分比。

结论很直接：W1 的 270 MB 单趟 SHA-1 约 100 ms，正好是总时间的两成多到四成；W2 的 86 MB 约 26 ms；many 档由 40 万条元数据与写入主导，哈希占比可以忽略。所以 **W1 不是 I/O 到顶，而是「写 + 哈希」串行**：`writer.rs` 的 `emit` 先 `write_all` 再 `hasher.update`，两段都压在同一线程的临界路径上。可行的下一步是把哈希放进独立线程、用小块环形缓冲与写入重叠（顺序不变，SHA-1 仍覆盖每个字节），预期 W1 从 235 ms 降到接近 150 ms；这一项作为 **S14** 单独立项，不在本轮改。

### 34.5 决定

- 两处提示位改动已回退，`git diff` 为空；不引入平台专有代码，也不再增加 I/O 提示位。热缓存下测不出收益，冷缓存未测——保持默认是保守选择，不等于冷缓存也没有收益。
- **Linux / macOS 路径不做**：`copy_file_range` / `fcopyfile` 只能在两个 fd 之间搬字节，而 zip 的 SHA-1 必须覆盖写出的每一个字节（单趟哈希是本项目的核心收益之一），走内核零拷贝还得再读一遍补齐哈希，省不下来；`fadvise` / `F_NOCACHE` 属提示级，收益与 Windows 同类。本机没有 Linux 环境，CI 的共享 runner 只能做构建与功能验证，给不出可信的「<10% 收益」判定，按「不写无法验证的路径」处理。
- **§22 的 S6「非临时写」与 §2 的 1 GB 单条目标**：据此降级为待办（descoped），本轮不再追（1 GB 单条中的 stored 直拷已由 §38 交付；非临时写仍 descoped，≥4 GiB zip64 与 mmap 经 §39 评估不做）；同一份 I/O 预算优先给 S14 的哈希重叠，因为后者有 34.4 的实测支撑。
- 需要时仍有 `RPS_TRACE=1` 打印 `scan=<ms>` 与写入前的总耗时，可以判断瓶颈在扫描还是写出。

### 34.6 仍然清楚的边界

- `verify_sources` 打开时，每个直拷条目会**多解压一遍做 CRC 校验**；写出本身仍是压缩字节直拷（`writer.add_raw` 内没有解码器），用于怀疑来源被改动的排障场景，默认关闭。
- 条目上限 500k（约 175 MB 元数据）；超过会直接报错而不是 OOM。
- 输出仍是单线程顺序写；SHA-1 自 S14 起在独立线程与写出重叠（见 §35）；并行 deflate 只在「需要重新压缩的条目」上有意义，当前收益不足以抵消复杂度和体积（见 §17、§18）。

## 35. S14：哈希与写重叠（2026-11-28）

§34.4 的关哈希对照把 W1 的约 100 ms 定位在 `writer.rs` 的 `emit`：`write_all` 之后紧接着 `hasher.update`，两段串行。本轮把 SHA-1 挪到独立线程，写出与摘要并行，字节与顺序不变。

### 35.1 实现

- `HashSink` 两种形态：`Threaded`（默认）与 `Inline`（`RPS_SYNC_HASH=1`，对照/排障）。
- 线程 `rps-pack-hash` 用 `sync_channel` 收 `(块, 已填长度)`，`hasher.update` 后把块还回回收通道；`HASH_BLOCK = 1 MiB`、`HASH_QUEUE = 4`、`HASH_BLOCKS = 6`；峰值是「在途 1 + 队列 4 + 哈希线程持有 1 + 回收池 6」共 12 个 1 MiB 块，额外内存上限约 12 MiB。线程模式下不再分配直拷用的 `scratch`。
- 直拷路径零拷贝：`add_raw` 直接把来源字节读进哈希块尾部（`spare()`），`write_all` 写同一片字节后 `advance()`，块满即交；头、中央目录、重压条目走 `push()`（拷进块）。
- 提交顺序 = 单写者顺序，哈希线程单消费者按同序 `update`，摘要与逐字节同步计算一致；`finish()` 先冲掉尾块再 join。
- 线程起不来时退回同线程（字节与摘要不变）；线程中途退出则报「哈希线程提前退出，无法继续计算 SHA-1」。

### 35.2 结果（`tools/bench/run_tiers.py`，release，5 轮、每轮每档 3 次取中位，同机）

| 档 | 墙钟中位 | 内部 | 峰值 RSS | 对比 S13 |
| --- | --- | --- | --- | --- |
| W1（270 MB / 469 条） | 138.4–159.7 ms | 122–142 ms | 12.2–13.3 MB | 234.5 ms → 约 −35% |
| W2（40 源 / 12,161 条） | 87.7–97.1 ms | 70–78 ms | 25.5–27.8 MB | 116.3 ms → 约 −20% |
| many-400k | 896.9–1379.2 ms | 876–1360 ms | 158.1–158.2 MB | 与 S13 同量级；方差受后台负载影响大 |
| micro / small | 15.9–20.0 / 17.7–20.0 ms | 1–2 ms | 2.4–6.4 MB | 不变（进程启动主导） |

三个产物的 SHA-1 与 S13 完全一致（W1 `cc8476da…`、W2 `e46c8f53…`、many `d9bf6ff1…`）；`RPS_SYNC_HASH=1` 跑 W1 得到同一 SHA-1、内部 206 ms，证明两种模式逐字节等价。峰值 RSS 比 S13 高约 3–4 MB（哈希块池；`scratch` 在线程模式下已不再分配），仍在 §18 预算内。many 档受后台负载影响最大，单轮中位在 900 ms 到 1.4 s 之间波动，判断只看 W1/W2 这类大字节量档。

测量命令：`RPS_PACK_BIN=rust/rps-pack/target/release/rps-pack.exe python tools/bench/run_tiers.py .tmp/bench-s13`；机器为 Ryzen 7 9700X / Windows 11 / Rust 1.100 release。夹具由 `tools/bench/gen_tiers.py` 固定种子生成、约 328 MB（在 `.tmp` 下、不入仓），这些数字不可由 CI 复现；换机比较请用同机同夹具的前后两版。

### 35.3 测试

Rust 52 项（lib 7 + merge 26 + mcmeta 2 + writer 9 + serve 1 + scan 7）。新增：`threaded_hash_matches_inline_across_block_boundaries`（跨块摘要等于同步哈希）、`threaded_hash_advance_matches_after_zero_copy_fill`（零拷贝填充路径）、`large_entry_crosses_hash_blocks_and_keeps_sha1`（3 MiB 单条目回读，报告 SHA-1 等于磁盘文件）、`raw_copies_cross_hash_blocks_at_exact_boundaries`（直拷 1 MiB−1 / 1 MiB / 1 MiB+1 / 3 MiB 四种长度，覆盖块前一字节、整块、块后一字节与多块，断言报告 SHA-1 等于磁盘文件且四条内容逐字节回读一致）。

### 35.4 边界

- 写出仍是单线程顺序（zip 是流式格式），并行只发生在「写」与「算哈希」之间。
- `many` 档由 40 万条元数据主导，收益在噪声内；S14 只针对大字节量。
- 多 1 个线程、约 12 MiB 缓冲；micro / small 实测无回归。
- `RPS_SYNC_HASH` 是排障开关，不是配置项。

## 36. S10b：HTTP 出口并发与限速（2026-11-28）

### 36.1 实现

- `PackHttpServer` 新增 6 参构造（`maxConcurrent`、`egressBytesPerSecond`），原 4 参保留并委托（64 / 不限）。
- 并发窗口：`Semaphore` 公平锁，槽位与线程池都按 `min(max-concurrent, 256)` 建；`tryAcquire(100 ms)` 失败直接 `503` + `Retry-After: 1`；槽位覆盖整个响应体，`finally` 释放；304/404/405 不占槽。
- 全局限速：按字节预约发送时刻，锁内只推进 `nextFreeNanos`，锁外 `TimeUnit.NANOSECONDS.sleep`，不把 HTTP 线程串成一条；实际少发（客户端提前断开）时 `refund` 退回额度。
- ETag 按「路径 + 大小 + 修改时间」缓存，命中时不再回读整包算 SHA-1。
- 配置：`delivery.http.max-concurrent`（默认 64，1–256）、`delivery.http.egress-kbps`（默认 0 = 不限，0–10,000,000）；Paper `startDelivery` 与 Fabric `FabricPlatform` 都从 `RpsConfig` 取值。
- 限速开启时推送在途超时从 120 s 放宽到 1 小时，避免慢速下载被当成失败回收槽位。
- `force` 语义收口：`PackPublisher` 与 `/rps push` 全体路径带真实 SHA-1 + `force = true` 入队，跳过「已加载同版本」过滤但仍记录摘要，迟到的旧代回执只释放槽位、不消耗重试。

### 36.2 测试

- `PackHttpServerTest.servesWhenEgressLimitIsEnabled`：限速开启仍能完整下载。
- `PackHttpServerTest.egressLimitPacesTheBody`：96 KiB 走 128 KB/s，断言耗时 ≥ 400 ms。
- `PackHttpServerTest.answers503WhenConcurrentSlotsAreExhausted`：8 MiB 文件 + 单槽 + 慢读客户端占住槽位，第二个请求得到 503 与 `Retry-After: 1`；断开后同一出口恢复 200。
- `PackHttpServerTest.etagFollowsThePublishedFile`：旧 ETag 不能换来 304，正文换成新包的内容。
- `FanoutControllerLifecycleTest.unconditionalPushIgnoresLoadedState`：普通推送跳过已加载玩家；`force` 带真实摘要重推并让下发器看到 `force`；旧代回执只释放槽位。

### 36.3 边界

- 限速是进程级总出口上限，不区分玩家/地址，也没有缓冲：预约按字节线性排队，适合「总带宽封顶」而不是精细 QoS。
- 503 只在没有空闲槽位时出现，客户端会按下载失败处理并计入一次重试；调大窗口或降低并发下载数可避免。
- 槽位等待 100 ms 是硬编码，不随配置变化；线程池按「窗口 + 8」建，窗口上限 256，因此最多 264 条。
- 关服时在途连接只收到中断标志，不中止传输：限速随之失效、连接继续跑到客户端断开，但 daemon 线程不会拖住关服。
- 客户端提前断开时按已发送字节退款，日志为 FINE 级别；`refund` 的分支目前只有代码审查覆盖，没有独立用例。
- ETag 缓存以大小 + 修改时间为准；同一毫秒内等长改写理论上会命中旧摘要（发布路径总换文件，实际不会遇到）。
## 37. 状态页显示体积比（2026-11-28）

### 37.1 实现

- `MergeResult` 增加 `rawBytes`（去重前各来源条目体积之和，由 Rust 报告的 `rawBytes` 解析），放在 `rawCopied` 之前。
- `summary()` 在「直拷 N」之后追加「（原始 X 字节，体积 Y%）」，Y = 产物字节 / rawBytes × 100 取整；`rawBytes` 为 0（外部合并器不报）时整段省略。
- `/rps status` 直接打印 `summary()`，因此状态页能看到压缩率与直拷比例，不需要额外字段。

### 37.2 测试

- `ConflictReportTest` 三处构造器补 `rawBytes`，其中一例用 `rawBytes = 1000`、`bytes = 120` 断言摘要含「体积 12%」。

### 37.3 边界

- 百分比按「去重前体积」算，含被覆盖条目，因此它是压缩率的保守下限，不等于「相对最大来源」的比例。
- 外部/旧版 `rps-pack` 不报 `rawBytes` 时退化为不显示，不影响其它字段。

## 38. 大条目直拷（2026-11-28）

### 38.1 动机与边界

- 此前任何单条目解压后超过 64 MiB 都直接失败；高清材质包里 100 MB 级 atlas 很常见。
- 直拷路径只搬字节，不需要整条进内存，因此 stored 条目上限放宽到 1 GiB（`MAX_RAW_ENTRY_BYTES`）。
- 需要解码的路径（deflate 重压、JSON 合并）仍受 64 MiB（`MAX_ENTRY_BYTES`）约束；冲突判定走 SHA-1，不读整条内容。
- 扫描期判定：`size > 1 GiB` 拒绝；`size > 64 MiB` 且不是可直拷的 stored 条目也拒绝，报错写明「只有 stored 条目能直拷」。

### 38.2 实现

- `scan.rs`：新增 `pub const MAX_RAW_ENTRY_BYTES: u64 = 1024 * 1024 * 1024`；上限检查按 `copyable`（stored 且 `data_start()` 可得）分两路：直拷 1 GiB、其余 64 MiB。`read_capped` 仍按 `MAX_ENTRY_BYTES + 1` 截断（结果会整条进内存）；`hash_capped` 增加 `cap` 参数，stored 流式读到 1 GiB、deflate 仍 64 MiB（64 KiB 缓冲，只算 SHA-1）。
- `engine.rs`：`verify_raw` 的 stored 分支按 `MAX_RAW_ENTRY_BYTES` 流式计算 CRC，deflate 分支保持 64 MiB；`body_bytes`（仅 JSON 合并用）对超过 64 MiB 的条目直接报错，不会把整条读进 `Vec`。
- 写出端的 `add_raw` 保持流式；新增 stored 条目 `compressed == uncompressed` 的一致性检查，避免未来调用方写出坏条目。

### 38.3 测试与复测

- `tests/scan.rs` 新增 `large_stored_entry_is_copyable_while_large_deflated_entry_is_rejected`：64 MiB + 1 的 stored 条目被接受且 `raw_method = Some(0)`，同样大小的 deflated 条目报「只有 stored 条目能直拷」。
- `tests/merge.rs` 新增 `large_stored_entry_is_raw_copied_end_to_end`：64 MiB + 1 字节的 stored 条目端到端合并，报告 `raw_copied = 1`，回读全部字节校验内容，并再以 `verify_sources = true` 跑一遍覆盖流式 CRC 分支。
- Rust 54 项全绿：lib 7 + merge 27 + mcmeta 2 + writer 9 + serve 1 + scan 8。
- 五档复测（release，单轮 3 次取中位）：micro 16.2 ms / small 17.0 / W1 141.7（内部 121，RSS 14.2 MB）/ W2 92.4（73，27.5 MB）/ many 916.5（899，158.1 MB）；W1/W2/many 的 sha1 与 §35 完全一致。

### 38.4 已知边界

- 单条超过 1 GiB 的 stored 条目仍拒绝；单条 ≥4 GiB 的 zip64 直拷未做，理由见 §39.3。
- 可合并 JSON（`lang` / `sounds.json` / `font` / `atlases`）若单条超过 64 MiB，会在 `body_bytes` 直接报错并让整次合并失败（实务上不会出现；需要时再改成 warn + 覆盖降级）。
- 开启 `verify_sources` 时大 stored 条目会多读一遍算 CRC（流式，内存不受影响，耗时随字节数增加）。
- JVM 侧无改动：合并仍由 `rps-pack` 完成，插件只消费报告。

## 39. 剩余加码项评估（2026-11-28）

加码清单收尾时还剩三项：源索引 LRU、单条 ≥4 GiB 的 zip64 直拷、mmap 源读取。三项都先做成本/收益判定，结论都是不做，代码不变，理由记在这里。

### 39.1 扫描阶段到底占多少

`RPS_TRACE=1` 打印的 `scan=` 与端到端墙钟（release，`.tmp/bench-s13` 夹具，各 3 次）：

| 档 | scan | 端到端 | 扫描占比 |
| --- | --- | --- | --- |
| W2（40 源 / 12,161 条） | 20–21 ms | 92 ms | 约 23% |
| many（4 源 / 400,001 条） | 548–734 ms | 916 ms | 60–80%（中位 60%） |

表注：scan 是 3 次运行的区间（W2 21/20/21 ms，many 548/551/734 ms），端到端取 §38.3 的中位 92/916 ms、未重测，百分比按行内两个中位数相除（scan 与端到端非同一轮），只作量级参考；外部 bash 计时比表内高约 40 ms（进程启动 + shell 开销），表内为 run_tiers 口径。

源索引 LRU 的天花板就是这一列：W2 最多省 20 ms，many 最多省 550–650 ms（扣除缓存读回成本后按 400–500 ms 估），而且只在「多源里少数源变化」时成立（S8 的指纹短路已经把「什么都没变」挡在前面了）。

### 39.2 源索引 LRU：不做

- 缓存体量：400k 条 `EntryMeta`（64 B 结构 + 每条约 35 B 名称）约 45 MB，超过 §21.2（:548）定的 32 MB 上限；压到 32 MB 只能覆盖约 28 万条，many 档约七成命中。
- 加缓存后每次命中都要从缓存交出一份 owned 副本（`engine.rs:503-507` 目前把 `meta.name` 直接 move 进 `BTreeMap`；插入缓存后这一步只能变成克隆），400k 条约 40–60 ms，直接吃掉大半收益。
- 失效的代价不对称：索引里存的是 `data_start()` 偏移，源 zip 被原地改写而 size/mtime 未变（或落在同一时间戳粒度内）就会直拷出错误字节；这类错误不会报错，只会产出坏包。S8 的构建指纹能挡住「整批没变」，但挡不住「缓存索引与这一版源文件不同步」。
- 真实部署是「一两个包、几千条」，省下的是 20 ms 级；构建本来就只在内容变化后由后台线程跑一次。

若将来真要解决多源重复扫描，优先做 §11.3 的紧凑二进制索引 + 版本号 + 原子替换，而不是先上内存 LRU；届时要一并解决上面的克隆成本。

### 39.3 单条 ≥4 GiB 的 zip64 直拷：不做

- writer 在 `uncompressed` 或 `compressed` ≥ `U32_MAX` 时即拒（`writer.rs:188-191`，实际是 4 GiB − 1 字节就报错），要支持得先造 4 GiB 以上的 stored 条目才能验证，夹具要占 8 GB 级磁盘且每次复测几十秒；CI 更不现实。
- 写出侧要改本地头（zip64 extra 记录 64 位 size）与 `add_raw` 的边界判断，属于直拷主路径；没有可重复验证的用例就动主路径，风险高于收益。
- 产品侧没有 4 GiB 单条的场景：4K 材质包的 atlas 是 100 MB 级，§38 已经覆盖到 1 GiB。超限时仍报明确错误，用户能看懂。

### 39.4 mmap 源：不做

- W1 现在 140 ms，其中 SHA-1 已由 S14 挪到独立线程；§34.3 按 540 MB / 235 ms 折约 2.3 GB/s（该数字不能外推到 140 ms 的基线），但读过之后没有额外用户态往返，说明瓶颈不在 read 的系统调用与拷贝。
- §34 的 A/B 已经证明「同一份源码加顺序访问提示位」量不出收益；mmap 只会换掉读取方式，不改变 zip 解压与写出的字节流。
- 代价是 `memmap2` 依赖、Windows 上文件被替换后映射仍指向旧文件（与 `scan.rs` 现有的「扫描后源被改动」条目名复核语义冲突；该检查只比条目名、不比字节，文件被整体替换而条目名不变时不会报错，正是映射指向旧文件的最坏情形）、以及映射页计入工作集后的 RSS 统计口径变化。

### 39.5 收口

至此加码清单的每一项都有结论：S10b（§36）、体积比（§37）、大条目直拷（§38）已交付；源索引 LRU、≥4 GiB zip64、mmap 源三项在本节按「不做」收口；非临时写仍按 §34.5 descoped。§22 的 S6/S8 两行已同步标注。§2 与 §5/§7/§11/§16 里仍保留 mmap、索引缓存这些当初的设计手段与待办勾选框，未落地部分以本节为准（§7:414 已改「不用」，§16 P3/P4 已标 descoped）。


## 40. 全面审查（2026-11-28，18 提交）

### 40.1 Rust：来源身份与原子输出

复审发现两处「默认配置下可能静默产出坏包 / 失败后留下坏文件」的问题，均已在 rust/rps-pack 修掉：

| 问题 | 修法 |
| --- | --- |
| 扫描后来源被整体替换，直拷仍用旧偏移，产物与报告不一致且不报错 | SourceReader 在 open 时记下文件大小与修改时间，写出前调用 check_unchanged()；不一致就报「来源 … 在扫描后被改动」并放弃写出 |
| stored 条目内容被改而 CRC 不校验（add_raw 直接信任中央目录） | 直拷 stored 时顺流计算 CRC-32，与扫描期记录值比对，不符报「CRC-32 不符」 |
| 写出失败或进程中断会在 output 路径留下半成品 | 先写同目录临时文件，成功 rename 原子替换；TempOutput 的 Drop 在失败路径清理临时文件 |

另外把「stored 声明长度不一致」从静默降级改成扫描期报错——降级后走 deflate 读同样会失败，早报错更清楚。

### 40.2 JVM：回执配对、会话冷却与调度收尾

| 位置 | 问题 | 修法 |
| --- | --- | --- |
| Paper PackPusher.onStatus | 回执 SHA-1 取「该玩家最近一次推送」，旧包迟到回执会错配新槽位 | 优先用 event.getHash()（客户端报告的实际包），为空才回退 |
| Fabric FabricPackDelivery | 同上，且 mixin 只转 action | 每次推送生成 push id 并存 pushId → sha1，mixin 转 packet.id()，回执按 id 取 |
| RustPackServe | 一次瞬时失败永久拉黑常驻会话，无日志 | 冷却 60 秒，期间走一次性进程；首次失败打一行告警，成功后清除 |
| FanoutController.tick | 后半段无 finally，异常会让 ticking 永真、队列停摆 | 整个 tick 包在 try/finally，结束统一复位 |
| PackPublisher.fanout() | supersede / disable 后仍可新建调度器 | 先查 active，不活跃直接返回 null |
| SourceWatcher.start() | 首次变动不受防抖约束，半写入的包会被立即合并 | 启动时把 lastFireAt 置为当前时间 |
| RustPackMerger | 报告缺 bytes/entries 时抛 NPE | 显式检查并抛 MergeUnavailableException |

### 40.3 HTTP 出口与推送队列（复审第二轮）

| 位置 | 问题 | 修法 |
| --- | --- | --- |
| FanoutController | 换代时新任务被 pending 去重或「已有在途」判定直接丢掉，发布新包/群体推送对部分玩家静默失效 | pending 改成 Map<UUID, Task> 只留最新一代；队列里的旧项按 seq 丢弃；上一代未回执时把新任务延后 retryDelayMs 重排，不丢 |
| PackHttpServer.etagFor | 冷启动并发请求各自把整包读一遍算 SHA-1 | 按文件键的 CompletableFuture single-flight，同键只算一次，其余等待结果 |
| PackHttpServer 线程池 | 无界队列让超限连接排队而不是 503，慢客户端可绕过准入 | 有界队列 512 + 拒绝计数；拒绝由 JDK HttpServer 关闭连接，rejectedRequests() 可观测 |
| If-None-Match | contains 子串匹配，且不识别通配符 | 按 RFC 9110 拆逗号、去 W/ 前缀、支持 * |
| sha1Of 失败 | 会发出 ETag: "" | 空摘要不发 ETag 头 |
| 传输异常 | 已预约的令牌不退 | transferTo 抛错时 refund 当次预约 |
| FanoutController.push | 恒传 force=true，任务里的 force 没用上 | 传 task.force；两平台 push 仍忽略 force，强制语义只在入队过滤层生效 |
| FanoutController.onStatus | 旧代的 FAILED 回执会把 pending 换成旧代重试，挤掉已排队的新代；迟到的 LOADED 回执还会清掉新代槽位 | 队列里有新代时只计 dropped 并返回；不一致的迟到回执不再清当前代槽位 |
| Fabric pushId 记账 | pushedSha1 按 playerId 写、按 pushId 读，永远 miss、回退全局摘要 | 按 pushId 写、终态回执后删除，另存 lastSha1ByPlayer 作回退；close 清空两张表 |
| FanoutController.pushed | 派发即累加，平台拒收时又计 dropped | 只有 push 返回正数才计成功 |
| etagFor 键计算 | Files.size / getLastModifiedTime 抛 IOException 会断开连接 | 捕获后按「本次不发 ETag」处理 |
| ETag 缓存 | 摘要计算失败（空串）也写缓存，该版本此后一直不发 ETag | 空摘要不写缓存 |
| /rps push 单人、无调度器时的直推 | 不占并发窗口、不做去重 | 已知边界：单飞只覆盖 FanoutController 的批量路径，注释与本节写明 |
| check_unchanged 的粒度 | 只比大小与修改时间，且只在写出前查一次 | 已知边界：同大小同时间戳的就地改写检测不到；硬杀或 panic=abort 可能留下同目录 .tmp（不自动清扫、rename 前不 fsync） |
| 令牌桶退款 | transferTo 抛错时按整块退，可能多退已写出的部分 | 已知边界：最多多退一个 64 KiB 块，方向是放宽限速，不会超发 |


### 40.4 验证

- Rust：56 项（lib 7 + mcmeta 2 + merge 27 + scan 9 + serve 1 + writer 10），新增 check_unchanged_detects_replaced_source、stored_crc_mismatch_is_rejected。
- JVM：core 21 个套件 / 115 项（4 项需 RPS_PACK_BIN），paper 2 / 7；全量构建 BUILD SUCCESSFUL。
- jar 守卫：Paper 2,635,331 B ≤ 2,750,000；Fabric 2,783,000 B ≤ 2,900,000。新增 failedRetryDoesNotDisplaceQueuedGeneration，并把两处「迟到回执释放槽位」的断言改为「不清槽位、只计 dropped」。
