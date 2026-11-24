package moe.nekomc.resourcepack.pack

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import java.util.zip.ZipFile

/**
 * 合成包条目清单与两版差异。
 *
 * 每次构建后把"sha1 + 路径"写进 entries.txt 并把旧的一份挪到 entries.prev.txt，
 * 于是差异不依赖保留旧 zip：/rps diff 读两份清单就够了。
 */
object EntryManifest {

    const val CURRENT: String = "entries.txt"
    const val PREVIOUS: String = "entries.prev.txt"

    /** 写新清单前先把旧的挪成 prev。 */
    @JvmStatic
    fun rotateAndWrite(dir: Path, zip: Path) {
        val current = dir.resolve(CURRENT)
        val previous = dir.resolve(PREVIOUS)
        try {
            Files.createDirectories(dir)
            if (Files.isRegularFile(current)) {
                Files.copy(current, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            // 挪不动旧清单时最多丢一次 diff，不影响构建
        }
        val entries = scan(zip)
        val text = StringBuilder(entries.size * 48)
        for ((path, sha1) in entries) {
            text.append(sha1).append(' ').append(path).append('\n')
        }
        try {
            Files.writeString(current, text.toString(), StandardCharsets.UTF_8)
        } catch (exception: IOException) {
            throw IOException("写条目清单失败：" + exception.message, exception)
        }
    }

    /** 扫描 zip 里的文件条目，返回 路径 -> sha1。 */
    @JvmStatic
    fun scan(zip: Path): Map<String, String> {
        val out = TreeMap<String, String>()
        if (!Files.isRegularFile(zip)) {
            return out
        }
        ZipFile(zip.toFile()).use { archive ->
            val iterator = archive.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                if (entry.isDirectory()) {
                    continue
                }
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                out[entry.name] = sha1Of(bytes)
            }
        }
        return out
    }

    /** 读一份清单。 */
    @JvmStatic
    fun read(file: Path): Map<String, String> {
        val out = TreeMap<String, String>()
        if (!Files.isRegularFile(file)) {
            return out
        }
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) {
                continue
            }
            val space = text.indexOf(' ')
            if (space <= 0) {
                continue
            }
            out[text.substring(space + 1)] = text.substring(0, space)
        }
        return out
    }

    /** 首行是汇总，后面是 变更/新增/删除 列表；超过 limit 时截断。 */
    @JvmStatic
    fun report(dir: Path, limit: Int): List<String> {
        val current = read(dir.resolve(CURRENT))
        val previous = read(dir.resolve(PREVIOUS))
        if (current.isEmpty() && previous.isEmpty()) {
            return emptyList()
        }
        val changed = ArrayList<String>()
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        for ((path, sha1) in current) {
            val before = previous[path]
            when {
                before == null -> added.add(path)
                before != sha1 -> changed.add(path)
            }
        }
        for (path in previous.keys) {
            if (!current.containsKey(path)) {
                removed.add(path)
            }
        }
        val lines = ArrayList<String>(changed.size + added.size + removed.size + 1)
        lines.add("新增 " + added.size + " · 变更 " + changed.size + " · 删除 " + removed.size + " （清单 " + current.size + " 条）")
        for (path in changed) {
            lines.add("~ " + path)
        }
        for (path in added) {
            lines.add("+ " + path)
        }
        for (path in removed) {
            lines.add("- " + path)
        }
        val cap = if (limit <= 0) lines.size else minOf(lines.size, limit + 1)
        if (lines.size > cap) {
            return lines.subList(0, cap)
        }
        return lines
    }

    private fun sha1Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        val hex = StringBuilder(40)
        for (value in digest) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value))
        }
        return hex.toString()
    }
}
