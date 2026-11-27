package moe.nekomc.resourcepack.merge

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** 常驻 rps-pack 会话池：按可执行文件路径复用进程，失败后永久回退一次性调用。 */
object RustPackServe {

    private val sessions = ConcurrentHashMap<Path, RustPackServeSession>()
    private val unsupported = ConcurrentHashMap.newKeySet<Path>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            closeAll()
        }, "rps-pack-serve-shutdown"))
    }

    /** 返回合并报告；会话不可用时返回 null，调用方走一次性进程。 */
    @JvmStatic
    fun call(binary: Path, workDir: Path, frame: JsonObject, timeoutMs: Int): JsonObject? {
        val key = binary.toAbsolutePath().normalize()
        if (key in unsupported) {
            return null
        }
        val session = try {
            sessions.computeIfAbsent(key) {
                RustPackServeSession.start(it, workDir.toAbsolutePath().normalize())
            }
        } catch (error: Exception) {
            unsupported.add(key)
            return null
        }
        return try {
            session.call(frame, timeoutMs)
        } catch (error: Exception) {
            close(key)
            unsupported.add(key)
            null
        }
    }

    /** 当前存活的常驻会话数，供测试确认复用而不是每次新建。 */
    @JvmStatic
    fun activeSessions(): Int = sessions.size

    private fun close(key: Path) {
        sessions.remove(key)?.close()
    }

    private fun closeAll() {
        for (key in sessions.keys.toList()) {
            close(key)
        }
    }
}
