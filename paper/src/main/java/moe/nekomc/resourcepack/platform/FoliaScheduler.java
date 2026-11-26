package moe.nekomc.resourcepack.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.Plugin;

/**
 * 用 Folia 的异步调度器实现内核的调度端口。
 *
 * <p>异步线程池与区域线程无关，来源监听、远程索引检查都跑在这里。</p>
 */
public final class FoliaScheduler implements Scheduler {

    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Cancellable runAsyncNow(Runnable task) {
        ScheduledTask handle = plugin.getServer().getAsyncScheduler()
            .runNow(plugin, scheduled -> task.run());
        return handle::cancel;
    }

    @Override
    public Cancellable runAsyncTimer(long delayMillis, long periodMillis, Runnable task) {
        ScheduledTask handle = plugin.getServer().getAsyncScheduler()
            .runAtFixedRate(plugin, scheduled -> task.run(), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
        return handle::cancel;
    }
}
