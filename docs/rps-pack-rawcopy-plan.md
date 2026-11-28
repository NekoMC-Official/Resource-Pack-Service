# rps-pack 原始字节搬运重构（P0）实施计划

> 状态：已实施（2026-11-28）｜分支 `main`｜本文件保留作设计对照，最新实测见 `docs/rps-pack-maxperf-plan.md`。
>
> **2026-11-28 补注**：本计划已按 S0–S7 实施完毕（提交 `3c8772a` 起），实测与后续切片见 `docs/rps-pack-maxperf-plan.md` §23–§35；文中「预期」数字保留作对照。

---

## 0. 一句话

把 `rps-pack` 从「全量解压 → 内存合并 → 全量重压」改为「**扫中央目录 → 元数据决策 → 原始压缩字节搬运**」，只对 `lang/sounds/font/atlases` 四类需要深度合并的 JSON 解压重压。

预期（同机同输入，单进程墙钟）：

| 负载 | 现状 | P0 完成目标 | 提升 |
| --- | --- | --- | --- |
| W1 超大（270 MB / 469 条） | 4.07 s / 547 MB | **0.25–0.40 s / 15–30 MB** | 10–16× / 20–36× |
| W2 超多（84 MB / 5,063 条） | 0.97 s / 100 MB | **0.55–0.75 s / 10–20 MB** | 1.3–1.8× / 5–10× |

工期 4.5 人日，8 个切片 S0–S7，每片有独立验收命令与回滚点。不改请求/报告主契约，不引入新依赖。

---

## 1. 范围与不变量

### 1.1 范围内

1. 新增 `scan.rs` / `resolve.rs` / `write.rs` / `report.rs` 四个模块，`engine.rs` 退化为编排层。
2. 删除 `archive.rs` 的 `read_source`（整包解压）、`crc.rs`（改用中央目录 CRC）、`Blob`（大缓冲）。
3. 原始字节拷贝使用 `zip` 9.8.2 的 `raw_copy_file_touch`。
4. 输出 sha1 改为写后流式读，不再整包入内存。
5. JVM 侧复用合并器返回的 sha1，去掉 `PackPublisher.kt:193` 的二次整包哈希。
6. 全部现有 20 个 Rust 集成测试与 93 项 JVM 单测保持绿；新增约 12 个 Rust 测试。

### 1.2 范围外（明确不做）

- 并行 deflate / 自写 zip writer / 分片合流（依据 `docs/optimization-research.md` §2：raw copy 后压缩不再是瓶颈）。
- HTTP Range、合并器常驻进程、mimalloc（P2，另立计划）。
- 报告字段的破坏性变更（只允许 P0-2 的追加字段 `loserSha1Count`）。
- 任何 Paper/Fabric 行为变更；本重构只动 `rust/rps-pack` 与 `PackPublisher` 的一处冗余哈希。

### 1.3 必须保住的语义（逐条有锚点）

| # | 语义 | 现状锚点 | 覆盖测试 |
| --- | --- | --- | --- |
| 1 | 条目名安全闸（空/绝对路径/`..`/盘符/NUL 拒绝） | `rust/rps-pack/src/archive.rs` `validate_entry_name` | `zip_slip_entries_are_rejected` |
| 2 | 单条目解压后 ≤64 MB、单源与累计条目 ≤20,000 | `archive.rs` `MAX_ENTRY_BYTES` / `MAX_TOTAL_ENTRIES` | 新增 |
| 3 | `pack.mcmeta` 抽出、格式区间交集、`supported_formats`（format<65） | `mcmeta.rs` / `engine.rs` | `format_intersection_empty_fails`、`missing_mcmeta_*` |
| 4 | 层序 `(priority, index)`，同 priority 按请求顺序 | `engine.rs` `order.sort_by_key` | `three_layers_accumulate_losers` |
| 5 | 内容去重（CRC+大小+SHA-1 全同） | `engine.rs` `insert` | `identical_entries_are_deduped`、`same_size_different_content_is_not_deduped` |
| 6 | 同来源重名：后写覆盖，不记冲突 | `previous.layer == rank` 分支 | 新增 |
| 7 | `pack.png`（Metadata 类）静默覆盖 | `AssetKind::Metadata` 分支 | `pack_icon_comes_from_highest_layer` |
| 8 | 四类 JSON 合并：sounds/lang 深合并，font/atlases 高层在前拼接去重 | `jsonmerge.rs` `kind_of` | `lang_json_is_deep_merged`、`font_arrays_are_concatenated_high_first` |
| 9 | JSON 语义冲突字段 ≤50 条、每条 ≤120 字符 | `jsonmerge.rs` `MAX_FIELDS` | `json_conflict_fields_are_capped`、`font_same_id_reports_json_conflict` |
| 10 | `policy` override / first / strict | `engine.rs` `insert` | `strict_policy_fails_on_path_conflict`、`first_policy_keeps_lower_layer` |
| 11 | 冲突结构：winner/losers/priorities/samePriority/mode/severity | `ConflictState` | `same_priority_conflict_is_flagged` |
| 12 | 明细 ≤5,000 条，`conflictSummary` 统计全部 | `MAX_CONFLICT_DETAILS` | 新增 |
| 13 | 命名空间共用警告 | `namespace_warnings` | 新增 |
| 14 | 输出按名称升序、时间戳 1980-01-01、权限 0644、重压条目 level 6 | `write_zip` | `single_source_passthrough`（:153–163） |
| 15 | 相同输入两次 → 相同 sha1 与相同字节 | `write_zip` + 报告 | `same_input_twice_produces_same_sha1` |
| 16 | 报告字段名与类型（Kotlin 解析契约） | `model.rs` `Report` | `RustPackMergerTest` |
| 17 | 失败形状：exit 2 + stdout `{"ok":false,"error":...}` | `main.rs` `fail()` | Java 侧 `MergeUnavailableException` |

---

## 2. 现状锚点与成本归因

### 2.1 数据流（现在）

~~~
archive.rs:read_source   逐条目 read_to_end → Vec<u8>，峰值 = 全部解压字节
engine.rs:merge          BTreeMap<String, Blob{bytes, crc, size, sha1, layer, source, priority}>
engine.rs:write_zip      逐条目 write_all(&bytes) → ZipWriter 现场 deflate(level 6)
engine.rs (结尾)         std::fs::read(output) 整包读回算 sha1（第二个内存峰）
PackPublisher.kt:193     Hashing.sha1Hex(created) 又读一遍整包（JVM 侧第三遍）
~~~

### 2.2 三处可消除成本

1. **全量解压**（`archive.rs` `read_to_end`）：270 MB 输入 → 270 MB 常驻 + 输出缓冲，W1 峰值 547 MB。
2. **全量重压**（`engine.rs` `write_zip`）：deflate 占 W1 耗时的 86%（纯压缩微基准 4,195 ms / 4,073 ms 总耗时），而其中绝大多数条目本来就是已压缩的 PNG/OGG。
3. **三次整包读**：Rust 端 `std::fs::read` + JVM 端 `Hashing.sha1Hex` + 客户端下载前的 sha 校验；前两者都可消除。

### 2.3 数据来源

- W1/W2 定义与旧/新数字：`docs/perf-benchmark.md` §3；原型：`docs/optimization-research.md` §3.3。
- 分阶段收益归因：解压 ~0.55 s、压缩 ~3.4 s、写盘+哈希 ~0.15 s（W1 4.07 s 的分解）。

---

## 3. 分层与切片

### 3.1 语言边界（保持不变）

~~~
Kotlin  PackPublisher / 配置 / 命令 / 调度      —— 编排，不碰 zip 字节
  │ exec + 一行 JSON 报告
Rust    rps-pack：合并算法与 zip IO（唯一实现）
  │ HTTPS 下发
Java    PackHttpServer / PackDelivery SPI / ETag
~~~

- Rust 不得知道 Bukkit/Fabric；Kotlin 不得 import `org.bukkit` / `net.minecraft`（既有判定线）。
- 本轮**不新增跨语言调用**：报告 JSON 结构不变，Kotlin 只在 P0-3 复用已有 `sha1` 字段。
- 合并器仍然是**独立进程 + 内置二进制**，不改部署模型。

### 3.2 模块图（目标）

~~~
engine.rs   merge() 编排（目标 ~120 行，从 534 行降下来）
  ├─ scan.rs     来源 → 元数据（~150 行）
  ├─ resolve.rs  元数据决策：层序/去重/策略/合并链/冲突（~230 行）
  ├─ write.rs    raw copy + 合并条目写出（~210 行）
  ├─ report.rs   装配 Report + 惰性 sha1（~90 行）
  ├─ jsonmerge.rs / mcmeta.rs / model.rs  原样复用
  └─ 删除：archive.rs read_source、crc.rs、Blob
~~~

### 3.3 代码预算

| 文件 | 现在 | 之后 | 说明 |
| --- | --- | --- | --- |
| `src/engine.rs` | 534 | ~120 | 只留编排与 format 交集 |
| `src/archive.rs` | 111 | 0 | 名称校验并入 scan.rs |
| `src/crc.rs` | 48 | 0 | 中央目录已给 crc32 |
| `src/scan.rs` | 0 | ~150 | 新增 |
| `src/resolve.rs` | 0 | ~230 | 新增 |
| `src/write.rs` | 0 | ~210 | 新增 |
| `src/report.rs` | 0 | ~90 | 新增 |
| **净计** | 693 | 800 | 结构更清晰，净增约 107 行，但删掉 3 处大缓冲与 2 个模块 |

### 3.4 简洁性规则（代码评审口径）

1. **删除优先**：能删的代码不留；不为「以后可能用」保留旧路径（没有 Java 兜底、没有 feature 开关）。
2. **一个模块一个职责**，公开面只到 crate 内；`lib.rs` 只导出 `merge` 与 `model`。
3. **不做两个实现之上的抽象**：不引入 trait、不引入配置开关去切换新旧引擎。
4. 错误字符串保持「来源 id + 条目名 + 原因」形状，便于现有日志检索（`来源 {id} 条目 {name} ...`）。
5. 所有上限常量集中在所属模块顶部，禁止散落的魔数。

---

## 4. 目标设计

### 4.1 scan.rs：只读中央目录

~~~rust
pub struct EntryMeta {
    pub name: String,
    pub index: usize,
    pub crc: u32,
    pub size: u64,
    pub compressed: u64,
}

pub struct SourcePack {
    pub id: String,
    pub priority: i64,
    pub rank: usize,
    pub path: PathBuf,
    pub archive: ZipArchive<BufReader<File>>,
    pub entries: Vec<EntryMeta>,
    pub mcmeta: Option<Value>,
}

pub fn scan(path: &Path, source: &Source, rank: usize, total: &mut usize) -> Result<SourcePack>
~~~

- 用 `by_index_raw(index)` 取 `name()`/`crc32()`/`size()`/`compressed_size()`，**不解压**；遍历完即丢弃 `ZipFile`，只留元数据。
- 只对 `pack.mcmeta` 做一次解压 + JSON 解析（与今天一致：后出现的覆盖前面的）。
- 沿用全部校验：名称安全、单条 ≤64 MB、单源 `archive.len()` ≤20,000、累计条目 ≤20,000。
- 复杂度 O(条目数)；常驻内存 O(条目数 × ~96 B + 每源 1 个 BufReader)。

### 4.2 resolve.rs：元数据决策

~~~rust
pub enum Slot {
    Passthrough { src: usize, index: usize },
    Merged { kind: MergeKind, chain: Vec<(usize, usize)> },
}

pub struct Side {           // 冲突的一侧，只存元数据
    pub id: String,
    pub priority: i64,
    pub src: usize,
    pub index: usize,
    pub bytes: u64,
    pub sha1: Option<String>,   // 惰性
}

pub struct ConflictState {
    pub winner: Side,
    pub losers: Vec<Side>,
    pub kind: AssetKind,
    pub mode: ConflictMode,
    pub same_priority: bool,
    pub fields: Vec<String>,
}

pub struct Resolution {
    pub slots: BTreeMap<String, Slot>,
    pub owners: BTreeMap<String, Side>,
    pub conflicts: BTreeMap<String, ConflictState>,
    pub deduped: usize,
    pub warnings: Vec<String>,
}

pub fn resolve(request: &Request, sources: &mut [SourcePack]) -> Result<Resolution>
~~~

决策顺序（严格镜像今天的 `insert`）：

~~~
按 rank 升序，逐来源按 index 升序：
  pack.mcmeta            → 跳过（scan 已抽走）
  slots 无此名           → Passthrough(incoming)
  同来源（rank 相同）    → 直接替换，不记冲突
  kind == Metadata       → 直接替换，不记冲突（pack.png）
  可合并 JSON：
      链长 == 1 且 crc/size 相同且 sha1 相等 → deduped++，跳过
      否则 chain.push(incoming)（resolve 阶段做一次 JSON 解析校验）
  crc/size 相同且 sha1 相等 → deduped++，跳过
  policy == Strict       → 报错（内容不同）
  policy == Override     → 记冲突(winner=incoming, loser=previous)，替换
  policy == First        → 记冲突(winner=previous, loser=incoming)，保留
~~~

- `sha1_of(src, index)`：流式读取（`by_index` + `Read::take(MAX_ENTRY_BYTES+1)`，64 KB 块），结果缓存到 `HashMap<(usize, usize), String>`；每个条目最多解码一次。
- 去重判定用 CRC+size 预筛，再比 SHA-1——与今天**完全等价**（今天也是这个三段式），只是不再把内容留在内存。
- 可合并 JSON 的解析失败：与今天一样打警告并退化为 policy 分支；解析只做校验，不保留 `Value`。
- 命名空间警告在最终 `owners` 上计算（merged 的 owner 取最高层贡献者），语义同 `namespace_warnings`。

### 4.3 write.rs：搬运与少量重压

~~~rust
pub struct MergedOutcome { pub path: String, pub sha1: String, pub fields: Vec<String> }
pub struct Written { pub bytes: u64, pub sha1: String, pub merged: Vec<MergedOutcome> }

pub fn write(
    resolution: &mut Resolution,
    sources: &mut [SourcePack],
    request: &Request,
    output: &Path,
) -> Result<Written>
~~~

写出循环（`slots` 的 BTreeMap 顺序 = 名称升序）：

| 条目 | 写法 | 是否重压 |
| --- | --- | --- |
| Passthrough | `by_index_raw` → `raw_copy_file_touch(file, DateTime::default(), Some(0o644))` | 否，原压缩字节 |
| Merged | 低→高解码 → 每步 `conflicts`/`merge` → 序列化 → `start_file` + `write_all` | 是，level 6 |
| pack.mcmeta | 由 format 交集生成 → `start_file` + `write_all` | 是（极小） |

- `ZipWriter::new(BufWriter::new(File::create(output)?))`，随后 `writer.set_auto_large_file()`（>4 GB 自动 zip64）。
- 重压条目 options 保持今天的样子：`Deflated` / `level 6` / `DateTime::default()` / `0o644`。
- `raw_copy_file_touch` 会丢掉源扩展时间戳、保留注释与其它 extra、重算 zip64 extra——正好满足「时间戳归一化 + 字节搬运」。
- `finish()` 后：用 64 KB 缓冲流式算 sha1，`bytes` 取 `std::fs::metadata(output).len()`；**不再** `std::fs::read` 整包。
- Merged 条目的 sha1 在序列化时顺手算（`Sha1::update(&merged_bytes)`），回填给报告。

### 4.4 report.rs

- format 交集、`supported_formats`（format<65）、`entries = slots.len() + 1`、`deduped`、`conflictSummary`、`truncated`、`warnings`、`millis` 全部沿用现逻辑。
- 冲突 sha1：Passthrough 用 `sha1_of` 缓存；Merged 用 `MergedOutcome.sha1`；losers 只取前 5 条 + 追加 `loserSha1Count`（P0-2，Kotlin 忽略未知字段）。
- 明细上限 5,000 条不变。

### 4.5 engine.rs

~~~rust
pub fn merge(request: &Request, project_root: &Path, output: &Path) -> Result<Report> {
    let started = Instant::now();
    let mut sources = scan_all(request, project_root)?;      // format ranges 一并得到
    let mut resolution = resolve::resolve(request, &mut sources)?;
    let written = write::write(&mut resolution, &mut sources, request, output)?;
    Ok(report::build(request, started, resolution, written, &sources))
}
~~~

### 4.6 内存上界

~~~
peak ≈ Σ BufReader(128 KB × N)
     + entries × ~96 B
     + max(一条合并链的累加 Value + 单个条目)
     + 64 KB 哈希缓冲
~~~

W1：3×128 KB + 469×96 B + ≈1 MB ≈ **1.5 MB**（原型实测 15 MB，差额是 zip 自身结构）。
W2：40×128 KB + 5,063×96 B ≈ **5.6 MB**（原型 55 MB 的大头是 40×1 MB BufReader，改 128 KB 即可）。

### 4.7 确定性论证

- 顺序：BTreeMap 名称升序，与今天一致。
- 时间/权限：raw copy 用 `touch` 归一化为 1980-01-01 / 0644；重压条目 options 固定。
- 压缩：重压条目 level 6；raw 条目保留源压缩字节 → 输出由「源文件字节」唯一决定。
- 结论：相同源文件 → 相同输出字节（与今天同强度）；跨机器同样成立，前提是源文件字节一致。
- 与旧版的唯一差异：raw 条目的压缩方法/字节被保留（不再重压），因此**首次升级后 sha1 会变一次**，内容语义不变。

---

## 5. 关键技术决策

| # | 决策 | 理由 | 替代方案与为何不选 |
| --- | --- | --- | --- |
| D1 | 用 `raw_copy_file_touch`（zip 9.8.2 `src/write.rs:1442`） | 同时满足「搬运压缩字节」与「时间戳/权限归一化」 | `raw_copy_file_rename` 保留源时间，破坏测试与确定性；自写 writer 成本高 |
| D2 | 去重 = CRC+size 预筛 → 流式 SHA-1（缓存） | 与今天语义完全等价，且每个条目最多解码一次 | 只信 CRC+size：会漏掉刻意构造的碰撞；整包解压：回到老路 |
| D3 | 可合并 JSON 延迟到 write 阶段合并 | resolve 只建链，避免把 `Value` 常驻 | resolve 阶段合并需缓存中间字节（内存回涨） |
| D4 | 写后流式算 sha1；JVM 侧复用 `result.sha1()` | 去掉 270 MB 整包分配与 JVM 二次读 | 保留 `std::fs::read`：RSS 峰 +270 MB |
| D5 | `set_auto_large_file()` | >4 GB 自动 zip64，小包不受影响 | 固定 `large_file(true)`：全量 zip64，体积与兼容性变差 |
| D6 | 时间戳必须归一化 | `single_source_passthrough` 已锁定该契约 | 保留源时间：确定性依赖源时间，跨机器不稳 |
| D7 | 不做并行 deflate | raw copy 后只剩少量 JSON 需压缩 | 见 `docs/optimization-research.md` §2 |
| D8 | 删除 `crc.rs` 与 `read_source` | 中央目录已提供 crc32，代码更少 | 保留：死代码与两套读取路径 |
| D9 | 报告主字段不变 | `RustPackMerger.java` 解析契约稳定 | 重命名/嵌套化：无收益，破坏兼容 |

---

## 6. 切片计划

> 每片都有：改动面、验收命令、完成判据、回滚点。**S1–S4 顺序执行**；S5 可与 S2 并行（不同文件）。

### S0 基线固化（0.5 人日）

- 复制当前二进制到 `.tmp/baseline/rps-pack-old.exe`。
- 用 W1/W2 生成基线输出与清单（条目名 + 解压后 sha1），脚本 `.tmp/bench/manifest.py`。
- 记录基线数字（已有：`.tmp/bench/results.json`）。
- **完成判据**：`manifest.py old merged.zip > baseline.json` 产出 469 / 5,063 条记录。
- 回滚点：无代码改动。

### S1 scan.rs（0.5 人日）

- 新增 `scan.rs`；`archive.rs` 保留 `validate_entry_name` 与常量，删除 `read_source`（先标记 `#[allow(dead_code)]` 到 S4 再删）。
- 新测试：`metadata_matches_content`（crc/size 与解压后一致）、`rejects_unsafe_names`、`extracts_mcmeta`、`enforces_entry_limits`。
- **验收**：`cargo test --manifest-path rust/rps-pack/Cargo.toml` 全绿（旧 `merge` 仍工作）。
- 回滚点：单文件删除。

### S2 resolve.rs（1 人日）

- 实现 Slot/Side/ConflictState/Resolution、`sha1_of` 缓存、决策顺序、命名空间警告。
- 新测试：`same_source_duplicate_last_wins`、`metadata_kind_is_silent`、`dedup_by_sha1_when_crc_matches`、`conflict_details_capped`。
- **验收**：`cargo test`；新增 resolve 单测通过；旧集成测试不动。
- 回滚点：模块独立，未接入 `merge`。

### S3 write.rs（1 人日）

- 先做 20 行 spike：`by_index_raw` + `raw_copy_file_touch` 能否编译并保留字节（含 data descriptor 条目）。
- 实现写出循环、合并链、mcmeta、流式 sha1、`set_auto_large_file`。
- 新测试：`raw_copy_keeps_compressed_bytes`、`timestamps_normalized_for_raw_copy`、`merged_entry_bytes_stable`、`output_sha1_matches_file`。
- **验收**：4 个新测试 + 现有 20 个集成测试全绿（此时 `merge` 已切到新路径）。
- 回滚点：`git checkout -- rust/rps-pack/src/engine.rs` 回到旧实现（S4 之前不提交）。

### S4 engine 装配与删除（0.5 人日）

- `engine.rs` 改为 §4.5 的编排；删除 `Blob`、`read_source`、`crc.rs`；`lib.rs` 导出不变。
- **验收**：`cargo test` 全绿；`cargo clippy -- -D warnings` 无新增告警；`cargo build --release`。
- 回滚点：本片单独一个 commit，可 revert。

### S5 JVM 瘦身（0.3 人日，可与 S2/S3 并行）

- `core/src/main/kotlin/moe/nekomc/resourcepack/pack/PackPublisher.kt:193`：优先用 `result.sha1()`（非空时），保留可选的整包校验（默认关）。
- 不改任何请求字段；`RustPackMerger` 不动。
- **验收**：`.tmp/build-all.ps1` 全绿，core 93 项测试通过；W1 端到端（/rps build）JVM 侧耗时下降。

### S6 对拍与基准（0.5 人日）

- 新二进制 vs 基线二进制：逐条目解压内容 sha1 全等（W1/W2）。
- 报告字段对比：entries/deduped/conflicts/summary 全等；`sha1` 允许不同（记录）。
- 基准 5 次中位，写入 `docs/optimization-research.md` 追加「实施结果」。
- **验收**：对拍 0 差异；W1 ≤0.45 s / ≤60 MB；W2 ≤0.75 s / ≤80 MB。

### S7 清理与文档（0.3 人日）

- 更新 `docs/design.md` 合并章节（两阶段流程 + 内存上界）、`docs/optimization-research.md` 状态、`docs/perf-benchmark.md` 备注。
- 删除 `.tmp/proto` 以外的临时产物；保留 `.tmp/bench` 供复现。
- **验收**：文档交叉引用一致；`cargo test` 再次全绿。

---

## 7. 语义对照表（旧 → 新）

| 行为 | 今天 | 重构后 | 风险 |
| --- | --- | --- | --- |
| 条目读取 | 全量解压到 `Vec` | 中央目录元数据 | 低 |
| 去重 | CRC+size+SHA-1 | 同（SHA-1 流式、缓存） | 低 |
| 覆盖策略 | 内存里比字节 | 元数据比 CRC/size/SHA-1 | 低 |
| JSON 合并 | 插入时合并 | 建链，写出时合并 | 中（解析时机变化，需对拍） |
| 冲突 fields | 合并时计算 | 写出时计算，报告前回填 | 中（同上） |
| 冲突 sha1 | 解压字节上算 | 流式/合并时算 | 低 |
| 输出压缩 | 全部重压 level 6 | 未合并条目搬运原字节，合并条目 level 6 | 中（sha1 变一次） |
| 输出时间戳 | 全部 1980-01-01 | raw 条目经 `touch` 仍为 1980-01-01 | 低（测试锁定） |
| 输出 sha1 | 整包读回算 | finish 后流式算 | 低 |
| 内存 | ≈2× 输入 | ≈元数据 + 单条链 | 低 |
| 源 CRC 校验 | 解压时隐式校验 | 不解压则不校验（可选 `verify_crc`，P2） | 中（记录） |
| 非 UTF-8 条目名 | 解码为 UTF-8 重写 | 保留原始字节（`touch` 不重命名） | 低（MC 包均为 UTF-8） |

---

## 8. 测试矩阵

### 8.1 现有 20 个集成测试的归属

- scan 覆盖：`single_source_passthrough`、`zip_slip_entries_are_rejected`、`missing_mcmeta_*`、`format_intersection_empty_fails`。
- resolve 覆盖：`two_layer_override_conflict`、`identical_entries_are_deduped`、`same_size_different_content_is_not_deduped`、`same_priority_conflict_is_flagged`、`three_layers_accumulate_losers`、`strict_policy_fails_on_path_conflict`、`first_policy_keeps_lower_layer`、`pack_icon_comes_from_highest_layer`。
- write 覆盖：`lang_json_is_deep_merged`、`font_arrays_are_concatenated_high_first`、`font_same_id_reports_json_conflict`、`json_conflict_fields_are_capped`、`requested_format_overrides_intersection_max`、`same_input_twice_produces_same_sha1`。

### 8.2 新增测试（12 个）

| 模块 | 测试 | 断言 |
| --- | --- | --- |
| scan | `metadata_matches_content` | 中央目录 crc/size 与解压结果一致 |
| scan | `extracts_mcmeta_without_entries` | mcmeta 抽走、不出现在 entries |
| scan | `enforces_entry_limits` | 超单条/累计上限报错 |
| resolve | `same_source_duplicate_last_wins` | 同 zip 内重名后者胜、无冲突 |
| resolve | `metadata_kind_is_silent` | pack.png 覆盖不记冲突 |
| resolve | `dedup_by_sha1_when_crc_matches` | 同 CRC/size 同内容去重 |
| resolve | `conflict_details_capped` | >5,000 条时 truncated 且 summary.total 为全量 |
| write | `raw_copy_keeps_compressed_bytes` | 输出条目的压缩字节 = 源条目 |
| write | `timestamps_normalized_for_raw_copy` | 全部 1980-01-01 / 0644 |
| write | `output_sha1_matches_file` | 报告 sha1 = 文件实际 sha1 |
| write | `merged_chain_matches_legacy` | 三层 lang 合并结果与旧算法一致 |
| engine | `report_bytes_match_file_size` | bytes = 文件大小 |

### 8.3 JVM 侧

- `RustPackMergerTest`（真实二进制）必须继续通过：`winnerSha1` 40 字符、`loserSha1s` 至少 1 条、`conflictSummary.high`。
- `:core` 93 项、`:paper` 7 项全绿；`tools` 27 项不受影响。
- 端到端：Fabric/Paper 测试服各跑一次 `/rps build`，确认 `merged.zip` 可下载且客户端加载成功。

---

## 9. 性能目标与测量

| 指标 | 基线 | 目标 | 测量 |
| --- | --- | --- | --- |
| W1 墙钟 | 4,135 ms | ≤450 ms | `measure.ps1` ×5 中位 |
| W1 峰值 RSS | 547 MB | ≤60 MB | 同上 |
| W2 墙钟 | 999 ms | ≤750 ms | 同上 |
| W2 峰值 RSS | 100 MB | ≤80 MB | 同上 |
| JVM 端到端 W1 | 合并 + ~200 ms 哈希 | 合并（复用 sha1） | `/rps build` 计时 |
| 输出大小偏差 | 270,174,433 B | ±0.05% | 对拍 |

收益分解（W1）：搬运 270 MB 压缩字节 ≈ 0.15 s；流式哈希 0.05 s；进程启动 0.03 s；报告与元数据 <0.02 s。

---

## 10. 风险登记

| # | 风险 | 触发 | 缓解 | 回滚 |
| --- | --- | --- | --- | --- |
| R1 | `raw_copy_file_touch` 行为与预期不符（时间/extra/压缩方法） | S3 spike | spike 先在 3 类条目（stored/deflated/带 descriptor）验证 | 改用 `raw_copy_file_rename` + 接受时间戳变化（需改测试） |
| R2 | 源 zip CRC 损坏不再被发现 | 源文件被截断 | P2 加 `verify_crc`（流式 CRC 扫描）；文档说明 | 保持现状 |
| R3 | 可合并 JSON 链过长/过大 | 大量 lang 叠加 | `MAX_MERGED_BYTES = 64 MB`，超限退化为 policy 覆盖 + 警告 | 无需 |
| R4 | 报告 sha1 与旧版不同 | 升级后首次构建 | 一次性 ETag 变更，客户端重下；文档记录 | 无需 |
| R5 | 非 UTF-8 条目名 | 老旧工具打的包 | 保留原始名；若出现乱码，加 normalize 选项 | 无需 |
| R6 | zip64 输出 | 总量 >4 GB | `set_auto_large_file`；S3 用大条目单元测试 | 关闭 auto（回到 4 GB 限制） |
| R7 | 合并阶段解析两次（校验 + 合并） | 大 lang 文件 | 只对 mergeable 路径，文件数少；实测确认 | 合并时再校验（失败退化） |
| R8 | 对拍发现条目内容差异 | S6 | 逐条目定位；优先保证内容一致，再谈性能 | revert 整个重构 commit |
| R9 | Windows 句柄占用 | 输出与来源同目录 | 输出是不同文件；PackPublisher 在进程结束后才 move | 无需 |
| R10 | 并发重建（watch + 手动） | 同一输出 | PackPublisher 已有 buildLock + staging 临时文件 | 无需 |

---

## 11. 交付与提交策略

- 一个重构 commit：`refactor(rps-pack): 两阶段原始字节搬运合并`（S1–S4 + S5 + 测试）。
- 一个文档 commit（可合并）：design/optimization 文档更新 + 本计划状态改「已实施」。
- 不 push、不合入 main（合入会触发 auto-release 发 0.1.1，需用户明确同意）。
- 保留 `.tmp/baseline` 与 `.tmp/bench/compare` 脚本供复现（.tmp 已 gitignore）。

---

## 12. 执行清单

- [ ] S0 基线二进制 + manifest 脚本
- [ ] S1 scan.rs + 4 测试
- [ ] S2 resolve.rs + 4 测试
- [ ] S3 write.rs + spike + 4 测试
- [ ] S4 engine 装配、删旧代码、clippy
- [ ] S5 PackPublisher 复用 sha1
- [ ] S6 对拍（0 差异）+ W1/W2 基准
- [ ] S7 文档与清理
- [ ] 汇报并等用户决定是否 push / 进 P2

---

*本计划基于 2026-11-27 的源码快照（HEAD 47c9724）；所有行号随实现变动，以模块与函数名为准。*
