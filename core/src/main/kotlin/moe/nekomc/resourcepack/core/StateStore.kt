package moe.nekomc.resourcepack.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 合成包世代与上次构建信息（state.json）。
 *
 * 只认三个字段：generation / sha1 / builtAt。文件坏了就当从零开始，不阻塞启动——
 * 代价只是客户端会重新下载一次同样的包。
 */
class StateStore(private val file: Path) {

    private var generationValue: Int = 0
    private var sha1Value: String = ""
    private var builtAtValue: String = ""

    init {
        load()
    }

    @Synchronized
    fun generation(): Int = generationValue

    @Synchronized
    fun lastSha1(): String = sha1Value

    @Synchronized
    fun lastBuildAt(): String = builtAtValue

    /**
     * 提交一次构建结果。
     *
     * @return true 表示内容确实变了（世代号已加一）；false 表示同一份内容，世代号不变
     */
    @Synchronized
    fun commit(sha1: String, buildAt: String): Boolean {
        val same = sha1Value.equals(sha1, ignoreCase = true)
        sha1Value = sha1
        builtAtValue = buildAt
        if (same) {
            save()
            return false
        }
        generationValue = (generationValue + 1).coerceAtLeast(1)
        save()
        return true
    }

    private fun load() {
        if (!Files.isRegularFile(file)) {
            return
        }
        val text = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (exception: IOException) {
            return
        }
        generationValue = match(text, "generation")?.toIntOrNull() ?: 0
        sha1Value = match(text, "sha1").orEmpty()
        builtAtValue = match(text, "builtAt").orEmpty()
    }

    private fun save() {
        val parent = file.parent
        try {
            if (parent != null) {
                Files.createDirectories(parent)
            }
            val text = "{\"generation\":" + generationValue +
                ",\"sha1\":\"" + sha1Value + "\"" +
                ",\"builtAt\":\"" + builtAtValue + "\"}" + "\n"
            // 先写临时文件再换名：进程中途退出也不会留下半截 JSON。
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, text, StandardCharsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            // 状态文件写不了只影响世代号连续性，不影响本次下发
        }
    }

    private fun match(text: String, key: String): String? {
        // 值里可能有空格（builtAt 的日期时间），不能用空白做分隔。
        val pattern = Regex("\"" + key + "\"\\s*:\\s*\"?([^\",}]*)")
        return pattern.find(text)?.groupValues?.get(1)
    }
}
