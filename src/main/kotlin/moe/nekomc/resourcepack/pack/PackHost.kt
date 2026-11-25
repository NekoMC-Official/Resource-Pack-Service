package moe.nekomc.resourcepack.pack

import java.nio.file.Path
import java.util.logging.Logger
import moe.nekomc.resourcepack.config.RpsConfig
import moe.nekomc.resourcepack.core.EngineConfig
import moe.nekomc.resourcepack.core.SourceRegistry
import moe.nekomc.resourcepack.core.StateStore
import moe.nekomc.resourcepack.delivery.PackPusher
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/**
 * 发布器需要的宿主能力。
 *
 * <p>Kotlin 编排层只依赖这个端口，不 import Java 插件实现类；由插件入口实现并注入。
 * 跨语言边界只出现 JDK 类型和本项目的 Java record。</p>
 */
interface PackHost {

    fun pluginInstance(): Plugin

    fun logger(): Logger

    fun server(): Server

    fun dataFolder(): Path

    fun config(): RpsConfig

    fun registry(): SourceRegistry

    fun stateStore(): StateStore

    fun delivery(): PackPusher?

    fun engineConfig(): EngineConfig
}
