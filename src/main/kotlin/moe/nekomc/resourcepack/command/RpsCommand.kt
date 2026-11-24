package moe.nekomc.resourcepack.command

import java.nio.file.Files
import moe.nekomc.resourcepack.ResourcePackServicePlugin
import moe.nekomc.resourcepack.pack.PackConflictScanner
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** /rps 命令树：状态、来源、重建、重载、推送、体检。 */
class RpsCommand(private val plugin: ResourcePackServicePlugin) : CommandExecutor, TabCompleter {

    private val mini = MiniMessage.miniMessage()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("rps.use")) {
            send(sender, PackMessages.error("需要权限 rps.use"))
            return true
        }
        val sub = args.firstOrNull()?.lowercase() ?: if (allowed(sender, "status")) "status" else "help"
        if (sub != "help" && sub in SUBCOMMANDS && !allowed(sender, sub)) {
            send(sender, PackMessages.error("需要权限 rps." + sub + "（或 rps.admin）"))
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
            else -> help(sender)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size <= 1) {
            val prefix = args.firstOrNull()?.lowercase() ?: ""
            return SUBCOMMANDS.filter { it.startsWith(prefix) && allowed(sender, it) }
        }
        if (args[0].equals("push", ignoreCase = true) && args.size == 2) {
            val prefix = args[1].lowercase()
            return plugin.server.onlinePlayers.map { it.name }.filter { it.lowercase().startsWith(prefix) }
        }
        return emptyList()
    }

    private fun diff(sender: CommandSender) {
        val lines = plugin.publisher().diffReport()
        if (lines.isEmpty()) {
            send(sender, PackMessages.info("还没有可比的两版合成包，先跑一次 /rps build"))
            return
        }
        for (line in lines) {
            send(sender, PackMessages.info(line))
        }
    }

    private fun status(sender: CommandSender) {
        val config = plugin.config()
        val snapshot = plugin.publisher().snapshot()
        send(sender, PackMessages.info("资源包状态"))
        send(sender, PackMessages.field("槽位", config.providerId()))
        send(sender, PackMessages.field("世代", snapshot.version().toString()))
        send(sender, PackMessages.field("地址", snapshot.url().ifBlank { "（未配置 output.public-url）" }))
        send(sender, PackMessages.field("sha1", snapshot.sha1().ifBlank { "（尚未构建）" }))
        send(sender, PackMessages.field("体积", if (snapshot.bytes() > 0) PackMessages.bytes(snapshot.bytes()) else "—"))
        send(sender, PackMessages.field("来源", plugin.registry().ordered().size.toString() + " 个"))
        send(sender, PackMessages.field("合并引擎", plugin.publisher().engineLabel() + "（rps-pack）"))
        val active = plugin.bridge().activeProvider()
        if (active.isNotBlank() && active != config.providerId()) {
            send(sender, PackMessages.error("下发权不在本服务：" + active + "，合成包不会下发（调高 provider.priority，或关掉对方的推送）"))
        }
        send(sender, PackMessages.field("下发宿主生效", active.ifBlank { "（无）" }))
        if (snapshot.error().isNotBlank()) {
            send(sender, PackMessages.error("上次构建失败：" + snapshot.error()))
        }
        if (plugin.publisher().building()) {
            send(sender, PackMessages.warn("正在构建"))
        }
    }

    private fun list(sender: CommandSender) {
        val sources = plugin.registry().ordered()
        if (sources.isEmpty()) {
            send(sender, PackMessages.warn("config.yml 的 sources 为空"))
            return
        }
        send(sender, PackMessages.info("来源层序（先叠 → 后叠）"))
        for (source in sources) {
            val exists = source.path()?.let { Files.isRegularFile(it) } == true
            val mark = if (exists) "<green>✓<reset>" else "<red>✗<reset>"
            send(sender, "$mark <white>" + source.id() + " <gray>priority=" + source.priority() + " <gray>" + source.path())
        }
    }

    private fun build(sender: CommandSender) {
        plugin.publisher().rebuild(true)
        send(sender, PackMessages.ok("已触发重建，结果见控制台"))
    }

    private fun reload(sender: CommandSender) {
        plugin.reload()
        send(sender, PackMessages.ok("配置已重载，来源 " + plugin.registry().ordered().size + " 个"))
    }

    private fun push(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        val player: Player? = if (name == null) {
            sender as? Player
        } else {
            plugin.server.getPlayerExact(name)
        }
        if (player == null) {
            send(sender, PackMessages.error("找不到目标玩家"))
            return
        }
        val code = plugin.bridge().push(player.uniqueId, true)
        if (code == 1) {
            send(sender, PackMessages.ok("已向 " + player.name + " 推送"))
        } else {
            send(sender, PackMessages.error("推送未排程：没有生效包或玩家不在线"))
        }
    }

    private fun check(sender: CommandSender) {
        val config = plugin.config()
        val issues = ArrayList<String>()
        val pluginsDir = plugin.dataFolder.parentFile.toPath()
        val active = plugin.bridge().activeProvider()
        if (active.isNotBlank() && active != config.providerId()) {
            issues.add("下发权在 " + active + "，合成包不会被下发")
        }
        for (finding in PackConflictScanner.scan(pluginsDir, plugin.dataFolder.name, 10)) {
            issues.add("疑似自带下发：" + finding.plugin() + "/" + finding.file().fileName + ":" + finding.line() + " " + finding.key() + "=" + finding.value())
        }
        if (config.publicUrl().isBlank()) {
            issues.add("output.public-url 为空，合成包不会下发")
        }
        for (source in plugin.registry().ordered()) {
            val path = source.path()
            if (path == null || !Files.isRegularFile(path)) {
                issues.add("来源缺失：" + source.id() + " → " + path)
            }
        }
        val properties = plugin.dataFolder.parentFile.parentFile.toPath().resolve("server.properties").toFile()
        if (properties.isFile) {
            val text = properties.readText()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("resource-pack=") && !trimmed.removePrefix("resource-pack=").isBlank()) {
                    issues.add("server.properties 的 resource-pack 未留空，原版会抢先下发旧包")
                }
                if (trimmed.startsWith("resource-pack-id=") && !trimmed.removePrefix("resource-pack-id=").isBlank()) {
                    issues.add("server.properties 的 resource-pack-id 未留空，客户端会装两份")
                }
            }
        }
        if (issues.isEmpty()) {
            send(sender, PackMessages.ok("体检通过"))
        } else {
            for (issue in issues) {
                send(sender, PackMessages.warn(issue))
            }
        }
    }

    private fun help(sender: CommandSender) {
        val usable = SUBCOMMANDS.filter { allowed(sender, it) }
        if (usable.isEmpty()) {
            send(sender, PackMessages.info("你没有可用的子命令，联系管理员分配 rps.* 权限"))
            return
        }
        send(sender, PackMessages.info("/rps " + usable.joinToString("｜")))
    }

    private fun allowed(sender: CommandSender, sub: String): Boolean {
        if (sub == "help") return true
        return sender.hasPermission("rps." + sub) || sender.hasPermission("rps.admin")
    }

    private fun send(sender: CommandSender, text: String) {
        sender.sendMessage(mini.deserialize(text))
    }

    private companion object {
        private val SUBCOMMANDS = listOf("status", "list", "build", "reload", "push", "check", "diff")
    }
}
