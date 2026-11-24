# 设计说明

## 1. 目标与边界

服务端一次只能下发一个资源包，客户端拿到的是完整 zip。本插件解决两件事：

1. **多来源合成**：把若干插件的资源包按层序合并成一个，冲突可解释。
2. **单点注册**：只向下发宿主登记一个合成包来源，其余插件不再各自抢下发权。

对外只暴露本服务自己的标准：`moe.nekomc.resourcepack.api.PackSource` 与
`moe.nekomc.resourcepack.api.ResourcePackService`，全部是 JDK 类型，不含任何宿主插件类型。

不做的事：自己调用 `Player#setResourcePack`、另开推送通道、修改下发宿主的配置。

## 2. 对外标准 API

| 类型 | 作用 |
|---|---|
| `api.PackSource` | 来源标准：`id()`、`file()`、`priority()`，可选 `enabled()`、`sha1()`、`namespaces()` |
| `api.ResourcePackService` | 统一入口：`register/unregister/sourceIds/version/url/sha1/rebuild/push` |

插件通过 `Bukkit.getServicesManager().load(ResourcePackService.class)` 取得入口。
登记来源会触发异步重建；`push(uuid, force)` 立即重推。版本号由本服务按合成结果统一递增，
接入方不需要维护。同 id 与配置文件来源冲突时，注册的优先；`/rps reload` 后会重新投回。

### 2.1 下发宿主的内部契约

实际推送由宿主插件承担，属于实现细节，接入方不必关心。宿主通过 Bukkit Services 暴露
`CompanionPackApi`，`register(PackProvider)` 即接管下发。要点（以宿主侧实现为准）：

| 项 | 约定 |
|---|---|
| `id()` | 唯一槽位标识，本插件固定 `resourcepack-service/active` |
| `version()` | 要求版本，正整数且单调递增；URL 的 `?v=` 必须等于它 |
| `url()` | http(s) 完整地址 |
| `sha1()` | 合成包的 sha1 十六进制；空表示不校验 |
| `priority()` | 大者优先；同优先级先注册者胜（合成包对其它来源仍按层序合入） |
| `required()` | true 等价强制推送，客户端拒绝即断开 |
| `prompt()` | 非空作为下载提示语 |

注册不会自动重推在线玩家；需要立即生效时调用 `api.push(uuid, false)`。玩家进服 1 tick 内由
宿主自动同步。

## 3. 语言分层

| 层 | 语言 | 包 | 规则 |
|---|---|---|---|
| 宿主 | Java | `moe.nekomc.resourcepack`、`bridge`、`api` | Bukkit 生命周期、反射桥接、record 边界类型 |
| 编排 | Kotlin | `command`、`config`、`watch` | 配置解析、命令树、报告渲染 |
| 计算 | Rust | `rust/rps-pack` | zip 读写、JSON 合并、sha1、pack.mcmeta 重建 |

跨语言边界**只传 JDK 类型与 Java record**。Kotlin 的 data class 不出 Kotlin 包；不用默认参数、
扩展函数、`object` 单例做跨边界 API。`jvmTarget` 与 `options.release` 必须同档（当前 25）。

Rust 通过 **sidecar 进程**接入，不走 JNI/Panama：

- 不改 `server/start.sh` 加 `--enable-native-access`；
- panic 只让本次构建失败，保留上一版合成包；
- 一个可执行文件同时可被 CI 调用。

## 4. 合并语义

### 4.1 层序

`priority` 小的先叠，大的后叠，后叠覆盖先叠。同 `priority` 保持 `config.yml` 中的书写顺序。

### 4.2 冲突分类

| 类型 | 判定 | 处置 |
|---|---|---|
| 内容重复 | 同名条目 CRC 与大小一致 | 去重，计一次 |
| 路径冲突 | 同名但内容不同 | 高优先级胜，记入冲突报告 |
| 元数据 | `pack.mcmeta` | 丢弃各来源的，按交集重建一份 |
| 可合并 JSON | `lang/*.json`、`sounds.json`、`font/*.json`、`atlases/*.json` | 结构合并 |
| 命名空间共用 | 两个来源写同一 `assets/<ns>/` | 仅报告，不阻断 |
| 格式区间 | 各来源 `supported_formats` 无交集 | 构建失败，保留上一版 |
| 图标 | `pack.png` | 取最高层 |

策略 `override`（默认）/ `strict`（有冲突即失败）/ `first`（低层胜，排查用）。

### 4.3 JSON 合并

- `lang/*.json`、`sounds.json`：深层合并，高层覆盖同键。
- `font/*.json`、`atlases/*.json`：数组按层序从高到低拼接，按 `toString()` 去重。
- 其余 JSON 一律按路径覆盖，不做猜测性合并。

### 4.4 确定性

输出条目按名称排序，时间戳固定为 1980-01-01，`pack.mcmeta` 由 `pack_format` 与
`supported_formats` 交集重建。同一输入必得同一 sha1，因此"内容没变就不重建、不推送、不重下"。

## 5. 增量更新的现实

客户端下载资源包是完整 GET，没有 Range、没有 delta、没有断点续传。服务端能做的只有两件：

1. **同 sha1 不重下**：客户端按 hash 复用本地已下载的包。
2. **减少变更频率与体积**：把高频变更挪出包外。

据此实现三项可观测能力：

- `/rps diff`：对比上一版条目索引，列出本次变更的文件与所属来源。
- `merge.warn-delta-bytes`：单次压缩前新增/变更字节超阈值时告警。
- 分层建议：扫描 `data/` 下的配方、进度、函数，提示可移到 datapack（客户端零下载）。

"只传差异"在协议层不成立；真增量需要客户端 mod，超出本插件范围。

## 6. rps-pack 协议

```
rps-pack merge --request - --output <path>
```

stdin：

```json
{"format":0,"description":"NekoMC 统一资源包","policy":"override","json_merge":true,
 "sources":[{"id":"bettermodel/base","path":"plugins/BetterModel/build.zip","priority":-100}]}
```

stdout（exit 0）：

```json
{"ok":true,"sha1":"...","bytes":12345,"entries":678,"deduped":2,
 "format":46,"format_min":46,"format_max":60,
 "conflicts":[{"path":"assets/x/y.png","winner":"a","losers":["b"]}],
 "warnings":[],"millis":88}
```

失败 exit 2，stdout 为 `{"ok":false,"error":"..."}`，诊断走 stderr。

Java 侧 `FallbackMerger` 在非零退出、超时（默认 60s）、JSON 非法时回落 `JavaPackMerger`，
连续 3 次失败锁定 Java 到下次 reload。

## 7. 构建产物

| 产物 | 位置 | 投放 |
|---|---|---|
| 插件 jar | `build/libs/resource-pack-service.jar` | `server/plugins/` |
| 合并器 | `build/dist/bin/rps-pack.exe`（Linux 无后缀） | `plugins/Resource Pack Service/bin/` |

二进制不打进 jar：jar 内可执行需运行时解压与 chmod，且临时目录里的 exe 容易被杀软误报。

## 8. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 安装 rustup（`x86_64-pc-windows-msvc`），空 crate 编译通过 | `cargo build` 成功 |
| M1 | 构建骨架：Kotlin + shadow + stdlib，`jvmTarget`/`release` 对齐 25（JDK 26 编译） | `./gradlew build` 绿 |
| M2 | 配置解析与命令迁 Kotlin，record 边界不动 | 命令可跑 |
| M3 | 抽出 `PackMerger` 接口，`JavaPackMerger` 归位 | 单测绿，行为不变 |
| M4 | `rps-pack` 实现合并 + `cargo test` | CLI 手跑真包 |
| M5 | ~~`RustPackMerger` + `FallbackMerger` + `engine` 配置~~ → 改为二进制内置、单一引擎 | 删掉 jar 内资源后启动报可读错误，不回退 |
| M6 | 双实现对拍、`/rps diff`、文档与部署说明 | 输出一致 |

## 9. 验收

1. `./gradlew build` 与 `cargo test` 全绿。
2. 固定 fixture 下 Java 与 Rust 实现：条目名集合一致、`pack.mcmeta` 结构一致、冲突列表一致；
   各自 sha1 内部稳定（不要求跨实现相同）。
3. 删除 `bin/rps-pack` 后 `engine: auto` 静态回落，构建仍成功。
4. 源文件未变化时重复构建不递增世代号，不触发推送。
5. `server.properties` 的 `resource-pack` 未留空时，`/rps check` 报错。

## 10. 风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 切换引擎导致 sha1 变化 | 全体客户端重下一次 | 一次性；世代号继续单调递增 |
| 双实现语义漂移 | 结果不一致 | fixture 对拍 |
| jar 增至约 1.8 MB | reload 类加载略慢 | 已接受，stdlib 为唯一新增依赖 |
| Rust 工具链缺失 | 只能走 Java 实现 | 构建自动跳过，功能不缺 |
| 合成包过大 | 客户端下载慢、进服卡 | 体积告警 + 分层建议 |

## 11. 实现进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 | 完成 | rustup 1.29.3，rustc/cargo 1.100.0（x86_64-pc-windows-msvc） |
| M1 | 完成 | Kotlin 2.4.21 + shadow 9.6.1，resource-pack-service.jar 约 1.9 MB |
| M2 | 完成 | 命令层由 Kotlin 提供（command/RpsCommand.kt）；配置解析仍留在 Java 的 RpsConfig record —— 它是跨语言边界类型，按第 3 节边界规则不搬 |
| M3 | 完成 | Merger 接口 + RustPackMerger（唯一实现，Java 版已删除）；PackPublisher 按需取用 |
| M4 | 完成 | rust/rps-pack：14 个 cargo test 全绿，release 产物 752 KB |
| M5 | 完成 | rps-pack 二进制随 jar 内置并自动解出；缺失时构建直接报错，不回退 |
| M6 | 完成 | EntryManifest 记录条目 sha1，/rps diff 报新增/变更/删除；双实现对拍测试 |

### 11.1 已修正的合并缺陷

初版 Java 合并器在层序上取反（低优先级胜），且 lang/sounds 的结构合并未真正生效。重写后由 PackMergerTest 覆盖：双层 lang 合并、同名路径高层覆盖、同内容去重、同输入两次 sha1 一致。

### 11.2 已落地类

| 层 | 类 | 职责 |
|---|---|---|
| Java | ResourcePackServicePlugin | 生命周期、reload、异步调度入口 |
| Java | api/PackSource、api/ResourcePackService | 对外标准：来源接口与统一入口，纯 JDK 类型 |
| Java | pack/RegisteredSources | 维护插件注册的来源，投进合并注册表；reload 后重新投回 |
| Java | pack/PackConflictScanner | 扫 `plugins/*/config*.yml` 里的资源包地址/开关，供 `/rps check` 提示疑似自带下发的插件 |
| Java | bridge/DispatchBridge | 反射接下发宿主：动态代理 provider，注册/刷新/推送/注销 |
| Java | pack/PackPublisher | 单飞构建、staging 转 merged.zip、世代递增、push.mode 处理 |
| Java | core/ZipSource | 合并层的文件来源（id/path/priority/sha1/命名空间） |
| Java | pack/PackView | 提供者取值接口（版本/地址/sha1 随快照变化） |
| Java | watch/SourceWatcher | Folia AsyncScheduler 轮询来源 mtime |
| Kotlin | command/RpsCommand | /rps status、list、build、reload、push、check、diff 与补全 |
| Kotlin | command/PackMessages | MiniMessage 文案 |
