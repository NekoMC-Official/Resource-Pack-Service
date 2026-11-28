package moe.nekomc.resourcepack.command

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import moe.nekomc.resourcepack.pack.ConflictReport
import moe.nekomc.resourcepack.pack.PackConflictScanner
import moe.nekomc.resourcepack.pack.PackSnapshot

/**
 * /rps 命令树。
 *
 * <p>只做编排：读发布器快照、调下发器、把结果排版成消息；所有副作用都在别的类里。
 * 平台差异通过 [CommandHost] 与 [CommandSenderRef] 注入，本类不 import 任何平台类型。</p>
 */
class RpsCommand(private val plugin: CommandHost) {

    private val subcommands = listOf(
        "status", "list", "build", "reload", "push", "check", "diff", "conflicts", "rejected", "community"
    )

    private val statusTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** 平台适配层把参数数组交进来；返回 true 表示已处理。 */
    fun execute(sender: CommandSenderRef, args: List<String>): Boolean {
        if (!sender.hasPermission("rps.use")) {
            sender.sendMessage(PackMessages.error("你没有使用 /rps 的权限"))
            return true
        }
        if (args.isEmpty()) {
            help(sender)
            return true
        }
        val sub = args[0].lowercase(Locale.ROOT)
        if (sub != "help" && sub !in subcommands) {
            sender.sendMessage(PackMessages.error("未知的子命令：" + args[0]))
            help(sender)
            return true
        }
        if (sub != "help" && !canUseSub(sender, sub)) {
            sender.sendMessage(PackMessages.error("你没有 rps." + sub + " 权限"))
            return true
        }
        when (sub) {
            "status" -> status(sender)
            "list" -> list(sender)
            "build" -> build(sender)
            "reload" -> reload(sender)
            "push" -> push(sender, args)
            "check" -> check(sender)
            "diff" -> diff(sender)
            "conflicts" -> conflicts(sender, args)
            "rejected" -> rejected(sender, args)
            "community" -> community(sender, args)
            else -> help(sender)
        }
        return true
    }

    private fun status(sender: CommandSenderRef) {
        val snapshot = plugin.publisher()?.snapshot() ?: PackSnapshot.empty()
        val delivery = plugin.delivery()
        sender.sendMessage(PackMessages.info("合成包状态"))
        sender.sendMessage(PackMessages.field("下发方式", delivery?.name() ?: "本插件内置推送（未就绪）"))
        if (delivery != null) {
            sender.sendMessage(PackMessages.field("下发细节", delivery.detail()))
        }
        sender.sendMessage(
            PackMessages.field("版本", if (snapshot.version() <= 0) "还没构建过" else "第 " + snapshot.version() + " 版")
        )
        sender.sendMessage(PackMessages.field("下载地址", if (snapshot.url().isBlank()) "没有可用地址" else snapshot.url()))
        sender.sendMessage(PackMessages.field("摘要", if (snapshot.sha1().isBlank()) "无" else snapshot.sha1()))
        sender.sendMessage(PackMessages.field("大小", if (snapshot.bytes() <= 0L) "无" else PackMessages.bytes(snapshot.bytes())))
        sender.sendMessage(PackMessages.field("构建时间", if (snapshot.builtAt().isBlank()) "无" else snapshot.builtAt()))
        sender.sendMessage(PackMessages.field("合成摘要", if (snapshot.summary().isBlank()) "无" else snapshot.summary()))
        sender.sendMessage(PackMessages.field("合成引擎", plugin.publisher()?.engineLabel() ?: "未就绪"))
        sender.sendMessage(PackMessages.field("来源数量", plugin.registry().all().size.toString()))
        sender.sendMessage(PackMessages.field("变更推送", describePushMode(plugin.config().pushMode())))
        sender.sendMessage(PackMessages.field("社区资源包", communitySummary()))
        val building = plugin.publisher()?.building() == true
        sender.sendMessage(PackMessages.field("当前状态", if (building) "正在合成" else "空闲"))
        val fanout = plugin.publisher()?.fanout()?.stats()
        if (fanout != null) {
            sender.sendMessage(
                PackMessages.field(
                    "推送队列",
                    "排队 " + fanout.queued + " / 下载中 " + fanout.inflight + " / 已推送 " + fanout.pushed
                        + " / 已完成 " + fanout.completed + " / 失败 " + fanout.failed + " / 窗口 " + fanout.window,
                ),
            )
        }
        if (snapshot.error().isNotBlank()) {
            sender.sendMessage(PackMessages.error("上次构建失败：" + snapshot.error()))
        }
    }

    private fun list(sender: CommandSenderRef) {
        val sources = plugin.registry().ordered()
        val community = plugin.publisher()?.communitySources().orEmpty()
        if (sources.isEmpty() && community.isEmpty()) {
            sender.sendMessage(PackMessages.warn("还没有登记任何来源，可以用 API 注册，或写进 config.yml 的 sources"))
            return
        }
        sender.sendMessage(PackMessages.info("已登记 " + (sources.size + community.size) + " 个来源"))
        for (source in sources) {
            val state = if (source.enabled()) "启用" else "停用"
            sender.sendMessage(
                PackMessages.field(
                    source.id(),
                    state + " · 优先级 " + source.priority() + versionSuffix(source.version())
                        + " · " + source.file()
                )
            )
        }
        for (source in community) {
            sender.sendMessage(
                PackMessages.field(
                    source.id(),
                    "启用 · 社区包 · 优先级 " + source.priority() + versionSuffix(source.version())
                        + " · " + source.file()
                )
            )
        }
    }

    /** 来源声明了版本号时补一段展示文本，未声明就什么都不加。 */
    private fun versionSuffix(version: String?): String {
        val label = versionLabel(version)
        return if (label.isEmpty()) "" else " · " + label
    }

    private fun versionLabel(version: String?): String {
        val text = version?.trim().orEmpty()
        if (text.isEmpty()) {
            return ""
        }
        val already = text.length > 1 && (text[0] == 'v' || text[0] == 'V') && text[1].isDigit()
        return if (already) text else "v" + text
    }

    private fun build(sender: CommandSenderRef) {
        val publisher = plugin.publisher()
        if (publisher == null) {
            sender.sendMessage(PackMessages.error("发布器还没就绪，等一下再试"))
            return
        }
        if (publisher.building()) {
            sender.sendMessage(PackMessages.warn("已经有一次合成在进行"))
            return
        }
        publisher.rebuild(true)
        sender.sendMessage(PackMessages.ok("已开始合成，完成后按 push.mode 处理在线玩家"))
    }

    private fun reload(sender: CommandSenderRef) {
        plugin.reload()
        sender.sendMessage(PackMessages.ok("配置已重载，正在重新合成"))
    }

    private fun push(sender: CommandSenderRef, args: List<String>) {
        val delivery = plugin.delivery()
        if (delivery == null) {
            sender.sendMessage(PackMessages.error("下发器还没就绪"))
            return
        }
        val force = args.any { it.equals("force", ignoreCase = true) }
        val name = args.drop(1).firstOrNull { !it.equals("force", ignoreCase = true) }
        if (name == null) {
            val self = sender.playerId
            if (self != null) {
                val pushed = delivery.push(self, force)
                if (pushed > 0) {
                    sender.sendMessage(PackMessages.ok("已为你重新加载资源包" + (if (force) "（强制）" else "")))
                } else {
                    sender.sendMessage(PackMessages.warn("没有可推送的地址，先等一次构建完成"))
                }
                return
            }
            var pushed = 0
        val publisher = plugin.publisher()
        val controller = publisher?.fanout()
        if (controller != null) {
            val all = plugin.onlinePlayers()
            controller.enqueue(all.map { it.uniqueId() }, publisher?.snapshot()?.sha1() ?: "", false, force = true)
            sender.sendMessage(
                PackMessages.ok("已把 " + all.size + " 名在线玩家排入推送队列" + if (force) "（强制）" else "")
            )
            return
        }
            for (player in plugin.onlinePlayers()) {
                pushed += delivery.push(player.uniqueId(), force)
            }
            sender.sendMessage(PackMessages.ok("已向 " + pushed + " 名在线玩家推送" + if (force) "（强制）" else ""))
            return
        }
        if (!sender.hasPermission("rps.push") && !sender.hasPermission("rps.admin")) {
            val self = sender.playerId
            if (self == null || !name.equals(sender.name, true)) {
                sender.sendMessage(PackMessages.error("只能对自己使用 /rps push；给其他玩家推送需要 rps.push"))
                return
            }
        }
        val target = plugin.onlinePlayers().firstOrNull { it.name().equals(name, true) }
        if (target == null) {
            sender.sendMessage(PackMessages.error("找不到在线玩家：" + name))
            return
        }
        val pushed = delivery.push(target.uniqueId(), force)
        if (pushed > 0) {
            sender.sendMessage(PackMessages.ok("已向 " + target.name() + " 推送" + if (force) "（强制）" else ""))
        } else {
            sender.sendMessage(PackMessages.warn("没有可推送的地址，先跑一次 /rps build"))
        }
    }

    private fun check(sender: CommandSenderRef) {
        val snapshot = plugin.publisher()?.snapshot() ?: PackSnapshot.empty()
        sender.sendMessage(PackMessages.info("环境检查"))
        if (snapshot.url().isBlank()) {
            sender.sendMessage(PackMessages.warn("还没有下载地址：确认 delivery.http.enabled 已开且端口未被占用"))
        } else {
            sender.sendMessage(PackMessages.field("下载地址", snapshot.url()))
        }
        val publicUrl = plugin.config().deliveryPublicUrl()
        if (publicUrl.isNotBlank()) {
            val lower = publicUrl.lowercase(Locale.ROOT)
            if (lower.contains("127.0.0.1") || lower.contains("localhost") || lower.contains("[::1]")) {
                sender.sendMessage(PackMessages.warn("public-url 指向本机回环地址，只有同机客户端能下载，远程玩家会失败"))
            }
        }
        checkServerProperties(sender)
        val scanRoots = plugin.conflictScanRoots()
        if (scanRoots.isEmpty()) {
            sender.sendMessage(PackMessages.info("当前平台没有可扫描的配置目录，跳过第三方资源包检查"))
        } else {
            val findings = PackConflictScanner.scanRoots(scanRoots, plugin.dataFolder().fileName.toString(), 10)
            if (findings.isEmpty()) {
                sender.sendMessage(PackMessages.ok("没有发现别的插件自己配资源包地址"))
            } else {
                val capped = if (findings.size >= 10) "（只列前 10 条，其余直接看配置文件）" else ""
                sender.sendMessage(PackMessages.warn("发现 " + findings.size + " 处疑似冲突配置" + capped + "："))
                for (finding in findings) {
                    sender.sendMessage(
                        PackMessages.field(
                            finding.plugin(),
                            finding.file().fileName.toString() + ":" + finding.line() + " " + finding.key() + "=" + finding.value()
                        )
                    )
                }
            }
        }
        if (plugin.config().communityEnabled()) {
            val community = plugin.publisher()?.communitySources().orEmpty()
            if (community.isEmpty()) {
                sender.sendMessage(PackMessages.warn("社区资源包目录没有可加载的包：" + plugin.config().communityDir()))
            } else {
                sender.sendMessage(PackMessages.ok("社区资源包已按已安装插件纳入 " + community.size + " 个"))
            }
        }
        sender.sendMessage(PackMessages.info("客户端每次收到版本变化的地址才会重新下载，先确认端口对外可达"))
    }

    private fun checkServerProperties(sender: CommandSenderRef) {
        val file: Path = plugin.serverRoot().resolve("server.properties")
        if (!Files.isRegularFile(file)) {
            sender.sendMessage(PackMessages.warn("找不到 server.properties：" + file))
            return
        }
        val properties = Properties()
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { properties.load(it) }
        } catch (exception: Exception) {
            sender.sendMessage(PackMessages.warn("读不了 server.properties：" + exception.message))
            return
        }
        var dirty = false
        for (key in listOf("resource-pack", "resource-pack-id")) {
            val value = properties.getProperty(key, "").trim()
            if (value.isNotEmpty()) {
                dirty = true
                sender.sendMessage(PackMessages.warn("server.properties 的 " + key + " 不为空，会与本插件争抢下发，请清空"))
            }
        }
        if (!dirty) {
            sender.sendMessage(PackMessages.ok("server.properties 的 resource-pack / resource-pack-id 都是空的"))
        }
    }

    private fun diff(sender: CommandSenderRef) {
        val lines = plugin.publisher()?.diffReport(30).orEmpty()
        if (lines.isEmpty()) {
            sender.sendMessage(PackMessages.warn("还没有可比对的条目清单，先跑一次 /rps build"))
            return
        }
        for (line in lines) {
            sender.sendMessage("<gray>" + line)
        }
    }

    private fun safe(value: String): String = value.replace("<", "\\<")

    private fun conflicts(sender: CommandSenderRef, args: List<String>) {
        val all = args.size >= 2 && args[1].equals("all", ignoreCase = true)
        val report = ConflictReport.read(plugin.config().outputDir())
        if (report == null) {
            sender.sendMessage(PackMessages.warn("还没有冲突报告：先跑一次 /rps build 重新生成"))
            return
        }
        val visible = ConflictReport.visible(report, all)
        sender.sendMessage(PackMessages.info("资源包冲突"))
        sender.sendMessage(
            PackMessages.field(
                "报告",
                report.generatedAt + " · " + report.sha1.take(12) + " · 第 " + report.version + " 版"
            )
        )
        sender.sendMessage(
            PackMessages.field(
                "汇总",
                "共 " + report.summary.total + " 条 · 高 " + report.summary.high
                    + " · 中 " + report.summary.medium + " · 低 " + report.summary.low
                    + (if (report.summary.truncated) " · 明细已截断" else "")
            )
        )
        if (visible.isEmpty()) {
            val message = if (all) "没有记录到冲突" else "没有材质、模型或方块状态冲突"
            sender.sendMessage(PackMessages.ok(message))
            if (!all) {
                sender.sendMessage("<gray>语言、声音、字体、图集冲突用 /rps conflicts all 查看")
            }
            return
        }
        val limit = if (all) 40 else 20
        for (conflict in visible.take(limit)) {
            val color = when (conflict.severity) {
                "high" -> "<red>"
                "medium" -> "<yellow>"
                else -> "<gray>"
            }
            sender.sendMessage(color + "[" + safe(conflict.severity) + "] " + safe(conflict.kind) + " <white>" + safe(conflict.path))
            sender.sendMessage(
                "<gray>胜出 " + safe(conflict.winner) + "(" + conflict.winnerPriority + ") <dark_gray>← "
                    + conflict.losers.joinToString(", ") { safe(it) }
                    + (if (conflict.samePriority) "（同优先级）" else "")
            )
            if (conflict.fields.isNotEmpty()) {
                sender.sendMessage("<gray>字段 " + conflict.fields.take(5).joinToString("; ") { safe(it) })
            }
        }
        if (visible.size > limit) {
            sender.sendMessage(PackMessages.field("提示", "另有 " + (visible.size - limit) + " 条，完整明细见 conflicts.json"))
        }
        if (!all) {
            sender.sendMessage("<gray>语言、声音、字体、图集冲突用 /rps conflicts all 查看")
        }
    }

    private fun rejected(sender: CommandSenderRef, args: List<String>) {
        val delivery = plugin.delivery()
        if (delivery == null) {
            sender.sendMessage(PackMessages.error("下发器还没就绪，等一下再试"))
            return
        }
        val requested = args.getOrNull(1)?.toIntOrNull() ?: 20
        val limit = requested.coerceIn(1, 200)
        val entries = delivery.recent(limit)
        if (entries.isEmpty()) {
            sender.sendMessage(PackMessages.warn("还没有记录到玩家资源包状态"))
            return
        }
        sender.sendMessage(PackMessages.info("最近 " + entries.size + " 条资源包状态"))
        for (entry in entries) {
            val name = entry.playerName().ifBlank { entry.playerId().toString() }
            val hash = if (entry.sha1().isBlank()) "" else " · " + entry.sha1().take(8)
            sender.sendMessage(
                PackMessages.field(
                    safe(name) + " · " + entry.status().name,
                    statusTime.format(Instant.ofEpochMilli(entry.at())) + hash
                )
            )
        }
        sender.sendMessage("<gray>状态来自客户端反馈；DECLINED 表示玩家拒绝了资源包")
    }

    private fun community(sender: CommandSenderRef, args: List<String>) {
        val config = plugin.config()
        sender.sendMessage(PackMessages.info("社区资源包远程索引"))
        sender.sendMessage(PackMessages.field("状态", plugin.communityRemoteStatus()))
        sender.sendMessage(PackMessages.field("本地目录", config.communityDir().toString()))
        val loaded = plugin.publisher()?.communitySources().orEmpty().size
        sender.sendMessage(PackMessages.field("已纳入", loaded.toString() + " 个"))
        if (args.size >= 2 && args[1].equals("update", ignoreCase = true)) {
            if (!config.communityRemoteEnabled()) {
                sender.sendMessage(PackMessages.warn("远程索引未启用：先设置 community.remote.enabled: true"))
                return
            }
            if (plugin.updateCommunity(true)) {
                sender.sendMessage(PackMessages.ok("已开始检查远程索引，完成后会自动重建合成包"))
            } else {
                sender.sendMessage(PackMessages.warn("更新器还没就绪，稍后再试"))
            }
        } else {
            sender.sendMessage("<gray>用法：/rps community [update]")
        }
    }

    private fun help(sender: CommandSenderRef) {
        sender.sendMessage(PackMessages.info("/rps 命令"))
        val entries = listOf(
            "status" to "看版本、下载地址、下发方式",
            "list" to "看已登记的来源",
            "build" to "立刻合成一次",
            "reload" to "重载配置并重新合成",
            "push" to if (sender.playerId != null) "重新下载资源包给自己；带玩家名则推给别人" else "给在线玩家或指定玩家推送",
            "check" to "检查地址、端口与配置冲突",
            "diff" to "对比本次与上次的条目差异",
            "conflicts" to "看材质、模型等资源冲突；all 查看全部",
            "rejected" to "看最近的玩家资源包状态；可带条数",
            "community" to "社区包远程索引；update 立即检查"
        )
        for ((sub, description) in entries) {
            if (canUseSub(sender, sub)) {
                sender.sendMessage(PackMessages.field(sub, description))
            }
        }
    }

    private fun canUseSub(sender: CommandSenderRef, sub: String): Boolean {
        if (sub == "push" && sender.playerId != null) {
            return true
        }
        if (sub == "conflicts") {
            return sender.hasPermission("rps.conflicts") || sender.hasPermission("rps.admin")
        }
        if (sub == "rejected") {
            return sender.hasPermission("rps.status") || sender.hasPermission("rps.admin")
        }
        return sub == "help" || sender.hasPermission("rps." + sub) || sender.hasPermission("rps.admin")
    }

    private fun communitySummary(): String {
        val config = plugin.config()
        if (!config.communityEnabled()) {
            return "未启用"
        }
        val sources = plugin.publisher()?.communitySources().orEmpty()
        return sources.size.toString() + " 个（" + config.communityDir() + "）"
    }

    private fun describePushMode(mode: String): String = when (mode.lowercase(Locale.ROOT)) {
        "none" -> "none（只落盘，不打扰玩家）"
        "force" -> "force（强制重推在线玩家）"
        else -> "notify（提示玩家点击下载）"
    }

    /** 补全：与命令树同一套权限判断。 */
    fun complete(sender: CommandSenderRef, args: List<String>): List<String> {
        if (args.size == 1) {
            return subcommands.filter { canUseSub(sender, it) }
        }
        if (args.size == 2 && args[0].equals("rejected", ignoreCase = true)) {
            return listOf("20", "50", "100")
        }
        if (args.size == 2 && args[0].equals("conflicts", ignoreCase = true)) {
            return listOf("all")
        }
        if (args.size == 2 && args[0].equals("community", ignoreCase = true)) {
            return listOf("status", "update")
        }
        if (args.size == 2 && args[0].equals("push", ignoreCase = true)) {
            if (!sender.hasPermission("rps.push") && !sender.hasPermission("rps.admin")) {
                return listOf("force")
            }
            val names = plugin.onlinePlayers().map { it.name() }
            return names + "force"
        }
        return emptyList()
    }
}
