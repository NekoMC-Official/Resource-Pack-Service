package moe.nekomc.resourcepack.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import moe.nekomc.resourcepack.ResourcePackServicePlugin;
import moe.nekomc.resourcepack.core.Hashing;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.core.RpsConfig;
import moe.nekomc.resourcepack.core.StateStore;
import moe.nekomc.resourcepack.merge.MergeResult;
import moe.nekomc.resourcepack.merge.Merger;
import moe.nekomc.resourcepack.merge.Mergers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/** 负责合并、落盘、换代与在线通知；同一时间只允许一次构建。 */
public final class PackPublisher implements PackView {

    public record Snapshot(String url, String sha1, int version, long bytes, long builtAt, String summary, String error) {

        public static Snapshot empty() {
            return new Snapshot("", "", 0, 0, 0, "", "");
        }

        public boolean ready() {
            return !url.isBlank() && version > 0;
        }
    }

    private final ResourcePackServicePlugin plugin;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Merger merger;

    private Merger merger() {
        Merger current = merger;
        if (current == null) {
            current = Mergers.rust(
                    plugin.config(), plugin.engineConfig(), plugin.getDataFolder().toPath(), plugin.getLogger());
            merger = current;
        }
        return current;
    }

    public java.util.List<String> diffReport() {
        try {
            return EntryManifest.report(plugin.config().outputDir(), 20);
        } catch (IOException error) {
            return java.util.List.of("读取条目清单失败：" + error.getMessage());
        }
    }

    public String engineLabel() {
        Merger current = merger;
        return current == null ? "未构建" : Mergers.label(current);
    }

    public PackPublisher(ResourcePackServicePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return plugin.config().providerId();
    }

    @Override
    public int version() {
        return snapshot.get().version();
    }

    @Override
    public String url() {
        return snapshot.get().url();
    }

    @Override
    public String sha1() {
        return snapshot.get().sha1();
    }

    @Override
    public int priority() {
        return plugin.config().providerPriority();
    }

    @Override
    public boolean required() {
        return plugin.config().required();
    }

    @Override
    public String prompt() {
        return plugin.config().prompt();
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public boolean building() {
        return running.get();
    }

    /** announce=true 时按 push.mode 通知在线玩家；watchdog 触发的重建走 false。 */
    public void rebuild(boolean announce) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                build(announce);
            } catch (Throwable error) {
                Snapshot previous = snapshot.get();
                snapshot.set(new Snapshot(previous.url(), previous.sha1(), previous.version(),
                        previous.bytes(), previous.builtAt(), previous.summary(),
                error.getMessage() == null ? error.toString() : error.getMessage()));
                if (error instanceof moe.nekomc.resourcepack.merge.MergeUnavailableException) {
                plugin.getLogger().severe("资源包无法构建（合并器不可用），保留上一版：" + error.getMessage());
            } else {
                plugin.getLogger().warning("资源包构建失败，保留上一版：" + error);
            }
            } finally {
                running.set(false);
            }
        });
    }

    private void build(boolean announce) throws IOException {
        RpsConfig config = plugin.config();
        List<ZipSource> sources = plugin.registry().ordered();
        Path outputDir = config.outputDir();
        Files.createDirectories(outputDir);
        Path staging = outputDir.resolve("merged.staging.zip");
        Path target = outputDir.resolve("merged.zip");

        MergeResult result = merger().merge(sources, config.packFormat(), config.description(), staging);

        String sha1 = Hashing.sha1Hex(staging);
        StateStore state = plugin.stateStore();
        boolean changed = !sha1.equalsIgnoreCase(state.lastSha1());
        if (changed) {
            state.commit(sha1, System.currentTimeMillis());
        }
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        EntryManifest.rotateAndWrite(outputDir, target);

        int version = state.generation();
        String url = withVersion(config.publicUrl(), version);
        snapshot.set(new Snapshot(url, sha1, version, Files.size(target), System.currentTimeMillis(), result.summary(), ""));
        plugin.bridge().refresh();

        if (url.isBlank()) {
            plugin.getLogger().warning("output.public-url 为空，合成包只落盘不下发");
            return;
        }
        if (!changed) {
            return;
        }
        plugin.getLogger().info("资源包已更新：世代 " + version + "，" + result.summary());
        if (announce) {
            afterChange(config);
        }
    }

    private void afterChange(RpsConfig config) {
        String mode = config.pushMode() == null ? "notify" : config.pushMode().toLowerCase(Locale.ROOT);
        if ("none".equals(mode)) {
            return;
        }
        if ("force".equals(mode)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.bridge().push(player.getUniqueId(), true);
            }
            return;
        }
        Component message = MiniMessage.miniMessage().deserialize(config.notifyMessage());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    static String withVersion(String base, int version) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String trimmed = base.trim();
        String separator = trimmed.contains("?") ? "&" : "?";
        return trimmed + separator + "v=" + Math.max(version, 1);
    }
}
