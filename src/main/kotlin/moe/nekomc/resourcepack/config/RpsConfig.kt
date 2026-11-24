package moe.nekomc.resourcepack.config

import java.nio.file.Files
import java.nio.file.Path
import moe.nekomc.resourcepack.core.ZipSource
import org.bukkit.configuration.file.FileConfiguration

/**
 * 插件配置的只读快照（Kotlin 编排层）。
 *
 * 解析、默认值、相对路径归这里管；Java 侧只读结果。字段一律用显式函数暴露，
 * 不用 data class，避免把 Kotlin 语法泄漏成跨语言 API。
 */
class RpsConfig private constructor(
    private val requiredFlag: Boolean,
    private val promptText: String,
    private val policyName: String,
    private val jsonMergeEnabled: Boolean,
    private val formatOverride: Int,
    private val maxBytesValue: Long,
    private val descriptionText: String,
    private val outputDirectory: Path,
    private val sourceList: List<ZipSource>,
    private val watchOn: Boolean,
    private val watchInterval: Long,
    private val pushModeName: String,
    private val notifyText: String,
    private val joinDelay: Int,
    private val statusFeedbackOn: Boolean,
    private val httpOn: Boolean,
    private val httpBindAddress: String,
    private val httpPortNumber: Int,
    private val httpUrlPath: String,
    private val httpPublicUrl: String
) {

    /** 资源包是否必装：客户端拒绝时断开。 */
    fun required(): Boolean = requiredFlag

    /** 推送给客户端时显示的提示语。 */
    fun prompt(): String = promptText

    /** 合并冲突策略，交给 rps-pack。 */
    fun policy(): String = policyName

    /** lang/sounds/font 等 JSON 是否按键深合并。 */
    fun jsonMerge(): Boolean = jsonMergeEnabled

    /** 强制 pack_format，0 表示由来源交集自动推断。 */
    fun packFormat(): Int = formatOverride

    /** 合成包体积上限（字节）。 */
    fun maxBytes(): Long = maxBytesValue

    /** 写进 pack.mcmeta 的描述。 */
    fun description(): String = descriptionText

    /** 合成包输出目录（已解析为绝对路径）。 */
    fun outputDir(): Path = outputDirectory

    /** 配置里声明的来源层。 */
    fun sources(): List<ZipSource> = sourceList

    fun watchEnabled(): Boolean = watchOn

    fun watchSeconds(): Long = watchInterval

    /** none / notify / force。 */
    fun pushMode(): String = pushModeName

    /** notify 模式下发给玩家的 MiniMessage 文案。 */
    fun notifyMessage(): String = notifyText

    /** 玩家进服后延迟多少 tick 推送。 */
    fun deliveryJoinDelayTicks(): Int = joinDelay

    /** 是否向玩家与日志反馈资源包加载结果。 */
    fun deliveryStatusFeedback(): Boolean = statusFeedbackOn

    /** 是否启动内置 HTTP 出口。 */
    fun deliveryHttpEnabled(): Boolean = httpOn

    fun deliveryHttpBind(): String = httpBindAddress

    fun deliveryHttpPort(): Int = httpPortNumber

    fun deliveryHttpPath(): String = httpUrlPath

    /** 对外下载地址；非空时优先于自动拼装，适用于反代或 CDN。 */
    fun deliveryPublicUrl(): String = httpPublicUrl

    companion object {

        private const val DEFAULT_NOTIFY =
            "<click:run_command:'/rps push'><gray>资源包已更新，<white><u>点这里</u><gray>立即重载</click>"

        /** 从 config.yml 读一份快照；相对路径按 serverRoot 解析。 */
        @JvmStatic
        fun load(config: FileConfiguration, serverRoot: Path): RpsConfig {
            val root = serverRoot.toAbsolutePath().normalize()
            val output = root.resolve(config.getString("output.dir", "merged") ?: "merged").normalize()

            val sources = ArrayList<ZipSource>()
            for (raw in config.getMapList("sources")) {
                val id = (raw["id"] as? String)?.trim().orEmpty()
                val path = (raw["path"] as? String)?.trim().orEmpty()
                if (id.isEmpty() || path.isEmpty()) {
                    continue
                }
                val enabled = raw["enabled"] as? Boolean ?: true
                val priority = (raw["priority"] as? Number)?.toInt() ?: 0
                val namespaces = (raw["namespaces"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                val declared = (raw["sha1"] as? String)?.trim().orEmpty()
                val file = root.resolve(path).normalize()
                sources.add(
                    ZipSource(
                        id,
                        enabled,
                        file,
                        declared.ifEmpty { null },
                        priority,
                        namespaces
                    )
                )
            }

            val format = parseFormat(config.getString("merge.pack-format", "auto"))
            val interval = config.getLong("watch.interval-seconds", 60L)

            return RpsConfig(
                requiredFlag = config.getBoolean("provider.required", false),
                promptText = config.getString("provider.prompt", "") ?: "",
                policyName = config.getString("merge.policy", "override") ?: "override",
                jsonMergeEnabled = config.getBoolean("merge.json-merge", true),
                formatOverride = format,
                maxBytesValue = config.getLong("merge.max-bytes", 104_857_600L).coerceAtLeast(1L),
                descriptionText = config.getString("merge.description", "NekoMC 统一资源包") ?: "",
                outputDirectory = output,
                sourceList = sources.toList(),
                watchOn = config.getBoolean("watch.enabled", true),
                watchInterval = interval,
                pushModeName = (config.getString("push.mode", "notify") ?: "notify").lowercase(),
                notifyText = config.getString("push.notify-message", DEFAULT_NOTIFY) ?: DEFAULT_NOTIFY,
                joinDelay = config.getInt("delivery.join-delay-ticks", 1).coerceAtLeast(0),
                statusFeedbackOn = config.getBoolean("delivery.status-feedback", true),
                httpOn = config.getBoolean("delivery.http.enabled", true),
                httpBindAddress = config.getString("delivery.http.bind", "0.0.0.0") ?: "0.0.0.0",
                httpPortNumber = config.getInt("delivery.http.port", 25580),
                httpUrlPath = config.getString("delivery.http.path", "/pack/merged.zip") ?: "/pack/merged.zip",
                httpPublicUrl = (config.getString("delivery.http.public-url", "") ?: "").trim()
            )
        }

        private fun parseFormat(raw: String?): Int {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.equals("auto", ignoreCase = true)) {
                return 0
            }
            return text.toIntOrNull()?.coerceAtLeast(0) ?: 0
        }
    }
}
