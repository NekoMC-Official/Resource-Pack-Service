# 计划：26.3 Fabric 服务端 mod（同仓库、同分层）

> 状态：**已落地并真机验收**（2026-11-27）。Paper 侧能力已 1:1 迁到 Fabric 服务端，实际形态与剩余项见 §12。目标是把现有 Paper/Folia 插件的能力 1:1 搬到 **Minecraft 26.3 + Fabric 服务端**，源码进同一个仓库，沿用现有「Java 内核 → Kotlin 胶水 → Rust 合并器」分层。

## 1. 目标与非目标

**目标**
- 服务端 mod，玩家进服按配置推送合并资源包；拒绝/失败/成功状态可观测、可查、可踢。
- 与 Paper 版共享同一套内核（合并、HTTP 托管、状态存储、配置键），配置文件名与键名保持一致，文档不用分叉。
- 官方发行两个 jar：`resource-pack-service-<ver>.jar`（Paper/Folia）、`resource-pack-service-fabric-<ver>.jar`（Fabric 26.3）。

**非目标**
- 不做客户端 mod（`environment: "server"`），玩家无需安装。
- 不复刻 Bukkit 插件联动（`PluginEnableEvent`、社区认领）——Fabric 无此生态，`community-bind` 相关能力在 Fabric 版直接不提供。
- 不移植 Folia 区域调度语义：Fabric 只有主线程，调度器退化为主线程队列（行为更简单，不是更复杂）。

## 2. 事实基线（已核实）

### 2.1 依赖版本（2026-11-26 实查）

| 项 | 值 | 来源 |
| --- | --- | --- |
| Minecraft | 26.3 | example-mod `gradle.properties` |
| Fabric Loader | 0.20.1 | 同上 |
| Fabric API | 0.167.0+26.3（example 用 0.166.2+26.3，取最新） | Modrinth `version/lPHkrIVF` |
| Loom | 1.19.4（稳定）；example 用 `1.19-SNAPSHOT` | `maven.fabricmc.net` metadata |
| 映射 | **无 Yarn**：`meta.fabricmc.net/v2/versions/yarn/26.3` 返回 `[]` → 走 Loom 默认的 Mojang 官方映射（与 vineflower 反编译名一致） |
| Java | toolchain 26 / `release 25`（与现有工程一致） |

### 2.2 Vanilla 26.3 服务端钩子（`server-26.3.jar` javap 实证）

- 推送：`new ClientboundResourcePackPushPacket(UUID id, String url, String hash, boolean required, Optional<Component> prompt)`
- 回执：`ServerboundResourcePackPacket(UUID id, Action action)`，`Action` 含 ACCEPTED / DECLINED / SUCCESSFULLY_LOADED / FAILED_DOWNLOAD / INVALID_URL / FAILED_RELOAD / DISCARDED
- 处理点：`net.minecraft.server.network.ServerCommonPacketListenerImpl#handleResourcePackResponse(ServerboundResourcePackPacket)`（public，**在 common 而不是 game listener**）
- 发消息：`ServerPlayer#sendSystemMessage(net.minecraft.network.chat.Component)`
- 连接句柄：`ServerPlayer.connection`（public 字段，`ServerGamePacketListenerImpl`）
- Vanilla 自带包：`MinecraftServer#getServerResourcePack()` / `isResourcePackRequired()`（server.properties 那条），只处理它自己的 pack id，不会误判我们的随机 UUID

### 2.3 客户端 26.3 真值表（Paper 版已实测，Fabric 版必须一致）

| 客户端档位 | required=false | required=true 且 announce-required=false | required=true 且 announce-required=true |
| --- | --- | --- | --- |
| Prompt | 弹原版框 | 弹框（客户端强制） | 弹框 |
| Enabled | 静默下载 | 静默下载 | 静默下载 |
| Disabled | 静默下载（走 pushPack） | **0 弹窗、静默 DECLINED、服务端断开** | 弹 Proceed/Disconnect，点 Disconnect 客户端自断 |

> 三种档位 + 两个布尔一共 6 格，Fabric 侧要逐格复测，语义由共享内核决定，适配层只负责发包与回执翻译。

### 2.4 现有工程的平台耦合面（`rg '^import (org\.bukkit|io\.papermc|net\.kyori)'`）

| 文件 | 耦合 |
| --- | --- |
| 原文件（重构前） | 耦合 | 现在的位置 |
| --- | --- | --- |
| `delivery/PackPusher.java` | Player、Listener、PlayerJoinEvent/QuitEvent、PlayerResourcePackStatusEvent、Adventure | `paper/src/main/java/moe/nekomc/resourcepack/delivery/PackPusher.java` |
| `ResourcePackServicePlugin.java` | JavaPlugin 生命周期 | `paper/src/main/java/moe/nekomc/resourcepack/ResourcePackServicePlugin.java` |
| `api/PackStatusEvent.java` | `extends org.bukkit.event.Event` | `paper/src/main/java/moe/nekomc/resourcepack/api/PackStatusEvent.java` |
| `core/EngineConfig.java` | `FileConfiguration` | 已进 `:core`，改读 `platform/ConfigSource` |
| `CommunityBindingListener.java` | PluginEnableEvent / PluginDisableEvent | `paper/src/main/java/moe/nekomc/resourcepack/CommunityBindingListener.java` |
| `config/RpsConfig.kt` | `FileConfiguration` | `core/src/main/kotlin/.../config/RpsConfig.kt`，已改读 `ConfigSource` |
| `watch/SourceWatcher.kt` | Plugin + Folia `ScheduledTask` | `core/src/main/kotlin/.../watch/SourceWatcher.kt`，已改走 `platform/Scheduler` |
| `pack/PackHost.kt` | `org.bukkit.Server`、Plugin | `core/src/main/kotlin/.../pack/PackHost.kt`，只剩平台无关端口 |
| `command/RpsCommand.kt` | CommandExecutor/TabCompleter/CommandSender/Player + Adventure | `core/src/main/kotlin/.../command/RpsCommand.kt`，已去 Bukkit |
| `command/RpsCommandHost.kt` | `org.bukkit.Server` | 拆成 `core/.../command/CommandHost.kt` + `paper/.../platform/BukkitCommandBridge.kt` |
| `pack/PackPublisher.kt` | 仅 Adventure MiniMessage | `core/src/main/kotlin/.../pack/PackPublisher.kt`，已改走 `PlayerRef` |

`merge/*`、`delivery/PackHttpServer.java`、`delivery/DeliveryStatusStore.java`、`core/{Hashing,SourceRegistry,StateStore,ZipSource}` 无平台依赖，直接进 `:core`。

测试现状：19 个测试类 / 92 项，其中只有 `PluginDescriptorTest`、`config/RpsConfigAnnounceRequiredTest` 触碰 Bukkit，其余可原样落在 `:core`。语言分工与规模见 §4。

## 3. 仓库与模块布局

```
resource-pack-service/
├─ settings.gradle.kts          # include(":core", ":paper", ":fabric")
├─ core/                        # 平台无关内核（java-library + kotlin，不依赖 Bukkit / Adventure）
│   ├─ src/main/java/moe/nekomc/resourcepack/{api,core,delivery,merge,pack,platform}
│   ├─ src/main/kotlin/moe/nekomc/resourcepack/{command,config,core,merge,pack,watch}
│   └─ src/test/...             # 92 项里的 85 项
├─ paper/                       # Paper / Folia 适配层
│   ├─ src/main/java/moe/nekomc/resourcepack/{ResourcePackServicePlugin.java,delivery,api,platform}
│   ├─ src/main/kotlin/moe/nekomc/resourcepack/{command,platform}
│   ├─ src/main/resources/{plugin.yml,config.yml}
│   └─ src/test/...             # PluginDescriptorTest、RpsConfigAnnounceRequiredTest
├─ fabric/                      # Fabric 26.3 服务端 mod（loom + JiJ，不用 shadow / remapJar）
│   └─ src/main/{java,resources}/...
├─ rust/rps-pack/               # 两平台共用，构建时各自暂存进 jar 的 rps-pack/bin
└─ tools/, packs/, docs/
```

迁移策略：**先抽 `:core` 让 paper 回绿，再加 `:fabric`**，避免一次改两件事。当前根工程变成聚合器，公共配置（kotlin 版本、toolchain、jvmTarget、测试平台）放 `buildSrc` 约定插件或根 `subprojects {}`。

## 4. 三语言分层（Java / Kotlin / Rust）

现有仓库的**实况**（`wc -l` 统计，2026-11-26）：

| 语言 | 规模 | 承担什么 | 依赖方向 |
| --- | --- | --- | --- |
| Java | 30 文件 / 2650 行 | **契约与 IO 原语**：`api/*` 接口与 record、`core/{Hashing,ZipSource,Issue,EngineConfig}`、`delivery/{PackHttpServer,DeliveryStatusStore,Sha1Hex}`、`merge/{Merger,MergeResult,MergeException,RustPackMerger,BundledRust,JsonMerge,FormatRange}`、`pack/{Host,HostStatus,PackSnapshot,Community*}`、平台入口 | 不依赖 Kotlin、不依赖 Rust 源码 |
| Kotlin | 19 文件 / 3385 行 | **编排与胶水**：`config/RpsConfig`、`command/{RpsCommand,PackMessages,RpsCommandHost}`、`core/{SourceRegistry,StateStore}`、`pack/{PackHost,PackPublisher,CommunityIndex,CommunitySelector,CommunityUpdater,PackConflictScanner,EntryManifest,ConflictReport,VersionRange,RegisteredSources}`、`watch/SourceWatcher`、`merge/Mergers` | 调 Java 内核（单向） |
| Rust | 13 个 `.rs` / 2109 行（含 tests） | **唯一的合并实现**：`engine/archive/crc/jsonmerge/mcmeta/model/error`，依赖 `serde / serde_json / sha1 / zip 9`（release: LTO + strip） | 独立进程，只吃 zip 出 zip；JVM 侧通过 `RustPackMerger` 调它 |

**关键事实（写进计划，别写错）**：`merge/Mergers.kt:15` 的注释就是答案 ——「合并实现只有 Rust 一个」。二进制缺失时抛 `MergeUnavailableException`，**不降级**到 Java 实现。`JsonMerge.java` 是同一套合并规则的 Java 对照实现（校验/测试用），不是运行时兜底。这意味着 Fabric 版必须随 jar 携带 `rps-pack-<os>-<arch>`，缺了就是构建失败，而不是变慢。

### 4.1 分层规则（两平台通用，开工后照此落码）

1. **Rust 不认平台**：只接参数、进出都是 zip。唯一要平台化的地方是解包目录 —— `BundledRust.ensure(Path dataFolder, ...)` 已把 `dataFolder` 参数化，Fabric 传 `config/resource-pack-service` 即可，**Rust 侧零改动**。
2. **Java 是地基**：两个平台共享的「有明确契约」的东西留 Java —— 接口、record、HTTP 宿主、哈希、状态文件格式、合并器调用与解包。理由：Paper 的 `Event`/`plugin.yml`、Fabric 的 `ModInitializer`/`Mixin`/`Packet` 都是 Java 类型，Java 层是两边唯一不需要互操作转换的共同语。
3. **Kotlin 是胶水**：配置、命令树、状态机编排、文件监听、发布流程 —— 流程代码用 Kotlin 收益最大；但它们**只允许依赖 Java 层与平台 SPI**，不允许 import `org.bukkit` / `net.minecraft`。
4. **禁止跨层反向依赖**：Java 不调 Kotlin（`ResourcePackServicePlugin` 作为入口是唯一例外）；Rust 不感知 JVM。判定线：`:core` 里出现 `import org.bukkit` 即视为拆分失败。

### 4.2 三个模块 × 三种语言

| 模块 | Java | Kotlin | Rust |
| --- | --- | --- | --- |
| `:core` | `api/*`、`delivery/*`、`core/{Hashing,ZipSource,Issue}`、`merge/*`（含 `RustPackMerger`、`BundledRust`）、`platform/*` SPI 接口、`pack/{Host,HostStatus,PackSnapshot}` | `config/RpsConfig`、`command/*`（去 Bukkit 化后的命令树）、`core/{SourceRegistry,StateStore}`、`pack/{PackHost,PackPublisher,Community*}`、`watch/SourceWatcher`、`merge/Mergers` | —（只消费 `rps-pack` 二进制） |
| `:paper` | `ResourcePackServicePlugin`（JavaPlugin 入口）、`paper/BukkitPackDelivery`、`paper/BukkitStatusBus` | `paper/BukkitConfigSource`、`paper/FoliaScheduler`、`paper/BukkitPlayerDirectory`、`paper/BukkitPermissions`、`CommunityBindingListener` | — |
| `:fabric` | `RpsFabricMod`（`ModInitializer`）、`mixin/ServerCommonPacketListenerMixin`、`FabricPackDelivery`（发 `ClientboundResourcePackPushPacket` + `sendSystemMessage`）、`FabricStatusBus` | `FabricPlatform`、`FabricConfigSource`（SnakeYAML）、`TickScheduler`、`FabricPlayerDirectory`、`FabricCommand`（Brigadier 注册） | — |

**语言分层不因平台变，只多一层薄适配**：`:core` 的 Java+Kotlin 组合被两平台原样复用；平台模块里「必须贴 MC/Bukkit 类型」的（发包、mixin、入口、事件）用 Java，「读写配置 / 注册命令 / 调度包装」这类流程用 Kotlin。Rust 一份，两边共用。

### 4.3 构建侧的语言配置（三处必须一致）

- Kotlin 插件 `2.4.21` 应用到 `:core` / `:paper` / `:fabric`，`jvmToolchain(26)` + `jvmTarget = JVM_25`。
- `tasks.withType<JavaCompile> { options.release = 25 }` 三个模块都要设，Java 与 Kotlin 目标不一致会直接编译失败（现有工程注释已写明）。
- `kotlin("stdlib")` 用 `implementation` 打进两个 shadow jar（Paper 版现在就自带，Fabric 版同理约 +1.7 MB）。
- Rust 产物由根工程 `stageRustBinary` 一次构建，两个模块各自 `from(layout.buildDirectory.dir("dist/bin")) into("rps-pack/bin")`；文件名以 `BundledRust.platformName()` 为唯一依据 —— 构建命名与运行时查找共用一套规则，别写两份。

### 4.4 这样分层对 Fabric 移植的实际影响

- **要重写**的只有：`PackPusher`（Bukkit 事件 → Fabric 事件 + 发包）、`SourceWatcher`（Folia `ScheduledTask` → tick 计数）、`RpsConfig` 的 YAML 读取（`FileConfiguration` → SnakeYAML）、命令注册（`CommandExecutor` → Brigadier）、玩家表/权限（`Bukkit.getOnlinePlayers` → `server.getPlayerList()`）。
- **不用动**的：`merge/*` Java 全部、`PackHttpServer`、`DeliveryStatusStore`、`Hashing/ZipSource`、`PackPublisher` 的合并编排、`Community*`（Fabric 版直接关掉这条能力）、Rust 全部。

## 5. 平台 SPI（新增到 `:core`）

```java
// core/src/main/java/moe/nekomc/resourcepack/platform/Platform.java
public interface Platform {
    Logger logger();
    Path dataDirectory();                 // paper: plugins/ResourcePackService；fabric: config/resource-pack-service
    Scheduler scheduler();                // paper: Folia/Bukkit 调度；fabric: 主线程 + tick 计数
    PlayerDirectory players();            // onlinePlayers / byId / byName
    PackDelivery delivery();              // 发包 + 发消息
    PermissionGate permissions();         // paper: 权限节点；fabric: op 等级
    ConfigSource config();                // paper: FileConfiguration；fabric: SnakeYAML
    StatusEventBus statusBus();           // 平台事件派发（Paper 发 Bukkit 事件，Fabric 发自定义事件）
}

public interface PackDelivery {
    void push(PlayerId player, String url, String sha1, boolean required, String promptMiniMessage);
    void sendMessage(PlayerId player, String miniMessage);
}
```

要点：
- `ConfigSource` 只需 `isSet/getBoolean/getInt/getString/getStringList/getSection`，`RpsConfig` 的解析逻辑一行不改即可双平台复用。
- `Scheduler` 只暴露 `repeat(Duration, Runnable)` / `later(Duration, Runnable)` / `onMain(Runnable)`；Paper 实现走 `plugin.getServer().getGlobalRegionScheduler()`（Folia 现有路径），Fabric 走 `ServerTickEvents.END_SERVER_TICK` 计数 + `server.execute`。
- `PackStatusEvent` 不再放共享 api：`:core` 保留 `BuildResult / PackStatus / PackSource / ResourcePackService` 四个平台无关类型；`PackStatusEvent` 各平台一份（Paper 版 `extends Event`，Fabric 版是普通类 + `EventFactory`）。这对下游意味着：联动插件换成 Fabric 版要改的只有事件 import。

## 6. Fabric 适配层清单

> 本节是立项时的设想。实际落地的文件与构建形态见 §12，两处不一致的地方以 §12 为准。

| 文件（fabric/src/main/java/moe/nekomc/resourcepack/fabric/） | 职责 |
| --- | --- |
| `RpsFabricMod.java` | `ModInitializer`：读配置 → 建 `PackHost` → 注册生命周期 |
| `FabricPlatform.java` | `Platform` 实现：logger（`LoggerFactory`）、dataDirectory（`FabricLoader.getInstance().getConfigDir().resolve("resource-pack-service")`）、调度器 |
| `FabricPackDelivery.java` | `player.connection.send(new ClientboundResourcePackPushPacket(uuid, url, sha1, required, Optional.ofNullable(prompt)))`；`sendSystemMessage(Component.literal(legacyText))` |
| `FabricMessages.java` | MiniMessage → Adventure Component → `LegacyComponentSerializer.legacySection()` 串 → `Component.literal`（§ 格式在 literal 里客户端仍渲染）；Adventure 作为普通依赖打进 jar，不依赖服务端提供 |
| `FabricCommand.java` | `CommandRegistrationCallback.EVENT`：`rps status|rebuild|push <player>|reload|sources`；权限 = `source.hasPermission(2)`（op），不引权限插件 |
| `FabricConfigLoader.java` | SnakeYAML 读 `ConfigSource`（SnakeYAML 随 MC 服务端提供，compileOnly 声明） |
| `mixin/ServerCommonPacketListenerMixin.java` | `@Inject(method = "handleResourcePackResponse(Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket;)V", at = @At("TAIL"))`：取 `packet.id()/action()`，翻译成 `PackStatus` 交给内核（DECLINED / SUCCESSFULLY_LOADED / FAILED_DOWNLOAD / INVALID_URL / DISCARDED） |
| `RpsFabricEvents.java` | `ServerLifecycleEvents.SERVER_STARTED`（起 HTTP + watcher）/ `SERVER_STOPPING`（停）/ `ServerPlayConnectionEvents.JOIN`（进服推送）/ `DISCONNECT`（清状态） |

`fabric.mod.json` 关键字段：
```json
{
  "id": "resource-pack-service",
  "version": "${version}",
  "environment": "server",
  "entrypoints": { "main": ["moe.nekomc.resourcepack.fabric.RpsFabricMod"] },
  "mixins": ["resourcepackservice.mixins.json"],
  "depends": { "fabricloader": ">=0.20.1", "minecraft": "~26.3", "java": ">=25", "fabric-api": "*" }
}
```

配置路径：`config/resource-pack-service/config.yml`，键与 Paper 版完全一致（`provider.required`、`provider.announce-required`、`provider.prompt`、`provider.reject-message`、`push.mode`、`http.*`、`merge.policy`、`watch.*`）。README 现有配置表可直接复用，只补一句“Fabric 版配置文件放 `config/resource-pack-service/config.yml`”。

## 7. 构建与发行

`:fabric/build.gradle.kts`：
```kotlin
plugins { id("net.fabricmc.fabric-loom") version "1.19.4"; id("com.gradleup.shadow") }
dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.167.0+26.3")
    implementation(project(":core"))
}
tasks.shadowJar { archiveBaseName.set("resource-pack-service-fabric"); archiveVersion.set(project.version.toString()) }
// loom 的 remapJar 必须吃 shadow 的输出，否则嵌套依赖不会被 remap
tasks.remapJar { inputFile.set(tasks.shadowJar.flatMap { it.archiveFile }) }
```

- Rust 合并器：复用根工程 `stageRustBinary`，把 `build/dist/bin/rps-pack-<os>-<arch>[.exe]` 打进 `rps-pack/bin`（与 Paper 版同一段逻辑，抽成 `buildSrc` 里的函数）。
- 产物名：`resource-pack-service-<ver>.jar` / `resource-pack-service-fabric-<ver>.jar`。
- `.github/workflows/auto-release.yml` 原用 `ls build/libs/resource-pack-service-*.jar | head -n1`，**会撞两个包**（已在 §12.6 落地为显式两份） → 改成显式两份（paper 用 `resource-pack-service-[0-9]*.jar`，fabric 用 `*-fabric-*.jar`），Release 资产同时上传。
- CI：`ci.yml` 加 `:fabric:build`；CodeQL 保持 Java/Kotlin。
- 版本号：`fabric.mod.json` 的 `version` 用 `processResources expand`（与 Paper `plugin.yml` 一致）。

## 8. 测试策略

| 层 | 内容 |
| --- | --- |
| `:core` 单测 | 现有 90 项（merge / hashing / state / config 解析 / 命令解析）原样跑，零 Bukkit |
| `:paper` 单测 | `PluginDescriptorTest`、Bukkit `FileConfiguration` 版配置测试 |
| `:fabric` 单测 | SnakeYAML `ConfigSource` 实现 + 回执翻译（`Action` → `PackStatus`）纯函数测试 |
| 集成 | `./gradlew :fabric:runServer` 起 26.3 服务端，`/rps rebuild` + `curl http://127.0.0.1:25580/pack/merged.zip` |
| 真机 | 26.3 客户端按 §2.3 六格逐格复测（Prompt/Enabled/Disabled × required/announce-required），并复测 T1/T2/T5/T6/T7 清单 |

## 9. 阶段计划

| 阶段 | 内容 | 验收 | 预估 |
| --- | --- | --- | --- |
| P0 | 空 Fabric 模组跑通：loom 版本二选一（1.19.4 vs 1.19-SNAPSHOT）、`genSources` 确认 `handleResourcePackResponse` 签名、`runServer` 起 26.3 | `./gradlew :fabric:runServer` 出 Done，mixin 能编译 | 0.5 天 |
| P1 | 抽 `:core` + 平台 SPI，`:paper` 适配回绿 | 92 项测试全绿；Paper jar 真机进服加载成功 | 1.5 天 |
| P2 | Fabric 骨架：配置、HTTP、`/rps status|rebuild`、Rust 合并器落地 | runServer 里 `/rps rebuild` 出包，curl 可取 | 1 天 |
| P3 | 投递与回执：push 包 + mixin + announce-required 语义 | 真机六格表复现；状态可查 | 1 天 |
| P4 | 命令/权限/消息打磨（op 等级、MiniMessage→§、Tab 补全） | 命令全量可用，中文标点不乱码 | 0.5 天 |
| P5 | 构建发行：jar 命名、CI、auto-release 双资产、deploy 脚本 | Release 同时出两个 jar，校验 sha1 | 0.5 天 |
| P6 | 文档：README Fabric 段、`docs/design.md` 分层、`docs/integration.md` 双平台接入 | 文档无 Paper 专有措辞 | 0.5 天 |

## 10. 风险与对策

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| Loom 版本 | 26.3 只有 `1.19-SNAPSHOT` 被 example 采用，`1.19.4` 未必认 26.3 | P0 先试稳定版，不行锁 snapshot 并在文档写清 |
| 映射缺失 Yarn | 26.3 的 Yarn 为空 → 只能官方映射；与 Paper 的 mojmap 一致，反倒是好事 | 全部代码用 mojmap 名，不写混淆名 |
| Mixin 脆弱 | `handleResourcePackResponse` 在 `ServerCommonPacketListenerImpl`，26.x 小版本可能挪位置 | P0 起加 `mixin audit` 断言（`-Dmixin.debug.export=true` 跑一次）；升级 MC 时先跑该项 |
| Adventure 版本 | core 需要显式依赖 adventure-api/minimessage，版本必须与 paper-api 自带的一致，否则运行时类冲突 | P1 用 `./gradlew :paper:dependencies` 读出 paper-api 的 adventure 版本，core 按同版本 `compileOnly`（Paper 侧）+ `implementation`（Fabric 侧 shadow） |
| Kotlin 体积 | Fabric jar 需自带 kotlin-stdlib（约 1.7 MB） | 与 Paper 版一致；如后续想瘦身，可把 fabric 适配层改成纯 Java，core 仍 Kotlin |
| 双平台语义漂移 | 同一配置两套实现容易分叉 | 内核与 SPI 全共享，适配层只做翻译；六格真机表作为回归清单 |
| Vanilla 自带包冲突 | server.properties 里的 `resource-pack` 与 mod 推送并存 | 我们的 push 用随机 UUID，vanilla 只认自己的 id；文档提示两处不要同时开 |

## 11. 待确认（开工前问一次）

1. Fabric 版是否也要 `announce-required` 这个开关（建议：要，语义与 Paper 完全一致）。
2. Fabric 版命令权限用 op 等级 2 是否够（不引 `fabric-permissions-api`）。
3. 是否需要在 Fabric 版保留「玩家拒绝后给提示」这条聊天消息（Paper 版现在是 `push.mode` 相关逻辑）。

## 12. 实施记录（2026-11-27）

P0–P3 已落地并在真机跑通，P5（发行/CI）与 P6（对外文档）未做。

### 12.1 实际构建形态（与 §7 计划的差异）

| 计划 | 实际 | 原因 |
| --- | --- | --- |
| shadow + `remapJar` 吃 shadow 输出 | **不用 shadow**：`include(project(":core"))` / `include("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")` / `include("org.yaml:snakeyaml:2.4")`，loom 打出 `META-INF/jars/*` | 构建日志里根本没有 `remapJar` 任务；loom 1.19.4 对 26.3 不做 remap |
| 需要 refmap | **不需要**：26.3 生产 jar 类名明文（`minecraft-26.3-client.jar` 里 11266 个 `net/minecraft/` 条目可读） | 26.x 已不混淆，mixin 按方法名直接匹配 |
| 产物 `resource-pack-service-fabric-<ver>.jar` | 已按计划落地：`base { archivesName.set("resource-pack-service-fabric") }`，输出 `fabric/build/libs/resource-pack-service-fabric-<ver>.jar` | 与 Paper 产物同风格，Release 里一眼区分 |

其它固定项：loom `1.19.4`、MC `26.3`、loader `0.20.1`、fabric-api `0.167.0+26.3`、toolchain 26 / `release 25`、Rust 二进制由 `:fabric:stageRustBinary` 打进 `rps-pack/bin/rps-pack-windows-x86_64.exe`。

### 12.2 实际文件清单（`fabric/src/main/`，12 个源文件 + 2 个资源）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `fabric/RpsFabricMod.java` | 73 | `DedicatedServerModInitializer`：启动自检、命令注册、生命周期与加入/退出事件接线 |
| `fabric/FabricPlatform.java` | 611 | `PackHost + CommandHost` 实现：配置装配、HTTP 出口、watcher、社区包、reload 顺序、`runOnServer` |
| `fabric/FabricPackDelivery.java` | 307 | 发包、回执状态映射、去重、必装踢人、状态提示 |
| `fabric/FabricConfigSource.java` | 118 | SnakeYAML `Map` 上的 `ConfigSource`，点号取值、类型回退 |
| `fabric/TickScheduler.java` | 145 | 自建 `Scheduler`：主线程 tick 扫到期任务，任务跑守护线程池 |
| `fabric/MiniMessages.java` | 164 | MiniMessage 极小子集 → 原版 `Component`（无 Adventure） |
| `fabric/FabricCommands.java` | 63 | Brigadier `/rps`（greedy 参数）+ 内核补全 |
| `fabric/FabricCommandSender.java` | 53 | `CommandSourceStack` → `CommandSenderRef`，权限用 op 表折算 |
| `fabric/FabricPlayerRef.java` | 24 | `ServerPlayer` → `PlayerRef` |
| `fabric/FabricLog.java` | 54 | JUL → slf4j 桥（内核用 JUL，不桥接就只进 stdout、不进 `logs/latest.log`） |
| `fabric/CompatibilityCheck.java` | 33 | 启动时反射确认 `handleResourcePackResponse` 仍在，防 mixin 静默失效 |
| `fabric/mixin/ServerCommonPacketListenerMixin.java` | 36 | `@Inject(at = TAIL)`，只观测不取消（TAIL 才会跑在主线程） |
| `resources/fabric.mod.json` | 22 | id/entrypoint/mixins/depends |
| `resources/resource-pack-service.mixins.json` | 12 | `server: [ServerCommonPacketListenerMixin]` |

内核侧配套改动：`core` 新增 `command/CommandSenderRef.kt`、`command/CommandHost.kt`，`RpsCommand.kt` 从 Paper 模块搬进 core 并去掉 Bukkit 依赖；`PackDelivery` 补 `baseUrl/publish/name/detail/recent`；`DeliveryStatusStore` 的 `record/status/hash/recent/remove` 改为 public。Paper 侧新增 `platform/{BukkitConfigSource,BukkitPlayerRef,FoliaScheduler,BukkitCommandSender,BukkitCommandBridge}`。

### 12.3 实施中踩到的三个真问题

1. **`InetAddress.getLocalHost()` 给出 169.254 链路本地地址** → 推送地址变成客户端连不上的 `http://169.254.31.232:25581/...`。已在 `FabricPackDelivery` 加 `localAddress()`：优先站点内网 IPv4，其次其它非回环 IPv4，最后才回退本机名；实测变成 `http://192.168.31.24:25581/pack/merged.zip?v=1`。
2. **同一次回执被投递两遍**：26.3 的 `handleResourcePackResponse` 在 Netty 线程与主线程各走一次，mixin 的 HEAD 注入会进两次（日志里一行 `Netty NIO IO #2`、一行 `Server thread`）。已在 `onResponse` 里按 `(玩家, 状态, sha1)` 去重，避免重复日志与 `required` 时重复排程断开。
3. **JUL 日志不进 `logs/latest.log`**：内核的 `PackHost.logger()` 是 `java.util.logging.Logger`，Fabric 默认只写 stdout，排查时在服务器日志里什么都看不到。已加 `FabricLog` 把 JUL 记录转交 slf4j。

### 12.4 真机验证（2026-11-27 01:23 / 01:26，`server-fabric` 25566）

配置：`provider.required: false` + `announce-required: true`，客户端档位保持默认（Prompt）。

| 观察点 | 结果 |
| --- | --- |
| 进服弹窗 | 必装框：中文原版两行 + `来自服务器的消息：Fabric 测试：请点「继续」加载资源包`（黄色），按钮「继续 / 断开连接」 |
| 点「继续」后下载 | `netstat` 见 `192.168.31.24 → :25581 TIME_WAIT`，服务端无 404 |
| 服务端回执 | `[01:26:41] [Netty NIO IO #3/INFO]: [资源包] 玩家 Sakana07X 已加载资源包`（去重后仅 1 行；去重前是 Netty + Server thread 各 1 行） |
| 推送次数 | `[01:26:38] ... 向玩家 Sakana07X 推送资源包（必装提示=true）` 仅 1 行 |
| 命令层（RCON） | `/rps status`、`/rps list`、`/rps diff`、`/rps push`、`/rps build` 全部返回中文结果 |
| 合成与出口 | `merged/merged.zip` 538 B、第 1 版、sha1 `50d39a64b862271c2958bef0e7bcc272531cd0a2`、格式 97（97–97）、引擎 rust；`curl` → `200 538` |
| 日志落点 | 桥接后内核日志出现在 `logs/latest.log`（`[resource-pack-service-1/INFO]`、`[Netty NIO IO #3/INFO]`） |

环境坑：26.3 生成的 `server.properties` 里 **`white-list` 默认 `true`**，空 `whitelist.json` 会把客户端挡在"你不在此服务器的白名单中"；测试服已 `whitelist add` 并改回 `white-list=false`。

### 12.5 剩余项

- 跨平台发布同 Paper：Fabric jar 只在构建平台带对应 `rps-pack`，Windows 产物不能直接用于 Linux 服务端。这属于发布约定而不是缺陷——Release 的矩阵本来就按平台各出一份；现在 jar 里还带 `rps-pack/bin/platform.txt`，装错平台时启动日志会直接说"这个 jar 是为 X 构建的"。
- 尚未在 CI 上跑过 `:fabric`（首次要下 loom 1.19.4 + MC 26.3 + fabric-api），本地构建与真机运行已验。

### 12.6 已完成收尾（2026-11-27）

- **静默踢人已真机确认**（用户手动验证）：`provider.required: true` + `announce-required: false` + 客户端「服务器资源包」档位 Disabled → 不弹窗，服务端按 `reject-message` 断开。
- 产物改名：`fabric/build.gradle.kts` 加 `base { archivesName.set("resource-pack-service-fabric") }`，输出 `fabric/build/libs/resource-pack-service-fabric-<ver>.jar`。
- CI（`ci.yml`）：上传 `resource-pack-service-paper-linux-x86_64` 与 `resource-pack-service-fabric-linux-x86_64` 两个 artifact（原先 `build/libs/` 路径在多模块下已失效，本次一并修正）。
- Auto Release：触发路径加 `core/**`、`paper/**`、`fabric/**`；发行资产同时带 Paper 与 Fabric 两个 jar；发行说明新增「产物」段。
- Release：每个平台产出 `resource-pack-service-<ver>-<platform>.jar` 与 `resource-pack-service-fabric-<ver>-<platform>.jar`。
- Deploy：新增 `variant`（paper / fabric）输入，按变体挑 jar，落地名分别为 `resource-pack-service.jar` / `resource-pack-service-fabric.jar`。
- 文档：README 安装段拆 Paper / Fabric 两节、构建产物与目录结构按模块重写；`docs/ci.md` 的 CI / Release / Deploy / Auto Release 段同步；`docs/integration.md` 新增「Fabric 服务端」说明（无第三方注册入口）。
- **第三方资源包扫描补上 Fabric**：`CommandHost.conflictScanRoot()` 换成 `conflictScanRoots(): List<Path>`（Paper 返回 `plugins/`，Fabric 返回 `config/`）；`PackConflictScanner` 新增 `scanRoots`，除原有 `config.yml` 布局外还认 `*.toml` / `*.properties` / 扫描根下散放的 `*.yml`（键允许 `server-resource-pack` 这类前缀），`/rps check` 两端行为一致；单测从 6 个扩到 10 个。
- **Deploy 目录拆分**：新增 `DEPLOY_MODS_DIR` secret，Fabric 变体投 `mods/`，未配置时回落 `DEPLOY_PLUGINS_DIR`；同时修正 `deploy.yml` 里 `variant` 输入误缩进到 `platform` 下面的问题（此前 `inputs.variant` 实际不存在）。
- **平台标记**：`paper` / `fabric` 两个构建脚本新增 `writePlatformMarker`，把 `rps-pack/bin/platform.txt` 打进 jar；`BundledRust.ensure` 在解不出当前平台二进制时读它并警告具体是哪个平台的产物。

### 12.7 审计修复轮（2026-11-27）

二次审计（功能对等 / 内核并发 / Fabric 运行时 / 构建发行）共 15 条 P1，全部处理完，其中改动落在：

- **内核**：`PackPublisher` 引入构建轮次号，`rebuildAsync` 只完成"本次及更早轮次"的 waiter（此前并发时 future 会拿到上一轮结果）；`enabled: false` 的来源不再进合成；`EntryManifest` 与公告各自 try/catch（包已落盘却判失败会回滚 current 并僵死 ETag）；`RustPackMerger` 改守护线程泵 stdout + 主线程 `waitFor(timeout)`（此前超时永不生效），request 带上 `enabled`；`PackHttpServer` 用同一 `FileChannel` 取大小并流式写出（此前先 `Files.size` 再 copy，换包瞬间会写超/截断），`start()` 同步、端口越界抛 `IOException`；`RpsConfig` 端口 `coerceIn(0, 65535)`、`community.dir` 支持平台默认值；`StateStore` 临时文件 + ATOMIC_MOVE；`SourceWatcher` 全同步 + `interval <= 0` 明确告警；`/rps conflicts` 需要 `rps.conflicts` 或 `rps.admin`。
- **Fabric**：`DISCARDED` 不再映射成 DECLINED（Paper 也不记录不踢）；`closed` 后不再发包；`FabricConfigSource.getString` 与非字符串取值对齐 Bukkit（`pack-format: 97` 不再退回 auto）；`readConfig` 解析失败直接抛错而不静默回默认；`reload` 先把配置与来源全部解析完再换字段，换字段之后的通道启停逐项降级（单个通道起不来只告警、不回滚），首次装配失败则明确报「服务未启用」；`TickScheduler` 改固定线程池 + `nanoTime` + 异常记日志；`MiniMessages` 支持 `<click:run_command>/<click:suggest_command>` 与 `<u>`、`\<` 转义；命令源权限按原版权限位折算（控制台不再无条件全权）；mixin 注入点从 HEAD 改 TAIL（HEAD 会跑在 Netty 线程）；生命周期回调统一包 `safe(...)`，单个配置错误不再带崩服务端。
- **构建 / 发行**：Auto Release 发布前把产物 `cp` 成 `resource-pack-service-<ver>-linux-x86_64.jar` / `...-fabric-<ver>-linux-x86_64.jar`（与 `deploy.yml` 的 `*-<platform>.jar` 对齐）；CI / Release / Auto Release 都断言 jar 内含 `rps-pack/bin/rps-pack*`；`--code-paths` 与 auto-release paths 补 `gradle/**`、`gradle.properties`；`stageRustBinary` 改 `Sync`（跨平台重复构建不再留同名双条目）；platform marker 任务加 inputs 并在没有二进制时删旧标记；`fabric.mod.json` 的 fabric-api 依赖收紧到 `>=0.167.0`。
- **已评估不改**：kotlin-stdlib 不做 relocate —— Bukkit 每个插件独立类加载器，jar 内的 `kotlin/` 不会与别的插件互相顶替；代价只是包体大一点。
