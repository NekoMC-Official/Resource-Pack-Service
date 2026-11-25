# 设计说明

本文记录 Resource Pack Service 的边界、对外契约、合并语义、下发链路与已知取舍。
面向要接来源的插件作者、要排障的运维，以及后续维护者。

## 1. 目标与非目标

**目标**

- 多个插件各自产出资源包时，服务端只下发**一份**合成包。
- 来源可以来自配置、插件 API、或任意已落盘的 zip；不要求对方改推送逻辑。
- 合成结果确定性可复现，内容不变就不让玩家重下。
- 服务自己完成 HTTP 下载出口与进服推送，不依赖其它插件。
- 失败时保留上一版，绝不把半成品或空包发给客户端。

**非目标**

- 不做客户端 mod，不做协议层增量下载（原版协议不支持）。
- 不做按玩家分组的多套资源包；Minecraft 一个连接只有一个服务端包。
- 不接管其它插件的私有推送；只通过 `/rps check` 报告疑似冲突。
- 不提供公网鉴权、限速、CDN 同步；这些交给反向代理或对象存储。

## 2. 分层与依赖方向

```text
Java（宿主边界）
  ResourcePackServicePlugin   插件入口、ServicesManager 注册、生命周期
  api/PackSource              对外来源标准（纯 JDK）
  api/ResourcePackService     对外服务入口（纯 JDK）
  delivery/PackHttpServer     下载出口
  delivery/PackPusher         进服推送、回执、Folia 调度
  merge/RustPackMerger        启动 rps-pack、解析 JSON 结果
  merge/BundledRust           从 jar 解出当前平台二进制
  pack/PackSnapshot           跨层不可变快照（Java record）
  pack/CommunityPack          社区包元数据（Java record）
  pack/CommunitySelection     选择结果（Java record）
  pack/CommunitySkip          跳过原因（Java record）
  pack/SelectionContext       已安装插件、版本、MC 与 format 的判定上下文（Java record）
        ↓
Kotlin（编排）
  config/RpsConfig            读取与校验 config.yml
  core/SourceRegistry         来源表与层序
  core/StateStore             sha1 → 世代号
  pack/PackPublisher          异步单飞、落盘、换代、通知
  pack/ConflictReport         冲突明细持久化与默认过滤
  pack/RegisteredSources      ResourcePackService 的实现
  pack/CommunitySelector      绑定、版本、format、requires 与变体选择
  pack/VersionRange           Minecraft / 插件版本范围解析
  pack/PackHost               发布器依赖的宿主端口
  command/RpsCommandHost      命令依赖的宿主端口
  config/CommunityBundle      读取 manifest 并产出社区来源
  pack/CommunityIndex         解析 Pages 索引并生成 manifest
  pack/CommunityUpdater       拉取索引、按需下载、校验与原子替换
  pack/EntryManifest          entries.txt / entries.prev.txt 与 diff
  pack/PackConflictScanner    扫描其它插件的疑似推送配置
  watch/SourceWatcher         mtime 轮询
  command/RpsCommand          命令与权限
        ↓
Rust（计算）
  rps-pack                    读取请求、合并 zip、输出确定性包与报告
```

规则：

1. 依赖只能向下：Java 可以调 Kotlin，Kotlin 可以调 Java 的 DTO 和 Rust 进程；反向不成立。
2. 跨语言只传 JDK 类型和 Java record（`PackSnapshot`、`ZipSource`、`HostStatus` 等），
   不把 Kotlin 默认参数、扩展函数、`object` 单例当 API。
3. Kotlin 的 `jvmTarget` 与 Java 的 `--release` 都是 25，避免 JVM-target 不一致。
4. Rust 只做纯函数式计算：输入请求与文件，输出 zip 与 JSON 报告，不碰 Bukkit。

## 3. 对外标准 API

包名 `moe.nekomc.resourcepack.api`。除事件必须继承 Bukkit 的 `Event` 外，只使用 JDK 类型；不暴露 Kotlin 类型或默认参数：

```java
public interface PackSource {
    String id();
    Path file();
    int priority();
    default boolean enabled() { return true; }
    default String sha1() { return null; }
    default List<String> namespaces() { return List.of(); }
    default String version() { return ""; }
}

public interface ResourcePackService {
    boolean register(PackSource source);
    boolean unregister(String id);
    Set<String> sourceIds();
    int version();
    String url();
    String sha1();
    void rebuild(boolean announce);
    int push(UUID player, boolean force);
    void invalidate(String id);

    // 0.1.6+，全部带 default 实现
    CompletableFuture<BuildResult> rebuildAsync(boolean announce);
    BuildResult snapshot();
    PackStatus status(UUID player);
    String hashOf(UUID player);
    String lastError();
}

public record BuildResult(
        boolean ok, String sha1, long version, String url,
        int entryCount, long bytes, String error) {}

public enum PackStatus { UNKNOWN, PENDING, LOADED, DECLINED, FAILED, INVALID_URL }

public final class PackStatusEvent extends Event {
    UUID playerId(); PackStatus status(); String sha1();
}
```

取得方式：

```java
ResourcePackService service = Bukkit.getServicesManager().load(ResourcePackService.class);
```

行为约定：

| 项 | 约定 |
| --- | --- |
| `register` | `id` 为空或 `file` 为 null 返回 false；同 id 覆盖；成功即触发异步重建 |
| `unregister` | 不存在返回 false；成功即触发异步重建 |
| `sourceIds` | 只含 API 登记的来源，不含 `config.yml` 的 `sources` |
| `PackSource.version` | 可选的内容版本；空串表示未声明，只用于 `/rps list` 展示，不参与世代号与合并顺序 |
| `version` | 当前成功构建的世代号；从未成功为 0 |
| `url` / `sha1` | 当前快照；未构建或没有下载地址时为空串 |
| `push` | 只对在线玩家生效，返回排程数量；不等待客户端回执 |
| `invalidate` | 来源 id 为空或未登记时无操作；否则触发一次异步重建，用于插件重写了本地 zip |
| 配置来源与 API 来源同 id | API 来源覆盖配置来源 |
| `rebuildAsync` | Java 不能只按返回类型重载 `rebuild(boolean)`，异步版另起名；同刻只跑一次，重复请求合并到下一轮，future 在该轮结束时完成 |
| `snapshot` | `sha1` 与客户端实际下载的 `merged.zip` 逐字节一致，`url` 是带版本参数的完整可 GET 地址；失败时保留上一次成功字段并带 `error` |
| `status` / `hashOf` | 未知或已离线玩家返回 `UNKNOWN` / `null`，不返回 null 状态；数据与 `/rps rejected` 共用同一份内存表 |
| `lastError` | 最近一次构建失败原因；成功或尚未构建为 `null` |
| `PackStatusEvent` | 在该玩家的 Folia 实体调度器触发，不参与取消；客户端回执已经发生后才发 |

API 来源在 `/rps reload` 后由 `RegisteredSources.reapply` 重新投回新注册表，
不需要插件重新注册。

## 4. 来源与层序

来源有三种进入方式：

1. `config.yml` 的 `sources`（运维零代码）；
2. 插件实现 `PackSource` 并 `register`；
3. 插件把 zip 落在某路径，由运维写进 `sources`。

`SourceRegistry` 以 `priority` 升序排列：低优先级先入层，高优先级后入层。
同一路径冲突时高层覆盖低层。相同 `priority` 按登记顺序，先登记的先入层
（也就是在 `override` 下先登记的反而会被后登记的覆盖，这一点在文档里明确写死，
避免“同优先级谁赢”靠猜）。

## 5. 合并语义

### 5.1 条目选择

| 情况 | 处理 |
| --- | --- |
| 路径只出现一次 | 直接保留 |
| 路径多次出现且 sha1 相同 | 去重，保留一份 |
| 路径多次出现且 sha1 不同，`policy=override` | 取最高层 |
| 同上，`policy=first` | 取最低层 |
| 同上，`policy=strict` | 整次构建失败，报告冲突路径与来源 |
| 目录条目 | 忽略，只处理文件 |

判重先比大小与 CRC32 预筛，再用 SHA-1 确认；三者全等才算同一份内容。冲突按路径分类为
`texture` / `texture-meta` / `model` / `blockstate` / `item-model`（高）、
`lang` / `sound` / `font` / `atlas`（中）、`shader` / `other`（低），`pack.png` 与
`pack.mcmeta` 属于元数据，不记冲突。

### 5.2 可合并 JSON

以下路径不是“整文件覆盖”，而是按键深合并：

- `assets/<ns>/lang/*.json`
- `assets/<ns>/sounds.json`
- `assets/<ns>/font/*.json`
- `assets/<ns>/atlases/*.json`

对象递归合并；标量由高层覆盖；数组按“高层在前”拼接后去重。
解析失败时该文件退化为普通条目，按冲突策略取一份，并在报告里给出警告。

深合并前会先比较两侧共同键；标量或数组条目定义不同的字段记为 `json-deep` /
`json-concat` 冲突，但仍写出合并结果。冲突明细最多保留 5000 条，字段说明最多 50 条、
单条 120 字符；`/rps conflicts` 默认只展示高严重度，`all` 展示全部，报告同时写入
`conflicts.json`。

### 5.3 pack.mcmeta

所有来源的 `pack.mcmeta` 都丢弃，由 rps-pack 重建：

- `pack_format`：取所有来源声明范围的交集；交集为空则构建失败；
- `supported_formats`：保留为交集区间；
- `description`：取 `merge.description`。

来源可以不带 `pack.mcmeta`，视为不限制格式；只有所有来源都不带且请求里
`format=0` 时才失败，并要求显式指定 `merge.pack-format`。

### 5.4 确定性与安全

- 条目按路径升序写入；
- 所有条目时间戳固定为 1980-01-01 00:00:00；
- 相同输入得到逐字节相同的 zip，sha1 稳定；
- 拒绝 `..`、绝对路径、盘符、NUL 等等 zip slip 路径；
- 单条目解压上限 64 MB，条目数上限 20000；
- 合成包总大小受 `merge.max-bytes` 限制，超过则构建失败并保留上一版。

## 6. 构建与换代

`PackPublisher` 在异步调度器上运行，同一时刻只允许一次构建（`AtomicBoolean`）：

```text
registry.ordered()
  → 检查来源文件存在
  → rps-pack merge --request - --output merged.staging.zip
  → 校验大小、算 sha1
  → Files.move(staging, merged.zip, REPLACE_EXISTING)
  → StateStore.commit(sha1) 决定世代号是否递增
  → EntryManifest.rotateAndWrite()
  → ConflictReport.write(conflicts.json)
  → 生成 url（base + ?v=世代号）
  → PackSnapshot 原子替换
  → 若内容变化，按 push.mode 通知
```

失败分支：

- `MergeUnavailableException`：合并器缺失或无法执行，`SEVERE` 记录并保留上一版；
- 其它异常：`WARNING` 记录并保留上一版；
- 快照保留上一版的 url/sha1/version，额外带上 `error` 文本，`/rps status` 可见。

内容未变化时 `StateStore.commit` 返回 false，跳过重推，避免全服重下。

## 7. 下发链路

### 7.1 下载出口

`PackHttpServer` 用 JDK `com.sun.net.httpserver.HttpServer`：

- 只服务配置的固定路径，其它路径 404；
- 只接受 GET/HEAD，其它方法 405；
- 用文件 sha1 生成强 ETag，支持 `If-None-Match` 返回 304；
- 8 个守护线程处理并发下载；
- `port=0` 时由系统分配，启动日志与实际 URL 使用真实端口；
- 文件还不存在时返回 404，等构建完成后再可下载。

`delivery.http.public-url` 非空时，推送地址完全以它为准；此时可以关闭内置 HTTP，
把文件同步到 CDN 或对象存储。

### 7.2 推送

`PackPusher`：

- 监听 `PlayerJoinEvent`，延迟 `delivery.join-delay-ticks` 后在玩家的
  Folia 实体调度器上调用 `setResourcePack`；
- sha1 为空时使用 3 参重载，非空时使用带 sha1 的 4 参版本；
- 监听 `PlayerResourcePackStatusEvent`，记录加载成功、拒绝、下载失败、无效地址；
- `provider.required=true` 且客户端拒绝时，在玩家调度器上断开；
- 是否把“必需”标记随包发出去由 `provider.announce-required` 决定（未配置时跟随 `required`）：
  取 `true` 时 `PackPusher` 把 `needed=true` 传给 `setResourcePack`，客户端即使档位为「已禁用」
  也会弹必装框（Proceed / Disconnect），拒绝时客户端会先用自己的文案断开、`reject-message` 只作兜底；
  取 `false` 时客户端一律当可选包，客户端弹窗文案始终是“建议/可选”；
- 内容变化后：`none` 不动作，`notify` 发可点击提示，`force` 直接重推所有在线玩家。

客户端只保留最后收到的包；同一时刻只允许本插件推送，是部署前提。

## 8. 权限

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `rps.use` | true | 进入 `/rps`；玩家可 `/rps push` 给自己；`/rps conflicts` 只需此权限 |
| `rps.status` / `list` / `diff` / `check` | op | 查看类 |
| `rps.build` / `reload` / `push` | op | 管理类 |
| `rps.admin` | op | build/reload/push/check 的集合 |
| `rps.*` | op | 全部子节点 |

玩家对别人执行 `/rps push <玩家>` 仍需要 `rps.push` 或 `rps.admin`；
命令帮助和 tab 补全按真实权限过滤。

## 9. 失败与降级

| 场景 | 行为 |
| --- | --- |
| 没有来源 | 构建失败，保留上一版，`/rps status` 显示原因 |
| 来源文件不存在 | 构建失败，保留上一版 |
| rps-pack 缺失/不可执行 | `SEVERE`，保留上一版；jar 内置版会先尝试解出 |
| 合并超时 | 杀掉子进程，构建失败，保留上一版 |
| 合并输出非法 JSON/非零退出 | 构建失败，保留上一版 |
| 合成包超过 `merge.max-bytes` | 构建失败，保留上一版 |
| HTTP 端口被占用 | 记录 `SEVERE`；若填了 `public-url` 仍可推送 |
| `public-url` 指向回环 | `/rps check` 警告，远程玩家会下载失败 |

没有第二套合并实现：Rust 是唯一实现，缺失就明确报错，避免两份实现结果不一致。

## 10. 测试

- Java：`./gradlew test`，92 项（19 个测试类），覆盖 API 注册表与 invalidate、社区包选择与版本范围、索引解析、远程更新与校验、URL 拼接、HTTP 出口、内置二进制解出、Rust 合并器集成、冲突报告、条目清单、插件描述符、配置体检扫描等。
- Rust：`cd rust/rps-pack && cargo test`，26 项（19 项合并集成 + 2 项 mcmeta + 5 项单元），覆盖层序、冲突策略、
  JSON 深合并、mcmeta 交集、确定性、zip slip、缺失 mcmeta、冲突分类与指纹等。
- Python：`python -m unittest discover -s tools`，27 项，覆盖 official 变体默认、addon/patch 依赖、integration 多插件闭合、release=none、优先级排序、version / minecraft / format 范围解析、索引生成，以及内容变更但 version 未递增时发行计划失败的闸门。
- CI 在 Ubuntu 上跑 `python -m unittest discover -s tools`、`cargo test` 与 `./gradlew build`，并把合并器路径通过
  `RPS_PACK_BIN` 传给集成测试。

## 11. 里程碑

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | Rust 工具链与空 crate | 完成 |
| M1 | Kotlin + Shadow 构建骨架 | 完成 |
| M2 | 配置、来源注册表、命令 | 完成 |
| M3 | `Merger` 抽象与 `RustPackMerger`（唯一实现） | 完成 |
| M4 | `rps-pack` 合并、去重、JSON 深合并 | 完成 |
| M5 | 二进制随 jar 内置、缺失即报错、无回退 | 完成 |
| M6 | 条目差异、内置 HTTP、进服推送、部署文档 | 完成 |
| M7 | 社区资源包 PR 校验、受控发行，以及服务端按已安装插件 / Minecraft 版本 / pack format / requires 筛选 | 完成（待真机 preflight） |
| M8 | Pages 索引、服务端定时拉取、按需下载、sha1 校验与 `/rps community` | 完成（待真机 preflight） |
| M9 | 真实 Folia 服务端端到端联调 | 待做 |

## 12. 已知风险与取舍

1. **整包重下**：原版没有增量协议；只能靠 sha1 跳过同内容、把高频内容移出包、
   以及压缩体积。`/rps diff` 用于判断变更是否值得全服重下。
2. **端口暴露**：内置 HTTP 默认监听 `0.0.0.0`，需要防火墙只放行游戏入口；
   公网部署建议反代 + HTTPS。
3. **单包限制**：无法按玩家给不同资源包；需要分组时只能拆分服务器或改用客户端 mod。
4. **平台绑定**：jar 内置的是构建机平台的合并器；Linux 服务端请用 Linux CI 产物。
5. **插件冲突**：别的插件直接 `setResourcePack` 时无法仲裁，只能靠 `/rps check` 发现并关掉对方。
6. **max-bytes 是构建期闸门**：超限时保留上一版，不会截断或拆分。
