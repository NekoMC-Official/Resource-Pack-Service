package moe.nekomc.resourcepack.command

import java.nio.file.Path
import moe.nekomc.resourcepack.config.RpsConfig
import moe.nekomc.resourcepack.core.SourceRegistry
import moe.nekomc.resourcepack.delivery.PackDelivery
import moe.nekomc.resourcepack.pack.PackPublisher
import moe.nekomc.resourcepack.platform.PlayerRef

/**
 * 命令层需要的宿主能力。
 *
 * <p>与 [moe.nekomc.resourcepack.pack.PackHost] 同一定位：只暴露 JDK 类型与本项目端口，
 * 平台差异（Bukkit 的 Server、Fabric 的 MinecraftServer）留在适配层。</p>
 */
interface CommandHost {

    fun dataFolder(): Path

    /** 服务端根目录（server.properties 所在处）；Fabric 是 run/ 或实例目录。 */
    fun serverRoot(): Path

    fun config(): RpsConfig

    fun registry(): SourceRegistry

    fun publisher(): PackPublisher?

    fun delivery(): PackDelivery?

    fun onlinePlayers(): List<PlayerRef>

    fun reload()

    /** 立即检查一次远程社区索引；返回是否已排程。 */
    fun updateCommunity(force: Boolean): Boolean

    /** 远程索引状态文案。 */
    fun communityRemoteStatus(): String

    /**
     * 扫描"别的插件/mod 自己配资源包地址"的根目录。
     *
     * <p>Paper 侧是 plugins/；Fabric 侧是 config/（mod 的 TOML 配置都在这儿）。
     * 返回空列表表示当前平台没有可扫描的目录，`/rps check` 会跳过这一段。</p>
     */
    fun conflictScanRoots(): List<Path>
}
