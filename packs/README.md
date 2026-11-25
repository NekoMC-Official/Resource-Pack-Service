# 社区资源包

这个目录放可以随仓库一起评审、构建和发行的资源包。每个子目录是一个独立资源包，构建时各自打成 zip；发行时生成 `community-packs.zip`，里面是 `manifest.json` 加每个包的独立 zip。服务端解压后，插件按本机已安装并启用的插件再次筛选，只把匹配的包纳入合成。

## 目录结构

```text
packs/
├── _template/              模板，不会被构建
└── my-pack/
    ├── pack.yml            元数据，不会打进客户端 zip
    ├── pack.mcmeta         原版元数据
    ├── pack.png            可选图标
    └── assets/<命名空间>/... 材质、模型、语言文件
```

## pack.yml

```yaml
id: my-pack
name: 我的资源包
license: CC-BY-4.0
priority: 0

# 内容版本：包内文件有实质变化时递增
version: 1.2.0

# 兼容的 Minecraft 版本范围；留空表示不限
minecraft: ">=26.3 <26.4"

# 依赖插件的版本范围；运行期按已安装插件版本判断
requires: ">=1.4.0 <2.0.0"

# 绑定关系：official / addon / patch / integration / standalone
# standalone 不绑定具体插件，必须写 plugin: universal
plugin: companion
relation: official
release: auto

# 同一插件的互斥变体组；只给一个包写 default: true
variant: ""
default: false
```

`pack.yml` 只接受扁平的 `key: value`，不要写嵌套 YAML。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 必须与目录名一致；只允许小写字母、数字、下划线和短横线 |
| `name` | 是 | 展示名称 |
| `license` | 是 | 材质来源与授权方式；没有授权的内容不要提交 |
| `plugin` | 是 | 绑定的插件 id；standalone 写 `universal` |
| `relation` | 是 | 见下面的绑定关系表 |
| `release` | 是 | `auto` / `manual` / `none`，见发行策略 |
| `version` | 否 | 内容版本，默认空；包内文件有实质变化时应递增 |
| `minecraft` | 否 | Minecraft 版本范围，支持空格 AND 与逗号 OR；空表示不限 |
| `requires` | 否 | 依赖插件的版本范围；空表示不限制 |
| `priority` | 否 | 合并层序，越大越靠上；默认 0 |
| `variant` | 否 | 互斥变体组标签；同一组只能有一个 `default: true` |
| `default` | 否 | 是否为该组默认项，默认 `false` |
| `also` | 仅 integration | 同时服务的其它插件 id，逗号分隔 |

包内除 `pack.yml` / `README.md` 以外的文件有改动时，`version` 必须比上一个发行标签里的同 id 包更大；否则发行计划直接失败，避免内容变了但客户端与缓存仍认为是同一版。

每个包必须自带 `pack.mcmeta`，其中 `pack.pack_format` 或 `pack.supported_formats` 至少有一个。仓库构建会从 `supported_formats` 推导 `format_min` / `format_max`，写进 manifest；没有 `supported_formats` 时两端都等于 `pack_format`。

## 绑定关系

| relation | 含义 | 依赖 |
| --- | --- | --- |
| `official` | 某插件的官方资源包 | 不同 `minecraft` 范围可以各有一个 default official 包 |
| `addon` | 在官方包之上追加内容 | 必须有同插件的 official 包 |
| `patch` | 覆盖官方包的少量路径 | 必须有同插件的 official 包 |
| `integration` | 同时服务多个插件 | 主插件与 `also` 里的每个插件都要有 official 包 |
| `standalone` | 不绑定具体插件 | `plugin` 必须是 `universal` |

同一个插件可以有多个 official 包（例如默认版与节日主题）：`minecraft` 范围不同的包可以各写 `default: true`，运行期按服务端版本筛选；兼容后仍多于一个时按 `priority` 降序选一个，`priority` 相同按 `id` 选一个，其余跳过。official 不使用 `variant` 做互斥，每个插件最终只有一个生效的 official 包。非 official 的 addon / patch / integration 用 `variant` 组成互斥组，同一组只给一个包写 `default: true`。

运行期还会检查：

- 声明的插件必须已安装并启用；`addon` / `patch` 的同插件 official 必须在候选里。
- `integration` 的主插件与 `also` 里每个插件都必须有 official 包。
- `minecraft` 必须接受服务端版本；`format_min` / `format_max` 必须覆盖服务端的 pack format；`requires` 必须接受已安装插件的版本。
- 非 official 的互斥组出现多个 `default: true` 时整组跳过，并在日志与 `/rps status` 里说明原因；official 组在运行期兼容后仍多于一个 `default` 时按 `priority` 降序、`priority` 相同按 `id` 选一个，其余跳过，不会整组跳过。

## 发行策略

| release | 是否进入社区目录 | 自动发行 |
| --- | --- | --- |
| `auto` | 是 | 已批准过的包在后续变更中自动发行 |
| `manual` | 是，但只在手动发行时进入 | 否 |
| `none` | 否 | 否，只做校验并在报告里列为未纳入 |

**新增资源包不会因为合并进 main 就自动发行。** 第一次必须由维护者手动触发 Auto Release 工作流，并在 `force_packs` 里填这个包的 id；批准过一次之后，它才进入“已存在且 `release: auto`”的自动发行集合。

如果一次提交只新增了包、没有改代码，Auto Release 会直接跳过，不会创建新版本。新包会留在报告里，等维护者确认。

批准是按包计算的，而且必须形成完整的绑定集合。例如同时新增 addon 和它的 official 时，`force_packs` 要把两个 id 都写上；只写 addon 会在发行计划阶段报“绑定关系不完整”，不会生成空包或残缺包。

## 命名空间

材质放在 `assets/<命名空间>/` 下。请使用自己的命名空间（例如 `assets/my_pack/`），不要占用 `minecraft`、`bettermodel` 等已有命名空间，否则会和其他来源冲突。

## 提交之后

1. `pack-pr` 用基础分支的脚本校验 `pack.yml`、绑定字段、`pack.mcmeta`、路径与体积，生成预览目录。
2. 绑定表、sha1、冲突条目与未纳入原因会作为一条评论更新到 PR 上。
3. 合并进 main 后，Auto Release 只发行已经批准的包；新包需要维护者手动 `force_packs` 一次。
4. 有已批准资源包时，发行产物还包括 `community-packs.zip` 与 `pack-summary.md`；只有代码变更时只发插件 jar。`community-packs.zip` 里是 `manifest.json` 与每个包的独立 zip。

Auto Release 还会把已批准的资源包索引发布到 GitHub Pages：`https://nekomc-official.github.io/Resource-Pack-Service/community-index.json`，包文件在 `objects/<sha1>.zip`。索引只包含本次批准的包，未批准的新包不会出现。

合并规则：高优先级覆盖同路径低优先级；内容完全相同的条目去重；语言文件、`sounds.json`、font、atlases 这类 JSON 会做结构合并；`pack.mcmeta` 由合并器重建。详见 [docs/design.md](../docs/design.md)。

## 在服务器上使用

1. 二选一：
   - 离线：从 Release 下载 `community-packs.zip`，解压到插件数据目录下的 `community/`。
   - 远程：保持 `community.remote.enabled: true`，插件会自动拉取 Pages 索引，只下载本机已安装插件对应的包；也可以执行 `/rps community update` 立即检查。
2. 确认配置（远程方式把 `remote.enabled` 设为 `true`）：

```yaml
community:
  enabled: true
  dir: "plugins/ResourcePackService/community"
  priority: 0
  remote:
    enabled: false
```

3. 执行 `/rps reload`，再用 `/rps status` 与 `/rps list` 核对纳入与跳过的包。

社区包不需要写进 `sources`。插件会在服务启动、`/rps reload` 以及插件启停时重新计算绑定。如果只想要其中一个包，也可以直接下载对应的 `<id>.zip`，作为 `sources` 的一项登记，或注册成 `PackSource`。
