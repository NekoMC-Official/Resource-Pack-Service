package moe.nekomc.resourcepack.pack

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 扫别的插件有没有自己配了资源包地址。
 *
 * 两个插件各推一个包时客户端只会显示最后那个，症状是"合成包没生效"。这里只做提示，
 * 不改别人的配置：找到的是疑似项，由管理员判断。
 */
object PackConflictScanner {

    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_FILES = 400

    private val CONFIG_NAMES = setOf("config.yml", "config.yaml", "settings.yml", "settings.yaml")
    private val KEY_PATTERN = Regex(
        "^\\s*(resource[-_]?pack|pack[-_]?url|send[-_]?on[-_]?join|pack[-_]?send)\\s*:\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** 一条疑似冲突。 */
    class Finding(
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

    /** 扫 plugins 目录下除自己以外的插件配置。 */
    @JvmStatic
    fun scan(pluginsDir: Path, ownFolder: String, limit: Int): List<Finding> {
        if (!Files.isDirectory(pluginsDir)) {
            return emptyList()
        }
        val findings = ArrayList<Finding>()
        val cap = if (limit <= 0) Int.MAX_VALUE else limit
        var visited = 0
        val folders = try {
            Files.list(pluginsDir).use { stream -> stream.sorted().toList() }
        } catch (exception: IOException) {
            return emptyList()
        }
        for (folder in folders) {
            if (visited >= MAX_FILES || findings.size >= cap) {
                break
            }
            if (!Files.isDirectory(folder)) {
                continue
            }
            val name = folder.fileName.toString()
            if (name.equals(ownFolder, ignoreCase = true)) {
                continue
            }
            for (file in candidates(folder)) {
                if (visited >= MAX_FILES || findings.size >= cap) {
                    break
                }
                visited++
                scanFile(name, file, findings, cap)
            }
        }
        return findings
    }

    private fun candidates(folder: Path): List<Path> {
        val out = ArrayList<Path>(2)
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
        return out
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
            val match = KEY_PATTERN.find(line) ?: continue
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
