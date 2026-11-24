# rps-pack

资源包合并器的独立实现，供插件以子进程方式调用，也可在 CI 中单独运行。
协议见 [docs/design.md](../../docs/design.md) 第 4 节（合并语义）与第 6 节（rps-pack 协议）。

## 用法

```sh
cargo build --release
echo '{"format":0,"description":"test","policy":"override","json_merge":true,
       "sources":[{"id":"base","path":"build/base.zip","priority":0}]}' \
  | ./target/release/rps-pack merge --request - --output out.zip
```

- `--request -` 从 stdin 读 UTF-8 JSON；也可传文件路径。
- 成功 exit 0，stdout 一行 JSON：`{ok:true, sha1, bytes, entries, deduped, format,
  format_min, format_max, conflicts, warnings, millis}`。
- 失败 exit 2，stdout 一行 `{ok:false,error}`，诊断写 stderr。
- 用法错误 exit 64。

## 合并语义

1. 来源按 `priority` 升序叠层（小的先叠），同 priority 保持请求里的数组顺序。
2. 同名条目 CRC 与大小一致 → 去重计数；内容不同 → 按 `policy`：
   `override` 高层胜并记 conflicts、`strict` 直接失败、`first` 低层胜并记 conflicts。
3. 各来源的 `pack.mcmeta` 丢弃，按 supported_formats / pack_format 的闭区间交集重建：
   交集为空失败；`format > 0` 覆盖 pack_format，否则取交集最大值；输出 supported_formats 为 `[min,max]`。
4. `json_merge` 为真时：`assets/*/lang/*.json`、`assets/*/sounds.json` 深层合并（高层覆盖同键）；
   `assets/*/font/*.json`、`assets/*/atlases/*.json` 的数组按高层在前拼接并按序列化去重；
   其余 JSON 与普通文件一样按路径覆盖。
5. `pack.png` 固定取最高层（不算冲突，不受 policy 影响）。

没有 `pack.mcmeta` 的来源视为“不提供格式约束”，照常参与合并且条目进入结果；
只有全部来源都没有 `pack.mcmeta` 且请求的 `format` 为 0 时才失败（请求 `format > 0` 时以它为准）。

## 安全闸与确定性

- 条目名含 `..`、以 `/` 开头或带盘符 → 失败（zip slip）。
- 单条目解压上限 64MB，所有来源累计条目数上限 20000 → 超限失败。
- 输出条目按名称升序，时间戳固定 1980-01-01 00:00:00，deflate 级别固定，
  同样输入两次得到相同 sha1。

## 测试

```sh
cargo test
```

覆盖：单源直通、双层覆盖冲突、内容重复去重、lang 深层合并、font 数组拼接、
格式区间无交集、zip slip 拒绝、同输入两次 sha1 一致，另含 strict/first 策略、
pack.png 取最高层、format 覆盖与缺少 pack.mcmeta。
