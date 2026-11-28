package moe.nekomc.resourcepack.watch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import moe.nekomc.resourcepack.pack.PackHost
import moe.nekomc.resourcepack.platform.Scheduler

/**
 * 来源文件变动侦测（周期由 platform.Scheduler 提供，内核不认具体服务端的调度器）。
 *
 * 只比较修改时间与大小，不做内容哈希：来源动一下就重建，重建自己会判断内容是否真的变了。
 */
class SourceWatcher(
    private val host: PackHost,
    val intervalSeconds: Long,
    private val paths: Supplier<List<Path>>,
    private val onChange: Runnable
) {

    private val stamps = ConcurrentHashMap<Path, String>()

    @Volatile
    private var task: Scheduler.Cancellable? = null

    /** 已发现变动但还没到防抖窗口；下一轮继续触发，不会丢。 */
    @Volatile
    private var pendingChange = false
    private var lastFireAt = 0L

    @Synchronized
    fun start() {
        if (task != null) {
            return
        }
        if (intervalSeconds <= 0L) {
            host.logger().warning("[资源包] watch.interval-seconds 必须大于 0，来源自动侦测未启动")
            return
        }
        val periodMs = (intervalSeconds * 1000L).coerceAtLeast(1000L)
        // 首次变动也要走完整的防抖窗口，避免读到正在写入的包。
        lastFireAt = System.currentTimeMillis()
        task = host.scheduler().runAsyncTimer(periodMs, periodMs, Runnable { tick() })
    }

    @Synchronized
    fun stop() {
        val current = task
        task = null
        current?.cancel()
        stamps.clear()
        pendingChange = false
    }

    /** 定时快照比较；任何异常都在这里吞掉，否则周期任务会被平台停掉。 */
    @Synchronized
    fun tick() {
        try {
            val snapshot = paths.get()
            val seen = HashSet<Path>(snapshot.size * 2 + 2)
            val pending = HashMap<Path, String>()
            var changed = false
            for (path in snapshot) {
                if (!seen.add(path)) {
                    continue
                }
                val mark = stampOf(path)
                val previous = stamps[path]
                // 首次见到只记录快照；文件消失/读不到不算变化，重新出现才触发一次重建。
                if (previous != null && previous != mark && mark != MISSING && mark != ERROR) {
                    changed = true
                }
                pending[path] = mark
            }
            stamps.keys.retainAll(seen)
            if (changed) {
                pendingChange = true
            }
            val now = System.currentTimeMillis()
            if (pendingChange && now - lastFireAt >= DEBOUNCE_MILLIS) {
                onChange.run()
                lastFireAt = now
                pendingChange = false
            }
            // 回调成功后再落新快照；回调抛错时下一轮仍能看到旧值并重试。
            stamps.putAll(pending)
        } catch (exception: Exception) {
            host.logger().warning("[资源包] 来源变动检查失败：" + exception.message)
        }
    }

    private fun stampOf(path: Path): String {
        return try {
            if (!Files.isRegularFile(path)) {
                MISSING
            } else {
                val modified: FileTime = Files.getLastModifiedTime(path)
                modified.to(TimeUnit.MILLISECONDS).toString() + ":" + Files.size(path)
            }
        } catch (exception: Exception) {
            ERROR
        }
    }

    private companion object {
        /** 变动后的合并窗口：连续写入只触发一次重建。 */
        private const val DEBOUNCE_MILLIS = 300L
        private const val MISSING = "missing"
        private const val ERROR = "error"
    }
}
