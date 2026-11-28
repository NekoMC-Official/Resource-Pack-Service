package moe.nekomc.resourcepack.delivery

import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Logger
import moe.nekomc.resourcepack.api.PackStatus
import moe.nekomc.resourcepack.platform.Scheduler

/**
 * 统一推送的调度器：把「一次推给很多人」拆成按 tick 分批、带抖动、带并发窗口的队列。
 *
 * <p>它只管节奏，不管协议：真正的发包仍然走 [PackDelivery.push]，平台差异留在适配层。
 * 队列长度与在线玩家数同阶，不复制包体积；同一玩家同一摘要只排一次。</p>
 *
 * <p>线程模型：所有状态由 [lock] 保护；[PackDelivery.push] 在锁外调用，避免平台侧回调
 * 反向进入时死锁。tick 跑在平台的异步调度器上，默认 50 ms 一次。</p>
 */
class FanoutController @JvmOverloads constructor(
    private val delivery: PackDelivery,
    private val scheduler: Scheduler,
    private val logger: Logger,
    private val maxConcurrent: Int = 64,
    private val jitterMs: Int = 1500,
    private val retryLimit: Int = 1,
    private val ramp: Boolean = true,
    private val retryDelayMs: Long = 5_000L,
    private val pendingTimeoutMs: Long = 120_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    /** 队列与窗口的只读快照，供 /rps status 展示。 */
    class Stats(
        val queued: Int,
        val inflight: Int,
        val pushed: Long,
        val completed: Long,
        val failed: Long,
        val declined: Long,
        val dropped: Long,
        val retried: Long,
        val window: Int,
    )

    private data class Task(
        val player: UUID,
        val sha1: String,
        val attempts: Int,
        val readyAt: Long,
        val seq: Long,
        val force: Boolean,
        val join: Boolean,
    )

    private class Inflight(
        val sha1: String,
        val at: Long,
        val attempts: Int,
        val force: Boolean,
    )

    private val lock = Any()

    // 两个队列而不是一个：进服任务需要优先，但不能因为它的抖动窗口挡住已经到期的广播任务。
    private val joinQueue = PriorityQueue<Task>(compareBy({ it.readyAt }, { it.seq }))
    private val broadcastQueue = PriorityQueue<Task>(compareBy({ it.readyAt }, { it.seq }))
    private val inflight = HashMap<UUID, Inflight>()
    private val pending = HashMap<UUID, Task>()

    private var ticker: Scheduler.Cancellable? = null
    private var closed = false
    private var ticking = false
    private var seq = 0L

    private val floorWindow = minOf(maxConcurrent, MIN_WINDOW)
    private var window = if (ramp) minOf(maxConcurrent, INITIAL_WINDOW) else maxConcurrent
    private var lastRampAt = 0L
    private var windowPushes = 0L
    private var windowFailures = 0L

    private var pushed = 0L
    private var completed = 0L
    private var failed = 0L
    private var declined = 0L
    private var dropped = 0L
    private var retried = 0L

    /**
     * 把一批玩家排进队列。
     *
     * @param sha1 目标摘要；回执会拿它判断是否属于同一代，force 为真时仍保留这个摘要
     * @param join 是否进服推送：进服任务优先级更高，并额外加 0..jitterMs 抖动
     * @param force 强制重推：跳过「已加载同一摘要」过滤，但仍记录摘要以便识别迟到回执
     */
    @JvmOverloads
    fun enqueue(
        players: Collection<UUID>,
        sha1: String,
        join: Boolean,
        now: Long = clock(),
        force: Boolean = false,
    ) {
        if (players.isEmpty()) {
            return
        }
        val target = sha1.trim()
        synchronized(lock) {
            if (closed) {
                return
            }
            ensureTicker()
            for (player in players) {
                val current = inflight[player]
                if (current != null && (target.isEmpty() || current.sha1 == target)) {
                    dropped++
                    continue
                }
                if (!force && target.isNotEmpty() && delivery.loadedSha1(player) == target) {
                    dropped++
                    continue
                }
                val previous = pending[player]
                if (previous != null && previous.sha1 == target) {
                    dropped++
                    continue
                }
                if (previous != null) {
                    // 换代：旧任务被新任务替换；队列里的旧项在派发时按 seq 识别并丢掉。
                    dropped++
                }
                val readyAt = if (join) now + ThreadLocalRandom.current().nextLong(jitterMs + 1L) else now
                val task = Task(player, target, 0, readyAt, seq++, force, join)
                pending[player] = task
                if (join) {
                    joinQueue.add(task)
                } else {
                    broadcastQueue.add(task)
                }
            }
        }
    }

    /**
     * 客户端回执：释放并发窗口；失败按配置重试一次。
     *
     * <p>回执带的摘要与在途任务不一致时（上一次发布的包迟到的回执），只计一次 dropped 后忽略：
     * 当前在途的已经是新代，旧代回执不能清掉它的槽位，也不能消耗新代的重试额度；
     * 这个槽位由 expireInflight 在超时后回收。</p>
     */
    @JvmOverloads
    fun onStatus(player: UUID, status: PackStatus, sha1: String, now: Long = clock()) {
        synchronized(lock) {
            val current = inflight[player] ?: return
            if (sha1.isNotBlank() && current.sha1.isNotBlank() && sha1 != current.sha1) {
                // 当前在途任务已经是新代：旧代的迟到回执不能把它清掉，否则新代会被记错。
                dropped++
                return
            }
            when (status) {
                PackStatus.LOADED -> {
                    inflight.remove(player)
                    completed++
                }
                PackStatus.DECLINED -> {
                    inflight.remove(player)
                    declined++
                }
                PackStatus.FAILED, PackStatus.INVALID_URL -> {
                    inflight.remove(player)
                    windowFailures++
                    val retrySha1 = sha1.ifBlank { current.sha1 }
                    val queued = pending[player]
                    if (queued != null) {
                        if (queued.sha1 != retrySha1) {
                            // 新代已经在队列里：旧代的失败重试不能把它挤掉。
                            dropped++
                        }
                        return
                    }
                    if (current.attempts < retryLimit && !closed) {
                        val retry = Task(player, retrySha1, current.attempts + 1, now + retryDelayMs, seq++, current.force, false)
                        pending[player] = retry
                        retried++
                        broadcastQueue.add(retry)
                    } else {
                        failed++
                    }
                }
                else -> Unit
            }
        }
    }

    /** 玩家下线：释放可能被占住的窗口，并撤掉还没派发的任务。 */
    fun playerLeft(player: UUID) {
        synchronized(lock) {
            if (inflight.remove(player) != null) {
                dropped++
            }
            pending.remove(player)
            joinQueue.removeIf { it.player == player }
            broadcastQueue.removeIf { it.player == player }
        }
    }

    /** 当前队列与窗口。 */
    fun stats(): Stats = synchronized(lock) {
        Stats(joinQueue.size + broadcastQueue.size, inflight.size, pushed, completed, failed, declined, dropped, retried, window)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            ticker?.cancel()
            ticker = null
            joinQueue.clear()
            broadcastQueue.clear()
            inflight.clear()
            pending.clear()
        }
    }

    private fun ensureTicker() {
        if (ticker != null) {
            return
        }
        lastRampAt = clock()
        ticker = scheduler.runAsyncTimer(TICK_MILLIS, TICK_MILLIS) { tick() }
    }

    private fun tick() {
        val due = ArrayList<Task>(TICK_LIMIT)
        val now = clock()
        synchronized(lock) {
            // Fabric 的 TickScheduler 是线程池：上一拍超过 50ms 时下一拍会在另一线程开跑。
            if (closed || ticking) {
                return
            }
            ticking = true
        }
        try {
            synchronized(lock) {
                expireInflight(now)
                while (due.size < TICK_LIMIT && inflight.size < window) {
                    val head = takeReady(now) ?: break
                val latest = pending[head.player]
                if (latest == null || latest.seq != head.seq) {
                    // 队列里的旧代任务：已被更新的任务替换，丢掉旧项即可。
                    dropped++
                    continue
                }
                if (inflight.containsKey(head.player)) {
                    // 上一代还没回执：把这一代按重试间隔延后，新包不能丢。
                    val delayed = head.copy(readyAt = now + retryDelayMs)
                    pending[head.player] = delayed
                    if (head.join) {
                        joinQueue.add(delayed)
                    } else {
                        broadcastQueue.add(delayed)
                    }
                    continue
                }
                pending.remove(head.player)
                inflight[head.player] = Inflight(head.sha1, now, head.attempts, head.force)
                    due.add(head)
                }
                windowPushes += due.size
                rampWindow(now)
            }
            for (task in due) {
                if (synchronized(lock) { closed }) {
                    break
                }
                val accepted = try {
                    delivery.push(task.player, task.sha1, task.force)
                } catch (error: Throwable) {
                    logger.warning("推送失败：" + (error.message ?: error.javaClass.simpleName))
                    0
                }
                if (accepted <= 0) {
                    synchronized(lock) {
                        inflight.remove(task.player)
                        dropped++
                        windowFailures++
                    }
                } else {
                    synchronized(lock) {
                        // 只有真正交到平台手上的才算推送成功。
                        pushed++
                    }
                }
            }
        } finally {
            synchronized(lock) {
                ticking = false
            }
        }
    }

    /** 取一个已经到期的任务：进服队列优先，但它没到期时不会挡住广播队列。 */
    private fun takeReady(now: Long): Task? {
        val joinHead = joinQueue.peek()
        if (joinHead != null && joinHead.readyAt <= now) {
            return joinQueue.poll()
        }
        val broadcastHead = broadcastQueue.peek()
        if (broadcastHead != null && broadcastHead.readyAt <= now) {
            return broadcastQueue.poll()
        }
        return null
    }

    private fun expireInflight(now: Long) {
        val iterator = inflight.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.at >= pendingTimeoutMs) {
                iterator.remove()
                failed++
                windowFailures++
            }
        }
    }

    /** 渐进升窗：失败率高于 2% 立刻减半，低于 1% 每 5 秒加 25%。 */
    private fun rampWindow(now: Long) {
        if (!ramp || now - lastRampAt < RAMP_MILLIS) {
            return
        }
        lastRampAt = now
        val samples = windowPushes
        if (samples >= 8) {
            val rate = windowFailures.toDouble() / samples.toDouble()
            window = when {
                rate > 0.02 -> maxOf(floorWindow, window / 2)
                rate < 0.01 -> minOf(maxConcurrent, maxOf(floorWindow, window + window / 4))
                else -> window
            }
        }
        windowPushes = 0
        windowFailures = 0
    }

    private companion object {
        const val TICK_MILLIS = 50L
        const val TICK_LIMIT = 200
        const val INITIAL_WINDOW = 8
        const val MIN_WINDOW = 8
        const val RAMP_MILLIS = 5_000L
    }
}