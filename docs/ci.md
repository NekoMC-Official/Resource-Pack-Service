# 持续集成与发布

仓库自带七条 GitHub Actions 工作流，全部位于 `.github/workflows/`。

## CI（`ci.yml`）

- 触发：推送到 `main`、任意 Pull Request、手动 `workflow_dispatch`。
- 环境：`ubuntu-latest` + Zulu JDK 26 + Rust stable。
- 步骤：`cargo test` → `cargo build --release` → `./gradlew build`（通过 `RPS_PACK_BIN` 指向刚编出的合并器）→ 上传 `resource-pack-service.jar`。
- 构建机是 Linux，所以产物里的内置合并器是 **linux-x86_64**，可直接部署到 Linux 服务端。

## Release（`release.yml`）

- 触发：推送 `v*` 标签，例如 `git tag v0.1.0 && git push origin v0.1.0`。
- 矩阵：`ubuntu-latest` 产出 `resource-pack-service-<版本>-linux-x86_64.jar`，`windows-latest` 产出 `...-windows-x86_64.jar`。
- 版本号通过 `-Pversion=` 注入 Gradle，`plugin.yml` 里的 `version` 会跟着变。
- 两个 jar 自动附加到对应 GitHub Release，并生成发行说明。

> jar 内只带**构建平台**的 `rps-pack`。Linux 服务端请用 linux 产物；Windows 产物只用于 Windows 服务端或在 Windows 上做合并。

## Deploy（`deploy.yml`）

手动触发，只做“投递文件”，不重启服务端。

| 输入 | 说明 |
|---|---|
| `tag` | 已发布的标签，例如 `v0.1.0` |
| `platform` | `linux-x86_64`（默认）或 `windows-x86_64` |

需要的仓库 Secrets：

| Secret | 含义 |
|---|---|
| `DEPLOY_HOST` | 目标主机 |
| `DEPLOY_USER` | SSH 用户 |
| `DEPLOY_SSH_KEY` | 私钥（对应公钥已加入目标机 `authorized_keys`） |
| `DEPLOY_PLUGINS_DIR` | 插件目录，例如 `/srv/nekomc-folia/server/plugins` |

工作流会先把现有 jar 备份成 `resource-pack-service.jar.bak-<时间戳>`，再原子替换，并输出远端 `sha256sum`。

建议在仓库 Settings → Environments 里给 `production` 配置 Required reviewers，避免误触。

## 发布流程

```sh
# 1. 本地确认
pwsh -NoProfile -Command "./gradlew.bat build"

# 2. 打标签（版本号与 build.gradle.kts 默认值一致时可省略 -Pversion）
git tag v0.1.0
git push origin v0.1.0

# 3. 等 Release 工作流产出一个 Linux jar
# 4. 手动跑 Deploy，填 tag 与 linux-x86_64
# 5. 维护窗口 COLD 重启，启动后执行 /rps check 与 /rps status
```

## 本地构建与 CI 的差异

- 本地在 Windows 上构建，jar 内置的是 `rps-pack-windows-x86_64.exe`，不能拿到 Linux 服务端跑。
- 发布用 jar 一律以 Release 工作流的 Linux 产物为准。
- 本地没有 `cargo` 时 `./gradlew build` 仍能过，但 jar 内没有合并器，运行期构建资源包会报错；发布前确认 CI 的 Rust 步骤为绿。

## Pack PR（`pack-pr.yml`）

- 触发：PR 改动 `packs/**`、`tools/packs.py` 或本工作流自身。
- 使用 `pull_request_target` 取得写评论权限，但**不执行 PR 里的任何代码**：基础分支脚本负责校验，PR 内容只当作数据读取。
- 步骤：编译 `rps-pack` → 校验并打包 `packs/` → 合并 → 把结果作为置顶评论更新到 PR（构建失败也会评论错误）→ 上传预览产物。
- 校验内容：`pack.yml` 必填项、id 与目录名一致、`pack.mcmeta` 合法、路径穿越与符号链接、条目数与解压体积上限、命名空间存在。
- PR 分支的 zip 与合并结果只作为 Actions 产物，不会进入仓库，也不会自动发行。

## Auto Release（`auto-release.yml`）

- 触发：`main` 上改动 `packs/**`、`src/**`、`rust/**`、`build.gradle.kts` 或 `tools/packs.py`；也可手动触发。
- 机器人：`github-actions[bot]`，使用仓库自带 `GITHUB_TOKEN`，不需要额外 Secrets。
- 版本：取最近 `v*` 标签后补丁号加一；还没有标签时从 `v0.1.0` 开始。发行说明列出上次标签以来的提交与资源包报告。
- 产物：Linux 版插件 jar；仓库里有资源包时再加 `community-pack.zip`、合并报告与 `pack-summary.md`。
- 跳过：提交信息含 `[skip release]`，或提交者是 Dependabot。
- 循环保护：机器人用 `GITHUB_TOKEN` 创建标签与 Release 不会再次触发工作流；手动推 `v*` 标签仍走原来的 `release.yml` 双平台矩阵。

> 自动发行只负责构建与发布，不会部署到服务器；部署继续走 `deploy.yml` 的手动投递。

## CodeQL（`codeql.yml`）

- 触发：推送到 `main`、面向 `main` 的 PR、每周一 03:27 UTC 定时扫描、手动 `workflow_dispatch`。
- 两个 job：`java-kotlin` 与 `rust`。Java/Kotlin 用 JDK 26 手动编译（`./gradlew compileJava compileKotlin --no-daemon`）；Rust 直接 `cargo build --release`。
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