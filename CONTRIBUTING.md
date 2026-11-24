# 参与贡献

本项目以 MIT 协议开源。欢迎提交 Issue 与 Pull Request，包括新增资源包来源、
修复合并逻辑、补充文档。

## 贡献者协议（DCO）

我们采用 **Developer Certificate of Origin 1.1**（DCO），不要求签单独的 CLA。
含义很直白：你确认自己有权提交这段代码，并同意它按本仓库的 MIT 协议发布。

每个提交加 `-s` 即视为签署：

```sh
git commit -s -m "feat(source): 支持 YourPlugin 的资源包"
```

提交信息里会出现一行：

```
Signed-off-by: 你的名字 <你的邮箱>
```

名字与邮箱须真实可联系。忘加时用 `git commit --amend -s`（未推送）或
`git rebase --signoff main`（已推送）补齐。PR 中任何未签署的提交都会被要求补签。

> 为什么是 DCO 而不是 CLA：MIT 项目不需要额外的授权转让，DCO 已经能证明
> "你有权提交、并同意以本项目协议发布"，对贡献者负担最小。

### DCO 1.1 原文

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.

Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## 提 PR 前

1. 从 `main` 开分支，命名 `feat/<主题>`、`fix/<主题>`、`docs/<主题>`；不要包含模型名或会话信息。
2. `./gradlew build` 必须通过；改到 `rust/rps-pack` 时 `cargo test` 也要全绿。
3. 行为变化要带测试：Java 侧放 `src/test/java/moe/nekomc/resourcepack/`，Rust 侧放 `rust/rps-pack/tests/`。
4. 不提交构建产物（`build/`、`target/`）、IDE 配置、本地服务器文件。
5. 提交信息用 `<type>(<scope>): <中文或英文短句>`，例如 `feat(merge): 支持 font 按 key 深合并`。

## 接入一个新的资源包来源

### A. 对方插件能实现本服务的 `PackSource`

不需要改本仓库。对方按 README 的
[给插件作者](README.md#给插件作者让自己的资源包接进来) 一节实现 `PackSource` 并
`register` 即可，本仓库无需任何改动。

### B. 对方只产出 zip 文件、又不能改

需要在本仓库加默认来源，走 PR：

1. `src/main/resources/config.yml` 的 `sources:` 下增加条目：`id`、`path`、`priority`。
2. 产出时机特殊（重载时重写、异步生成）时在 `watch/` 补钩子，或在 PR 里说明轮询为何够用。
3. 加测试：用最小 zip 验证该来源能合入、且不会覆盖掉其它来源的同名条目。
4. 更新 `docs/design.md` 的来源表。
5. PR 描述写清：插件名与版本、产出路径、是否自带 `pack.mcmeta`、`pack_format` 范围、
   文件重写时机、命名空间、期望优先级、是否需要 `required`。

评审通过后合入 `main`，随**下一个发行版本**发布（默认 MINOR 递增）。等不及发行时，
运维方在本地配置里加一条 `sources` 即可立即生效。

## 评审与发行

- 每个 PR 至少一名维护者审阅；涉及合并算法的改动需要附对拍结果（相同输入两次构建 sha1 一致）。
- 发行按语义化版本打 tag（`v0.2.0`），发行说明列出新增来源与合并行为变化。
- 合并包内容一变，客户端就要重下整包，因此发行说明会标注"是否改变既有合成结果"。

## 行为准则

讨论对事不对人。不接受人身攻击、泄露他人隐私信息、提交来源不明的第三方资源包内容。
