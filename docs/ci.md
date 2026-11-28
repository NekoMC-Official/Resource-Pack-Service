# 持续集成与发布

仓库自带七条 GitHub Actions 工作流，全部位于 `.github/workflows/`。

## CI（`ci.yml`）

- 触发：推送到 `main`、任意 Pull Request、手动 `workflow_dispatch`。
- 环境：`ubuntu-latest` + Python 3.12 + Zulu JDK 26 + Rust stable。
- 步骤：`python -m unittest discover -s tools`（绑定选择与变体规则）→ `cargo test` → `cargo build --release` → `./gradlew build`（一次构建 Paper 与 Fabric 两个模块；`RPS_PACK_BIN` 只给单元测试定位真实二进制，构建脚本不读它）→ 断言两个 jar 里都有 `rps-pack/bin/rps-pack*`（缺失即失败）→ `python tools/check_jar_size.py`（体积守卫：Paper ≤ 2,750,000 B、Fabric ≤ 2,900,000 B，超限 exit 1）→ 上传两个产物：`resource-pack-service-<版本>.jar`（Paper/Folia）与 `resource-pack-service-fabric-<版本>.jar`（Fabric 服务端）。默认版本 `0.1.0`，工作流内可用 `-Pversion=` 覆盖。
- 构建机是 Linux，所以产物里的内置合并器是 **linux-x86_64**，可直接部署到 Linux 服务端。

## Release（`release.yml`）

- 触发：推送 `v*` 标签，例如 `git tag v0.1.0 && git push origin v0.1.0`。
- 矩阵：每个平台产出两个 jar —— `resource-pack-service-<版本>-<平台>.jar` 与 `resource-pack-service-fabric-<版本>-<平台>.jar`；`ubuntu-latest` → `linux-x86_64`，`windows-latest` → `windows-x86_64`。
- 版本号通过 `-Pversion=` 注入 Gradle，`plugin.yml` 的 `version`、`fabric.mod.json` 的 `version` 与两个 jar 的文件名都会跟着变。
- 四个 jar 全部附加到对应 GitHub Release，并生成发行说明。

> jar 内只带**构建平台**的 `rps-pack`。Linux 服务端请用 linux 产物；Windows 产物只用于 Windows 服务端或在 Windows 上做合并。

## Deploy（`deploy.yml`）

手动触发，只做“投递文件”，不重启服务端。

| 输入 | 说明 |
|---|---|
| `tag` | 已发布的标签，例如 `v0.1.0` |
| `platform` | `linux-x86_64`（默认）或 `windows-x86_64` |
| `variant` | `paper`（默认）或 `fabric`，决定挑哪个 jar、落地文件叫什么名字 |

需要的仓库 Secrets：

| Secret | 含义 |
|---|---|
| `DEPLOY_HOST` | 目标主机 |
| `DEPLOY_USER` | SSH 用户 |
| `DEPLOY_SSH_KEY` | 私钥（对应公钥已加入目标机 `authorized_keys`） |
| `DEPLOY_PLUGINS_DIR` | Paper/Folia 的插件目录，例如 `/srv/nekomc-folia/server/plugins` |
| `DEPLOY_MODS_DIR` | Fabric 的模组目录，例如 `/srv/nekomc-fabric/server/mods`；不填时回落到 `DEPLOY_PLUGINS_DIR` |

工作流按 `variant` 挑出对应 jar，把现有同名文件备份成 `<名字>.bak-<时间戳>`，再原子替换，并输出远端 `sha256sum`。Paper 变体落地名是 `resource-pack-service.jar`，投到 `DEPLOY_PLUGINS_DIR`；Fabric 变体落地名是 `resource-pack-service-fabric.jar`，投到 `DEPLOY_MODS_DIR`（没配则用 `DEPLOY_PLUGINS_DIR`，适合两个目录共用一套部署脚本的旧环境）。

建议在仓库 Settings → Environments 里给 `production` 配置 Required reviewers，避免误触。

## 发布流程

```sh
# 1. 本地确认
pwsh -NoProfile -Command "./gradlew.bat build"

# 2. 打标签（版本号与 build.gradle.kts 默认值一致时可省略 -Pversion）
git tag v0.1.0
git push origin v0.1.0

# 3. 等 Release 工作流产出一个 Linux jar
# 4. 手动跑 Deploy，填 tag、平台与变体（paper / fabric）
# 5. 维护窗口 COLD 重启，启动后执行 /rps check 与 /rps status
```

## 本地构建与 CI 的差异

- 本地在 Windows 上构建，两个 jar 内置的都是 `rps-pack-windows-x86_64.exe`，不能拿到 Linux 服务端跑；产物分别在 `paper/build/libs/` 与 `fabric/build/libs/`。
- 发布用 jar 一律以 Release 工作流的 Linux 产物为准。
- 本地没有 `cargo` 时 `./gradlew build` 仍能过，但 jar 内没有合并器，运行期构建资源包会报错；发布前确认 CI 的 Rust 步骤为绿。

## Pack PR（`pack-pr.yml`）

- 触发：PR 改动 `packs/**`、`tools/packs.py` 或本工作流自身。
- 使用 `pull_request_target` 取得写评论权限，但**不执行 PR 里的任何代码**：基础分支脚本负责校验，PR 内容只当作数据读取。
- 步骤：用基础分支脚本校验并打包 `packs/` → 按绑定关系生成预览目录 → 把绑定表、sha1、冲突与未纳入原因作为置顶评论更新到 PR（校验失败也会评论错误）→ 上传预览产物。
- 校验内容：`pack.yml` 的 `plugin` / `relation` / `release` / `also` / `variant` / `default` / `version` / `minecraft` / `requires`，id 与目录名一致，`pack.mcmeta` 合法且能推导出 `format_min` / `format_max`，路径穿越与符号链接，条目数与解压体积上限，命名空间存在。
- 绑定不完整或变体冲突的包不会进入预览目录，原因会写进 PR 评论。
- PR 分支的 zip 与合并结果只作为 Actions 产物，不会进入仓库，也不会自动发行。

## Auto Release（`auto-release.yml`）

- 触发：`main` 上改动 `packs/**`、`core/**`、`paper/**`、`fabric/**`、`rust/**`、`build.gradle.kts`、`settings.gradle.kts`、`tools/packs.py` 或 `auto-release.yml` 自身；也可手动触发。
- 发行计划：`tools/packs.py plan` 以最近 `v*` 标签为基线，用 `git cat-file` 判断包在上个版本是否已存在，用 `git diff` 判断 `core`、`paper`、`fabric`、`rust`、构建脚本等代码路径是否有变更。
- 新包闸门：新增的 `release: auto` 包不会自动批准；维护者必须手动触发工作流，并在 `force_packs` 输入里点名一次。只新增包、没有代码变更时 `should_release=false`，后续构建与发布步骤全部跳过，不会创建版本。
- 打包：只把 `approved-packs.txt` 里的包传给 `packs.py bundle --only`；未批准、未点名的包不会进入 `community-packs.zip`。如果批准集合的绑定关系不完整（例如 addon 缺同插件 official），工作流直接失败。
- 包变更闸门：`plan` 额外输出 `packs_changed`（`force_packs` 点名、已批准包内容变更、新批准包，或上个标签里的包被删除/撤出时为 true）。为 false 时只发插件 jar，跳过 `community-packs.zip`、索引上传与 Pages 部署。
- 版本闸门：已发行包的内容文件有改动时，`pack.yml` 的 `version` 必须严格大于上一个 `v*` 标签里的同 id 包；否则发行计划失败，避免内容变了但客户端与缓存仍认为是同一版。
- 托管索引：同一个批准集合会传给 `packs.py index --only`，生成 `community-index.json` 与 `objects/<sha1>.zip`，上传为 Pages artifact 并部署到 `https://nekomc-official.github.io/Resource-Pack-Service/`。没有已批准包但发生撤包时，仍会生成空索引并部署，避免远程继续下载已撤下的包。未批准的包不会出现在索引里，因此不会因为合并进 `main` 就对外可用。
- Pages：仓库的 Pages Source 必须设为 GitHub Actions；工作流需要 `pages: write` 与 `id-token: write`。
- 服务端消费：把 `community.remote.enabled` 设为 `true`，插件会定时拉取索引，只下载本机已安装插件对应的包，全部下载与 sha1 校验通过后才整体切换 `manifest.json`，失败保留旧清单与旧包；`/rps community update` 可立即检查。离线部署仍可用 `community-packs.zip`。
- 版本：取最近 `v*` 标签后补丁号加一；还没有标签时从 `v0.1.0` 开始。发行说明列出上次标签以来的提交与资源包报告。
- 产物：两个 Linux 版 jar —— `resource-pack-service-<版本>.jar`（Paper/Folia）与 `resource-pack-service-fabric-<版本>.jar`（Fabric 服务端），都内含 `moe.nekomc.resourcepack.api` 接口，供联动插件编译期依赖；有已批准资源包时再加 `community-packs.zip`、`pack-summary.md` 与 `community-index.json`；只有撤包时只更新索引，不生成 `community-packs.zip`。索引会部署到 Pages。
- 跳过：提交信息含 `[skip release]`，或提交者是 Dependabot。
- 循环保护：机器人用 `GITHUB_TOKEN` 创建标签与 Release 不会再次触发工作流；手动推 `v*` 标签仍走原来的 `release.yml` 双平台矩阵。

自动发行只负责构建与发布，不会部署到服务器；部署继续走 `deploy.yml` 的手动投递。

## CodeQL（`codeql.yml`）

- 触发：推送到 `main`、面向 `main` 的 PR、每周一 03:27 UTC 定时扫描、手动 `workflow_dispatch`。
- 两个 job：`java-kotlin` 与 `rust`。Java/Kotlin 用 JDK 26 手动编译（`./gradlew compileJava compileKotlin --no-daemon --no-build-cache --rerun-tasks`，强制真实编译，避免缓存恢复导致 CodeQL 看不到编译过程）；Rust 用 `build-mode: none`，由 CodeQL 自己解析依赖，编译验证交给 CI。
- 权限：`contents: read`、`actions: read`、`security-events: write`，只把结果写入 Security → Code scanning。
- 公开仓库的 CodeQL 免费；结果在仓库 Security 页与 PR 的检查项里看。
- 首次扫描需要基础分支已启用 Code scanning；如果 Security 页没有结果，先确认这两个 job 是否成功，再检查默认分支设置。
- 不扫描构建产物与 `rust/target`；异常提交信息不含敏感内容。

## Labeler（`labeler.yml`）

- 触发：`pull_request_target` 的 opened / synchronize / reopened / ready_for_review。
- 使用 `actions/labeler@v7`，按 `.github/labeler.yml` 给 PR 打路径标签：`java`、`kotlin`、`rust`、`documentation`、`ci`、`pack`。
- `sync-labels: false`：只加标签，不因为后续提交移除已有标签，避免维护者手动改过的标签被覆盖。
- 工作流不 checkout PR 代码，只在基础分支配置下运行，避免来自 fork 的代码执行。
- 新增顶层目录时，记得在 `.github/labeler.yml` 补一条规则；否则 PR 不会自动带标签。