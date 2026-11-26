package moe.nekomc.resourcepack.command

import java.util.UUID

/**
 * 命令发送者端口。
 *
 * <p>命令层不认 Bukkit 的 CommandSender，也不认 Fabric 的 ServerCommandSource；
 * 适配层把"名字、是不是玩家、有没有权限、怎么发消息"四件事包出来即可。</p>
 */
interface CommandSenderRef {

    /** 展示用名字；控制台通常是 "CONSOLE" / "Server"。 */
    val name: String

    /** 玩家 UUID；控制台为 null。 */
    val playerId: UUID?

    fun hasPermission(node: String): Boolean

    /** 传入 MiniMessage 文本，由适配层渲染成平台组件。 */
    fun sendMessage(miniMessage: String)
}
