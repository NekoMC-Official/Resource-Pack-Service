package moe.nekomc.resourcepack.watch;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** 轮询来源文件的时间戳，任何一个变化就触发一次重建。 */
public final class SourceWatcher {

    private final Plugin plugin;
    private final long intervalSeconds;
    private final Supplier<List<Path>> paths;
    private final Runnable onChange;
    private final Map<Path, Long> seen = new ConcurrentHashMap<>();
    private volatile ScheduledTask task;

    public SourceWatcher(Plugin plugin, long intervalSeconds, Supplier<List<Path>> paths, Runnable onChange) {
        this.plugin = plugin;
        this.intervalSeconds = Math.max(intervalSeconds, 5L);
        this.paths = paths;
        this.onChange = onChange;
    }

    public void start() {
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduled -> tick(),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledTask current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }

    private void tick() {
        boolean changed = false;
        for (Path path : paths.get()) {
            long stamp;
            try {
                stamp = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException missing) {
                stamp = -1L;
            }
            Long previous = seen.put(path, stamp);
            if (previous != null && previous.longValue() != stamp) {
                changed = true;
            }
        }
        if (changed) {
            onChange.run();
        }
    }
}
