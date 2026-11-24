# 社区资源包

这个目录放可以随仓库一起评审、构建和发行的资源包。每个子目录是一个独立资源包，构建时各自打成 zip，再合并成一个可直接下发的整包。

## 目录结构

    packs/
      _template/          模板，不会被构建
      my-pack/
        pack.yml          元数据
        pack.mcmeta       原版元数据
        pack.png          可选图标
        assets/<命名空间>/... 材质、模型、语言文件

## pack.yml

    id: my-pack
    name: 我的资源包
    priority: 0
    description: 一句话说明
    license: CC-BY-4.0

- id 必须与目录名一致，只能用小写字母、数字、下划线和短横线。
- priority 越大越靠上层；同一路径冲突时高优先级覆盖低优先级。相同优先级按 id 排序，靠后的在上。
- license 必填，写清楚材质来源与授权方式；没有授权的内容不要提交。
- 每个包必须自带 pack.mcmeta，pack_format 或 supported_formats 至少有一个，并与目标客户端版本匹配。

## 命名空间

材质放在 assets/<命名空间>/ 下。请使用自己的命名空间（例如 assets/my_pack/），不要占用 minecraft、bettermodel 等已有命名空间，否则会和其他来源冲突。

## 提交之后

1. 机器人会校验 pack.yml、pack.mcmeta、路径与体积，再合并全部资源包。
2. 合并结果、冲突条目和 sha1 会作为一条评论更新到 PR 上。
3. 合并进 main 后，自动发行工作流会打出插件 jar 与 community-pack.zip，附到新的 Release 上。

合并规则：高优先级覆盖同路径低优先级；内容完全相同的条目去重；语言文件、sounds.json、font、atlases 这类 JSON 会做结构合并；pack.mcmeta 由合并器重建。详见 docs/design.md。

## 在服务器上使用

1. 从 Release 下载 community-pack.zip，放到插件数据目录，例如 plugins/Resource Pack Service/community-pack.zip。
2. 在 config.yml 的 sources 里增加一项：

       - id: "community"
         enabled: true
         path: "plugins/Resource Pack Service/community-pack.zip"
         priority: 0

3. 执行 /rps reload，再用 /rps status 确认这个来源已经进入合成。

如果只想要其中一个包，也可以直接下载 build/packs 里对应的 zip，作为 sources 的一项登记（或注册成 PackSource）。
