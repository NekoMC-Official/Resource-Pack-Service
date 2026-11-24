package moe.nekomc.resourcepack.watch

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier
import org.bukkit.plugin.Plugin

/**
 * 来源文件变动侦测（Kotlin 编排层调用 Bukkit 的异步调度器）。
 *
 * 只比较修改时间与大小，不做内容哈希：来源动一下就重建，重建自己会判断内容是否真的变了。
 */
class SourceWatcher(
    private val plugin: Plugin,
    private val intervalSeconds: Long,
    private val paths: Supplier<List<Path>>,
    private val onChange: Runnable
) {

    private val stamps = HashMap<Path, String>()
    private var task: ScheduledTask? = null

    fun start() {
        if (task != null || intervalSeconds <= 0L) {
            return
        }
        val periodMs = (intervalSeconds * 1000L).coerceAtLeast(1000L)
        task = plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            Consumer { },
            periodMs,
            periodMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        val current = task
        task = null
        current?.cancel()
        stamps.clear()
    }

    private fun tick() {
        var changed = false
        for (path in paths.get()) {
            val mark = stampOf(path)
            val previous = stamps.put(path, mark)
            if (previous != null && previous != mark) {
                changed = true
            }
        }
        if (changed) {
            try {
                onChange.run()
            } catch (exception: RuntimeException) {
                plugin.logger.warning("[资源包] 侦测到来源变化后重建失败：" + exception.message)
            }
        }
    }

    private fun stampOf(path: Path): String {
        return try {
            if (!Files.isRegularFile(path)) {
                "missing"
            } else {
                val modified: FileTime = Files.getLastModifiedTime(path)
                modified.to(TimeUnit.MILLISECONDS).toString() + ":" + Files.size(path)
            }
        } catch (exception: Exception) {
            "error"
        }
    }
}
