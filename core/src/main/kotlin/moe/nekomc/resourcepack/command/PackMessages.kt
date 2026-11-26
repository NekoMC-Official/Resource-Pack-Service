package moe.nekomc.resourcepack.command

/**
 * 命令输出文案。
 *
 * 集中一处，避免同一条状态在不同命令里措辞不一致；颜色使用 MiniMessage 记法，
 * 由命令层统一交给 Adventure 渲染。
 */
object PackMessages {

    const val PREFIX: String = "<gray>[资源包]<reset> "

    @JvmStatic
    fun info(text: String): String = PREFIX + "<gray>" + text

    @JvmStatic
    fun ok(text: String): String = PREFIX + "<green>" + text

    @JvmStatic
    fun warn(text: String): String = PREFIX + "<yellow>" + text

    @JvmStatic
    fun error(text: String): String = PREFIX + "<red>" + text

    @JvmStatic
    fun field(label: String, value: String): String = "<gray>" + label + "：<white>" + value

    @JvmStatic
    fun bytes(value: Long): String {
        if (value < 1024) {
            return value.toString() + " B"
        }
        val units = arrayOf("KB", "MB", "GB")
        var size = value.toDouble() / 1024.0
        var index = 0
        while (size >= 1024.0 && index < units.size - 1) {
            size /= 1024.0
            index++
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", size, units[index])
    }
}
