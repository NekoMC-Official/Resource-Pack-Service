package moe.nekomc.resourcepack.platform

import java.util.UUID
import moe.nekomc.resourcepack.command.CommandSenderRef
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** 把 Bukkit 的 CommandSender 包成命令层认的端口。 */
class BukkitCommandSender(private val sender: CommandSender) : CommandSenderRef {

    override val name: String
        get() = sender.name

    override val playerId: UUID?
        get() = (sender as? Player)?.uniqueId

    override fun hasPermission(node: String): Boolean = sender.hasPermission(node)

    override fun sendMessage(miniMessage: String) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage))
    }
}
