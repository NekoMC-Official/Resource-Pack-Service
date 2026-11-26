package moe.nekomc.resourcepack.pack

import java.nio.file.Path
import java.util.logging.Logger
import moe.nekomc.resourcepack.config.RpsConfig
import moe.nekomc.resourcepack.core.EngineConfig
import moe.nekomc.resourcepack.core.SourceRegistry
import moe.nekomc.resourcepack.core.StateStore
import moe.nekomc.resourcepack.delivery.PackDelivery
import moe.nekomc.resourcepack.platform.PlayerRef
import moe.nekomc.resourcepack.platform.Scheduler

/**
 * 发布器需要的宿主能力。
 *
 * <p>Kotlin 编排层只依赖这个端口，不 import 任何插件实现类；由各平台适配层实现并注入。
 * 跨语言边界只出现 JDK 类型、本项目的 Java record 与 platform 包下的端口。</p>
 */
interface PackHost {

    fun logger(): Logger

    fun dataFolder(): Path

    fun config(): RpsConfig

    fun registry(): SourceRegistry

    fun stateStore(): StateStore

    fun delivery(): PackDelivery?

    fun engineConfig(): EngineConfig

    /** 定时与异步执行；Folia 走 asyncScheduler，Fabric 走 tick 计数。 */
    fun scheduler(): Scheduler

    /** 在线玩家快照，公告与重推时使用。 */
    fun onlinePlayers(): List<PlayerRef>
}
