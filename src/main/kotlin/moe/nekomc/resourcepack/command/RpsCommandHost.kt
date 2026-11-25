package moe.nekomc.resourcepack.command

import java.nio.file.Path
import moe.nekomc.resourcepack.config.RpsConfig
import moe.nekomc.resourcepack.core.SourceRegistry
import moe.nekomc.resourcepack.delivery.PackPusher
import moe.nekomc.resourcepack.pack.PackPublisher
import org.bukkit.Server

/**
 * 命令层需要的宿主能力。
 *
 * <p>与 [moe.nekomc.resourcepack.pack.PackHost] 一样，只暴露 JDK 类型与项目内的 Java
 * record，避免 Kotlin 直接依赖 Java 插件实现类。</p>
 */
interface RpsCommandHost {

    fun server(): Server

    fun dataFolder(): Path

    fun config(): RpsConfig

    fun registry(): SourceRegistry

    fun publisher(): PackPublisher?

    fun delivery(): PackPusher?

    fun reload()

    /** 立即检查一次远程社区索引；返回是否已排程。 */
    fun updateCommunity(force: Boolean): Boolean

    /** 远程索引状态文案。 */
    fun communityRemoteStatus(): String
}
