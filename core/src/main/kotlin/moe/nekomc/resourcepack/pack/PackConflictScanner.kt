package moe.nekomc.resourcepack.pack

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 扫别的插件/mod 有没有自己配了资源包地址。
 *
 * 两个来源各推一个包时客户端只会显示最后那个，症状是"合成包没生效"。这里只做提示，
 * 不改别人的配置：找到的是疑似项，由管理员判断。
 *
 * 支持两种布局：
 * - Bukkit：`plugins/<插件名>/config.yml`、`plugins/<插件名>/config/config.yml`
 * - Fabric：`config/<mod>.toml`、`config/<mod>/<任意>.toml`，以及直接放在扫描根下的 `*.yml`
 */
object PackConflictScanner {

    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_FILES = 400
    private const val MAX_TOML_PER_FOLDER = 8

    /** 超长单行不参与正则匹配：回溯代价与行长平方相关，且真实配置不会这么长。 */
    private const val MAX_LINE_CHARS = 4096

    private val CONFIG_NAMES = setOf("config.yml", "config.yaml", "settings.yml", "settings.yaml")
    private val LOOSE_SUFFIXES = listOf(".yml", ".yaml", ".toml", ".properties", ".conf")

    /** `key: value`（YAML）。 */
    private val KEY_PATTERN = Regex(
        "^\\s*(resource[-_]?pack|pack[-_]?url|send[-_]?on[-_]?join|pack[-_]?send)\\s*:\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** `key = value`（TOML / properties），键允许带前缀，例如 `server-resource-pack`。 */
    private val ASSIGN_PATTERN = Regex(
        "^\\s*([A-Za-z0-9_.-]*(?:resource[-_]?pack|pack[-_]?url)[A-Za-z0-9_.-]*)\\s*=\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** 一条疑似冲突。 */
    data class Finding(
        private val pluginName: String,
        private val configFile: Path,
        private val lineNumber: Int,
        private val keyName: String,
        private val rawValue: String
    ) {
        fun plugin(): String = pluginName
        fun file(): Path = configFile
        fun line(): Int = lineNumber
        fun key(): String = keyName
        fun value(): String = rawValue
    }

    /** 单目录扫描，等价于 [scanRoots] 传一个根。 */
    @JvmStatic
    fun scan(pluginsDir: Path, ownFolder: String, limit: Int): List<Finding> =
        scanRoots(listOf(pluginsDir), ownFolder, limit)

    /**
     * 扫多个根目录：子目录按"插件/mod 名"处理，根下直接放的配置文件按文件名当来源名。
     *
     * @param ownFolder 自己的数据目录名，扫到时跳过
     */
    @JvmStatic
    fun scanRoots(roots: List<Path>, ownFolder: String, limit: Int): List<Finding> {
        val findings = ArrayList<Finding>()
        val cap = if (limit <= 0) Int.MAX_VALUE else limit
        var visited = 0
        for (root in roots) {
            if (visited >= MAX_FILES || findings.size >= cap) {
                break
            }
            if (!Files.isDirectory(root)) {
                continue
            }
            val entries = try {
                Files.list(root).use { stream -> stream.sorted().toList() }
            } catch (exception: IOException) {
                continue
            }
            for (entry in entries) {
                if (visited >= MAX_FILES || findings.size >= cap) {
                    break
                }
                if (Files.isDirectory(entry)) {
                    val name = entry.fileName.toString()
                    if (name.equals(ownFolder, ignoreCase = true)) {
                        continue
                    }
                    for (file in candidates(entry)) {
                        if (visited >= MAX_FILES || findings.size >= cap) {
                            break
                        }
                        visited++
                        scanFile(name, file, findings, cap)
                    }
                } else if (isLooseConfig(entry)) {
                    visited++
                    scanFile(entry.fileName.toString().substringBeforeLast('.'), entry, findings, cap)
                }
            }
        }
        return findings
    }

    private fun candidates(folder: Path): List<Path> {
        val out = ArrayList<Path>(3)
        for (fileName in CONFIG_NAMES) {
            val direct = folder.resolve(fileName)
            if (Files.isRegularFile(direct)) {
                out.add(direct)
            }
            val nested = folder.resolve("config").resolve(fileName)
            if (Files.isRegularFile(nested)) {
                out.add(nested)
            }
        }
        // Fabric 的 mod 配置绝大多数是 TOML：config/<mod>/xxx.toml
        try {
            Files.list(folder).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml", ignoreCase = true) }
                    .sorted()
                    .limit(MAX_TOML_PER_FOLDER.toLong())
                    .forEach { out.add(it) }
            }
        } catch (exception: IOException) {
            // 目录读不了就只保留 YAML 候选
        }
        return out
    }

    private fun isLooseConfig(file: Path): Boolean {
        if (!Files.isRegularFile(file)) {
            return false
        }
        val name = file.fileName.toString().lowercase(Locale.ROOT)
        return LOOSE_SUFFIXES.any { name.endsWith(it) }
    }

    private fun scanFile(pluginName: String, file: Path, into: MutableList<Finding>, cap: Int) {
        val size = try {
            Files.size(file)
        } catch (exception: IOException) {
            return
        }
        if (size > MAX_FILE_BYTES) {
            return
        }
        val lines = try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
        } catch (exception: IOException) {
            return
        }
        for ((index, line) in lines.withIndex()) {
            if (into.size >= cap) {
                return
            }
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue
            }
            val lower = line.lowercase(Locale.ROOT)
                if (line.length > MAX_LINE_CHARS || (!lower.contains("pack") && !lower.contains("join"))) {
                continue
            }
            val match = KEY_PATTERN.find(line) ?: ASSIGN_PATTERN.find(line) ?: continue
            val key = match.groupValues[1]
            val value = clean(match.groupValues[2])
            if (!suspicious(value)) {
                continue
            }
            into.add(Finding(pluginName, file, index + 1, key.lowercase(Locale.ROOT), value))
        }
    }

    private fun clean(raw: String): String {
        var value = raw.trim()
        val comment = value.indexOf(" #")
        if (comment >= 0) {
            value = value.substring(0, comment).trim()
        }
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'')) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    private fun suspicious(value: String): Boolean {
        if (value.isEmpty()) {
            return false
        }
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains("http://") || lower.contains("https://") || lower == "true"
    }
}
