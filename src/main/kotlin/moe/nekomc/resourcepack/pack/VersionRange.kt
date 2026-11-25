package moe.nekomc.resourcepack.pack

/**
 * 版本范围表达式。
 *
 * <p>支持四种写法：</p>
 * <ul>
 *   <li>空串或 {@code *}：任意版本</li>
 *   <li>单个版本：精确匹配，例如 {@code 26.3}</li>
 *   <li>比较式：{@code >=1.20}、{@code <1.21}、{@code =1.20.4}、{@code ==1.20.4}</li>
 *   <li>组合：逗号表示“或”，空格表示“且”，例如 {@code >=1.20 <1.21, 26.3}</li>
 * </ul>
 *
 * <p>版本按 1–4 段数字比较，缺的段补 0，所以 {@code 1.20} 等于 {@code 1.20.0}。
 * 允许 {@code v} 前缀，以及 {@code -} / {@code +} 后面的预发布或构建后缀（比较时忽略）。
 * 非法表达式 [parse] 返回 null，由调用方决定是跳过还是报错。</p>
 */
class VersionRange private constructor(private val groups: List<List<Bound>>) {

    /** 判断一个版本文本是否落在范围内；版本本身不合法时返回 false。 */
    fun accepts(raw: String?): Boolean {
        val version = Version.parse(raw) ?: return false
        return groups.any { group -> group.all { bound -> bound.test(version) } }
    }

    private data class Bound(val operator: String, val version: Version) {

        fun test(candidate: Version): Boolean = when (operator) {
            ">=" -> candidate >= version
            "<=" -> candidate <= version
            ">" -> candidate > version
            "<" -> candidate < version
            "any" -> true
            else -> candidate == version
        }
    }

    private data class Version(private val parts: List<Int>) : Comparable<Version> {

        override fun compareTo(other: Version): Int {
            for (index in 0 until 4) {
                val left = parts[index]
                val right = other.parts[index]
                if (left != right) {
                    return left.compareTo(right)
                }
            }
            return 0
        }

        companion object {

            fun parse(raw: String?): Version? {
                val text = raw?.trim().orEmpty()
                if (text.isEmpty()) {
                    return null
                }
                val withoutPrefix = if (text.startsWith("v") || text.startsWith("V")) text.substring(1) else text
                val cut = withoutPrefix.indexOfFirst { it == '-' || it == '+' }
                val core = if (cut >= 0) withoutPrefix.substring(0, cut) else withoutPrefix
                if (core.isEmpty()) {
                    return null
                }
                val pieces = core.split('.')
                if (pieces.isEmpty() || pieces.size > 4) {
                    return null
                }
                val numbers = ArrayList<Int>(4)
                for (piece in pieces) {
                    if (piece.isEmpty()) {
                        return null
                    }
                    val value = piece.toIntOrNull() ?: return null
                    if (value < 0) {
                        return null
                    }
                    numbers.add(value)
                }
                while (numbers.size < 4) {
                    numbers.add(0)
                }
                return Version(numbers)
            }
        }
    }

    companion object {

        /** 解析表达式；空串与 {@code *} 表示任意版本，非法表达式返回 null。 */
        @JvmStatic
        fun parse(raw: String?): VersionRange? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text == "*") {
                return VersionRange(listOf(emptyList()))
            }
            val groups = ArrayList<List<Bound>>()
            for (part in text.split(',')) {
                val tokens = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) {
                    return null
                }
                val bounds = ArrayList<Bound>()
                for (token in tokens) {
                    val bound = parseBound(token) ?: return null
                    bounds.add(bound)
                }
                groups.add(bounds)
            }
            return VersionRange(groups)
        }

        private fun parseBound(token: String): Bound? {
            if (token == "*") {
                return Bound("any", Version(listOf(0, 0, 0, 0)))
            }
            val operator = when {
                token.startsWith(">=") -> ">="
                token.startsWith("<=") -> "<="
                token.startsWith("==") -> "="
                token.startsWith(">") -> ">"
                token.startsWith("<") -> "<"
                token.startsWith("=") -> "="
                else -> "="
            }
            val rawVersion = when {
                token.startsWith(">=") -> token.substring(2)
                token.startsWith("<=") -> token.substring(2)
                token.startsWith("==") -> token.substring(2)
                token.startsWith(">") -> token.substring(1)
                token.startsWith("<") -> token.substring(1)
                token.startsWith("=") -> token.substring(1)
                else -> token
            }
            val version = Version.parse(rawVersion) ?: return null
            return Bound(operator, version)
        }
    }
}
