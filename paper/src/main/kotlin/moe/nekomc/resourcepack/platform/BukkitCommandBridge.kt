package moe.nekomc.resourcepack.platform

import moe.nekomc.resourcepack.command.RpsCommand
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/** Bukkit 命令接口与平台中立命令树之间的桥。 */
class BukkitCommandBridge(private val command: RpsCommand) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean =
        command.execute(BukkitCommandSender(sender), args.toList())

    override fun onTabComplete(sender: CommandSender, cmd: Command, alias: String, args: Array<String>): List<String> =
        command.complete(BukkitCommandSender(sender), args.toList())
}
