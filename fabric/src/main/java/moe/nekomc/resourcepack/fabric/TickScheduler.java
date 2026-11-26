package moe.nekomc.resourcepack.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.nekomc.resourcepack.platform.Scheduler;
import net.minecraft.server.MinecraftServer;

/**
 * 用服务端 tick 驱动的调度器。
 *
 * <p>Fabric 没有 Bukkit 的 asyncScheduler，这里自己按毫秒记账：每次 tick 扫一遍到期任务，
 * 真正的工作丢到固定大小的守护线程池，避免拖慢主线程。计时用 {@link System#nanoTime()}
 * （单调，不受系统时钟回拨影响），精度等于一个 tick。</p>
 */
public final class TickScheduler implements Scheduler {
    private static final long TICK_MILLIS = 50L;

    private final CopyOnWriteArrayList<Task> tasks = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;
    private final Logger logger;
    private volatile MinecraftServer server;

    public TickScheduler(Logger logger) {
        this.logger = logger;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "resource-pack-service-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 固定大小：任务都是秒级 IO/合成，缓存线程池在异常情况下会无限长。
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    /** 服务端启动后绑定；之前排程的任务先攒着。 */
    public void attach(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
    }

    public MinecraftServer server() {
        return server;
    }

    @Override
    public Cancellable runAsyncNow(Runnable task) {
        Task entry = new Task(task, 0L, 0L, System.nanoTime());
        tasks.add(entry);
        return entry;
    }

    @Override
    public Cancellable runAsyncTimer(long delayMillis, long periodMillis, Runnable task) {
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, delayMillis));
        Task entry = new Task(task, delayMillis, periodMillis, System.nanoTime() + delayNanos);
        tasks.add(entry);
        return entry;
    }

    /** 每个服务端 tick 调用一次；到期的任务在异步线程执行。 */
    public void tick() {
        if (executor.isShutdown()) {
            // shutdown() 之后的最后一拍不该把 RejectedExecutionException 抛到服务端线程
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        List<Task> due = null;
        for (Task task : tasks) {
            if (task.cancelled || task.nextAt > now) {
                continue;
            }
            if (due == null) {
                due = new ArrayList<>(2);
            }
            due.add(task);
        }
        if (due == null) {
            return;
        }
        for (Task task : due) {
            if (task.cancelled) {
                tasks.remove(task);
                continue;
            }
            if (task.periodMillis <= 0L) {
                tasks.remove(task);
            } else {
                // 落后多个周期时只补一次，避免卡顿后突发补跑。
                long period = TimeUnit.MILLISECONDS.toNanos(task.periodMillis);
                task.nextAt = Math.max(task.nextAt + period, now);
            }
            executor.execute(() -> {
                if (task.cancelled) {
                    return;
                }
                try {
                    task.runnable.run();
                } catch (Throwable error) {
                    // 单个任务失败不影响其它任务，但必须留痕：否则看门狗坏掉也无从发现。
                    logger.log(Level.WARNING, "[ResourcePackService] 异步任务执行失败", error);
                }
            });
        }
    }

    /** 关服时停掉线程池；等待中的任务直接丢弃。 */
    public void shutdown() {
        for (Task task : tasks) {
            task.cancelled = true;
        }
        tasks.clear();
        executor.shutdownNow();
    }

    /** 一个排程条目。 */
    private final class Task implements Cancellable {
        private final Runnable runnable;
        private final long periodMillis;
        private volatile long nextAt;
        private volatile boolean cancelled;

        private Task(Runnable runnable, long delayMillis, long periodMillis, long nextAt) {
            this.runnable = runnable;
            this.periodMillis = Math.max(0L, periodMillis);
            this.nextAt = nextAt;
        }

        @Override
        public void cancel() {
            cancelled = true;
            tasks.remove(this);
        }
    }
}
