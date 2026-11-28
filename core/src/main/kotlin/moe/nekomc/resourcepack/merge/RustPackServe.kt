package moe.nekomc.resourcepack.merge

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 常驻 rps-pack 会话池：按可执行文件路径复用进程，失败后短期冷却再重试。 */
object RustPackServe {

    /** 失败后的冷却时长：期间直接走一次性进程，到点后允许重新尝试常驻会话。 */
    private const val RETRY_MILLIS = 60_000L

    private val sessions = ConcurrentHashMap<Path, RustPackServeSession>()
    private val retryAt = ConcurrentHashMap<Path, Long>()
    private val failures = ConcurrentHashMap<Path, AtomicInteger>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            closeAll()
        }, "rps-pack-serve-shutdown"))
    }

    /** 返回合并报告；会话不可用或处于冷却期时返回 null，调用方走一次性进程。 */
    @JvmStatic
    fun call(binary: Path, workDir: Path, frame: JsonObject, timeoutMs: Int): JsonObject? {
        val key = binary.toAbsolutePath().normalize()
        val until = retryAt[key]
        if (until != null && System.currentTimeMillis() < until) {
            return null
        }
        val session = try {
            sessions.computeIfAbsent(key) {
                RustPackServeSession.start(it, workDir.toAbsolutePath().normalize())
            }
        } catch (error: Exception) {
            coolDown(key, "启动常驻会话失败", error)
            return null
        }
        return try {
            val result = session.call(frame, timeoutMs)
            retryAt.remove(key)
            failures.remove(key)
            result
        } catch (error: Exception) {
            close(key)
            coolDown(key, "常驻会话调用失败", error)
            null
        }
    }

    /** 当前存活的常驻会话数，供测试确认复用而不是每次新建。 */
    @JvmStatic
    fun activeSessions(): Int = sessions.size

    /** 记下冷却截止时间；只告警第一次，避免每帧刷日志。 */
    private fun coolDown(key: Path, what: String, error: Exception) {
        retryAt[key] = System.currentTimeMillis() + RETRY_MILLIS
        val count = failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        if (count == 1) {
            System.err.println(
                "[资源包] " + what + "，" + RETRY_MILLIS + " ms 内改用一次性进程：" + error.message
            )
        }
    }

    private fun close(key: Path) {
        sessions.remove(key)?.close()
    }

    private fun closeAll() {
        for (key in sessions.keys.toList()) {
            close(key)
        }
    }
}
