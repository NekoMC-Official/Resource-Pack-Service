package moe.nekomc.resourcepack.config

import java.nio.file.Files
import java.nio.file.Path
import moe.nekomc.resourcepack.core.ZipSource
import moe.nekomc.resourcepack.platform.ConfigSource

/**
 * 插件配置的只读快照（Kotlin 编排层）。
 *
 * 解析、默认值、相对路径归这里管；Java 侧只读结果。字段一律用显式函数暴露，
 * 不用 data class，避免把 Kotlin 语法泄漏成跨语言 API。
 */
class RpsConfig private constructor(
    private val requiredFlag: Boolean,
    private val announceRequiredFlag: Boolean,
    private val promptText: String,
    private val rejectText: String,
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
    private val httpPublicUrl: String,
    private val httpMaxConcurrent: Int,
    private val httpEgressKbps: Int,
    private val fanoutMaxConcurrent: Int,
    private val fanoutJitterMs: Int,
    private val fanoutRetry: Int,
    private val fanoutRamp: Boolean,
    private val communityOn: Boolean,
    private val communityDirectory: Path,
    private val communityLayerPriority: Int,
    private val communityMinecraftVersion: String,
    private val communityPackFormatNumber: Int,
    private val communityRemoteOn: Boolean,
    private val communityRemoteIndexUrl: String,
    private val communityRemoteInterval: Long,
    private val communityRemoteTimeout: Long,
    private val communityRemoteMaxPackBytes: Long,
    private val communityRemoteRequireHttps: Boolean,
    private val communityRemotePinSha1: String
) {

    /** 资源包是否必装：客户端拒绝时断开。 */
    fun required(): Boolean = requiredFlag

    /** 是否把“必需”标记发给客户端；未配置 provider.announce-required 时跟随 provider.required。 */
    fun announceRequired(): Boolean = announceRequiredFlag

    /** 推送给客户端时显示的提示语。 */
    fun prompt(): String = promptText

    /** 客户端拒绝必装资源包时显示的断开原因。 */
    fun rejectMessage(): String = rejectText

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

    /** HTTP 出口同时在途的下载上限；占满时新请求直接 503。 */
    fun deliveryHttpMaxConcurrent(): Int = httpMaxConcurrent

    /** HTTP 出口全局限速（KB/s）；0 表示不限。 */
    fun deliveryHttpEgressKbps(): Int = httpEgressKbps

    /** 同时处于下载中的玩家上限（fanout 并发窗口）。 */
    fun deliveryFanoutMaxConcurrent(): Int = fanoutMaxConcurrent

    /** 进服推送的随机抖动上限（毫秒），避免同时进服打满出口。 */
    fun deliveryFanoutJitterMs(): Int = fanoutJitterMs

    /** 下载失败后的重试次数。 */
    fun deliveryFanoutRetry(): Int = fanoutRetry

    /** 是否按失败率渐进调整并发窗口。 */
    fun deliveryFanoutRamp(): Boolean = fanoutRamp

    /** 是否加载社区资源包目录。 */
    fun communityEnabled(): Boolean = communityOn

    /** 社区资源包目录（已解析为绝对路径）。 */
    fun communityDir(): Path = communityDirectory

    /** 社区包在合成层序里的基准优先级。 */
    fun communityPriority(): Int = communityLayerPriority

    /** 社区包适用的 Minecraft 版本；空串表示不检查。 */
    fun communityMinecraft(): String = communityMinecraftVersion

    /** 服务端当前资源包格式；0 表示不检查。 */
    fun communityPackFormat(): Int = communityPackFormatNumber

    /** 是否自动从远程索引更新社区包。 */
    fun communityRemoteEnabled(): Boolean = communityRemoteOn

    /** 远程索引地址。 */
    fun communityIndexUrl(): String = communityRemoteIndexUrl

    /** 远程检查间隔（分钟）。 */
    fun communityIntervalMinutes(): Long = communityRemoteInterval

    /** 单次 HTTP 请求超时（毫秒）。 */
    fun communityTimeoutMs(): Long = communityRemoteTimeout

    /** 单个社区包允许的最大字节数。 */
    fun communityMaxPackBytes(): Long = communityRemoteMaxPackBytes

    /** 是否要求索引与包都走 HTTPS。 */
    fun communityRequireHttps(): Boolean = communityRemoteRequireHttps

    /** 可选的索引正文 SHA-256 固定值；空串表示不校验。 */
    fun communityPinSha1(): String = communityRemotePinSha1

    companion object {

        private const val DEFAULT_INDEX =
            "https://nekomc-official.github.io/Resource-Pack-Service/community-index.json"

        private const val DEFAULT_NOTIFY =
            "<click:run_command:'/rps push'><gray>资源包已更新，<white><u>点这里</u><gray>立即重载</click>"

        private const val DEFAULT_REJECT =
            "<red>本服资源包为必装项。请重新进入并在提示中点「是」，或在 选项 → 资源包 中把服务器资源包设为「已启用」。"

        /** 从 config.yml 读一份快照；相对路径按 serverRoot 解析。 */
        @JvmStatic
        fun load(config: ConfigSource, serverRoot: Path, defaultCommunityDir: Path? = null): RpsConfig {
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
            val communityDir = if (config.isSet("community.dir")) {
            root.resolve(config.getString("community.dir", "community") ?: "community").normalize()
        } else {
            (defaultCommunityDir ?: root.resolve("plugins/ResourcePackService/community")).toAbsolutePath().normalize()
        }
            val communityMinecraft = (config.getString("community.minecraft", "") ?: "").trim()
            val communityPackFormatValue = config.get("community.pack-format")?.toString()?.trim().orEmpty()
            // 缺省时沿用 merge.pack-format 的具体数字；merge.pack-format 为 auto 时 format 已是 0，即不按格式过滤
            val communityPackFormat =
                if (communityPackFormatValue.isEmpty()) format
                else communityPackFormatValue.toIntOrNull()?.coerceAtLeast(0) ?: format
        val communityRemoteIndex = (config.getString("community.remote.index-url", DEFAULT_INDEX) ?: DEFAULT_INDEX).trim()
        val communityRemoteInterval = config.getLong("community.remote.interval-minutes", 60L).coerceAtLeast(5L)
        val communityRemoteTimeout = config.getLong("community.remote.timeout-ms", 30_000L).coerceIn(1_000L, 300_000L)
        val communityRemoteMaxPack = config.getLong("community.remote.max-pack-bytes", 67_108_864L).coerceAtLeast(1L)
            val interval = config.getLong("watch.interval-seconds", 60L)

            val requiredFlagValue = config.getBoolean("provider.required", false)
            val announceRequiredValue =
                config.getBoolean("provider.announce-required", requiredFlagValue)

            return RpsConfig(
                requiredFlag = requiredFlagValue,
                announceRequiredFlag = announceRequiredValue,
                promptText = config.getString("provider.prompt", "") ?: "",
                rejectText = config.getString("provider.reject-message", DEFAULT_REJECT) ?: DEFAULT_REJECT,
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
                httpPortNumber = config.getInt("delivery.http.port", 25580).coerceIn(0, 65535),
                httpUrlPath = config.getString("delivery.http.path", "/pack/merged.zip") ?: "/pack/merged.zip",
                httpPublicUrl = (config.getString("delivery.http.public-url", "") ?: "").trim(),
                httpMaxConcurrent = config.getInt("delivery.http.max-concurrent", 64).coerceIn(1, 256),
                httpEgressKbps = config.getInt("delivery.http.egress-kbps", 0).coerceIn(0, 10_000_000),
            fanoutMaxConcurrent = config.getInt("delivery.fanout.max-concurrent", 64).coerceIn(1, 1024),
            fanoutJitterMs = config.getInt("delivery.fanout.jitter-ms", 1500).coerceIn(0, 10_000),
            fanoutRetry = config.getInt("delivery.fanout.retry", 1).coerceIn(0, 3),
            fanoutRamp = config.getBoolean("delivery.fanout.ramp", true),
            communityOn = config.getBoolean("community.enabled", true),
            communityDirectory = communityDir,
            communityLayerPriority = config.getInt("community.priority", 0),
            communityMinecraftVersion = communityMinecraft,
            communityPackFormatNumber = communityPackFormat,
            communityRemoteOn = config.getBoolean("community.remote.enabled", false),
            communityRemoteIndexUrl = communityRemoteIndex,
            communityRemoteInterval = communityRemoteInterval,
            communityRemoteTimeout = communityRemoteTimeout,
            communityRemoteMaxPackBytes = communityRemoteMaxPack,
            communityRemoteRequireHttps = config.getBoolean("community.remote.require-https", true),
            communityRemotePinSha1 = (config.getString("community.remote.pin-sha1", "") ?: "").trim()
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
