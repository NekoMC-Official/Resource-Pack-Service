package moe.nekomc.resourcepack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.stream.Stream;
import moe.nekomc.resourcepack.api.BuildResult;
import moe.nekomc.resourcepack.api.PackSource;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.api.ResourcePackService;
import moe.nekomc.resourcepack.command.CommandHost;
import moe.nekomc.resourcepack.command.RpsCommand;
import moe.nekomc.resourcepack.platform.BukkitCommandBridge;
import moe.nekomc.resourcepack.config.CommunityBundle;
import moe.nekomc.resourcepack.config.RpsConfig;
import moe.nekomc.resourcepack.core.EngineConfig;
import moe.nekomc.resourcepack.core.SourceRegistry;
import moe.nekomc.resourcepack.core.StateStore;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.delivery.PackHttpServer;
import moe.nekomc.resourcepack.delivery.PackPusher;
import moe.nekomc.resourcepack.pack.Host;
import moe.nekomc.resourcepack.pack.HostStatus;
import moe.nekomc.resourcepack.pack.PackHost;
import moe.nekomc.resourcepack.pack.PackPublisher;
import moe.nekomc.resourcepack.pack.PackSnapshot;
import moe.nekomc.resourcepack.pack.CommunityUpdater;
import moe.nekomc.resourcepack.pack.RemoteUpdateResult;
import moe.nekomc.resourcepack.pack.SelectionContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import moe.nekomc.resourcepack.pack.RegisteredSources;
import moe.nekomc.resourcepack.platform.BukkitConfigSource;
import moe.nekomc.resourcepack.platform.BukkitPlayerRef;
import moe.nekomc.resourcepack.platform.FoliaScheduler;
import moe.nekomc.resourcepack.platform.PlayerRef;
import moe.nekomc.resourcepack.platform.Scheduler;
import moe.nekomc.resourcepack.watch.SourceWatcher;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件入口：装配配置、注册表、发布器、下载出口与推送器。
 *
 * <p>启动顺序是有讲究的：先有配置和状态，才有发布器；发布器要能在第一次构建时
 * 拿到下发器拼地址，所以下发器必须在 {@code publisher.rebuild(true)} 之前就位。
 * 文件监听放在最后，避免开服瞬间被自己的落盘触发一次。</p>
 */
public final class ResourcePackServicePlugin extends JavaPlugin implements PackHost, CommandHost {

    private volatile RpsConfig config;
    private EngineConfig engineConfig;
    private SourceRegistry registry;
    private StateStore stateStore;
    private volatile PackPublisher publisher;
    private PackPusher delivery;
    private SourceWatcher watcher;
    private RegisteredSources registeredSources;
    private CommunityBindingListener communityListener;
    private volatile CommunityUpdater communityUpdater;
    private volatile ScheduledTask communityTask;
    private volatile String communityRemoteMessage = "";
    private volatile long communityRemoteLastCheck;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        registeredSources = new RegisteredSources(this::currentRegistry, new Host() {
            @Override
            public void rebuild(boolean announce) {
                PackPublisher current = publisher;
                if (current != null) {
                    current.rebuild(announce);
                }
            }

            @Override
            public HostStatus status() {
                PackPublisher current = publisher;
                if (current == null) {
                    return HostStatus.empty();
                }
                PackSnapshot snapshot = current.snapshot();
                return new HostStatus(snapshot.version(), snapshot.url(), snapshot.sha1());
            }

            @Override
            public CompletableFuture<BuildResult> rebuildAsync(boolean announce) {
                PackPublisher current = publisher;
                if (current == null) {
                    return CompletableFuture.completedFuture(BuildResult.unavailable("发布器尚未就绪"));
                }
                return current.rebuildAsync(announce);
            }

            @Override
            public BuildResult snapshot() {
                PackPublisher current = publisher;
                return current == null ? BuildResult.unavailable("发布器尚未就绪") : current.buildResult();
            }

            @Override
            public PackStatus status(UUID player) {
                PackPusher current = delivery;
                return current == null ? PackStatus.UNKNOWN : current.status(player);
            }

            @Override
            public String hashOf(UUID player) {
                PackPusher current = delivery;
                return current == null ? null : current.hashOf(player);
            }

            @Override
            public String lastError() {
                PackPublisher current = publisher;
                return current == null ? null : current.buildResult().error();
            }

            @Override
            public int push(UUID player, boolean force) {
                PackPusher current = delivery;
                return current == null ? 0 : current.push(player, force);
            }
        });
        reload();
        PluginCommand command = getCommand("rps");
        if (command == null) {
            getLogger().warning("plugin.yml 里没有 rps 命令，管理命令不可用");
        } else {
            BukkitCommandBridge bridge = new BukkitCommandBridge(new RpsCommand(this));
            command.setExecutor(bridge);
            command.setTabCompleter(bridge);
        }
        getServer().getServicesManager().register(ResourcePackService.class, registeredSources, this, ServicePriority.Normal);
        communityListener = new CommunityBindingListener(this);
        getServer().getPluginManager().registerEvents(communityListener, this);
        getLogger().info("资源包服务已启动");
    }

    @Override
    public void onDisable() {
        // 先作废当前发布器：在途构建不会在关闭过程中继续写输出目录。
        PackPublisher current = publisher;
        if (current != null) {
            current.supersede();
        }
        if (communityListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(communityListener);
            communityListener = null;
        }
        stopRemote();
        stopWatcher();
        stopDelivery();
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("资源包服务已停止");
    }

    /** 重载配置并重建整条链路；命令与文件监听都走这里。 */
    public synchronized void reload() {
        reloadConfig();
        Path serverRoot = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path dataFolder = getDataFolder().toPath();
        config = RpsConfig.load(new BukkitConfigSource(getConfig()), serverRoot, null);
        engineConfig = EngineConfig.load(new BukkitConfigSource(getConfig()), dataFolder);
        stateStore = new StateStore(dataFolder.resolve("state.json"));

        SourceRegistry fresh = new SourceRegistry();
        for (ZipSource source : config.sources()) {
            fresh.put(source);
        }
        registry = fresh;
        if (registeredSources != null) {
            int restored = registeredSources.reapply(fresh);
            if (restored > 0) {
                getLogger().info("重载后重投 " + restored + " 个已注册来源");
            }
        }

        stopWatcher();
        PackPublisher previous = publisher;
        if (previous != null) {
            previous.supersede();
        }
        stopDelivery();
        publisher = new PackPublisher(this);
        startDelivery();
        // 先把社区来源灌进新发布器再做第一次构建，避免旧来源先构建出一版。
        try {
            refreshCommunity(false);
        } catch (Throwable error) {
            getLogger().warning("社区资源包刷新失败，按空集合继续：" + error.getMessage());
        }
        publisher.rebuild(true);
        startRemote();
        startWatcher();
    }

    private void startDelivery() {
        PackHttpServer server = null;
        if (config.deliveryHttpEnabled()) {
            PackHttpServer candidate = new PackHttpServer(
                    config.outputDir().resolve("merged.zip"),
                    config.deliveryHttpBind(),
                    config.deliveryHttpPort(),
                    config.deliveryHttpPath());
            try {
                candidate.start();
                server = candidate;
                getLogger().info("下载出口已启动：" + candidate.bind() + ":" + candidate.port() + candidate.urlPath());
            } catch (IOException exception) {
                getLogger().severe("下载出口启动失败（" + config.deliveryHttpBind() + ":" + config.deliveryHttpPort()
                        + "）：" + exception.getMessage());
            }
        }
        if (server == null && config.deliveryPublicUrl().isBlank()) {
            getLogger().severe("self 模式需要 HTTP 出口或手工设置 delivery.http.public-url");
        }
        PackPusher pusher = new PackPusher(
                this, server,
                config.deliveryJoinDelayTicks(),
                config.deliveryStatusFeedback(),
                config.required(),
                config.announceRequired(),
                config.prompt(),
                config.rejectMessage());
        delivery = pusher;
        getServer().getPluginManager().registerEvents(pusher, this);
    }

    private void stopDelivery() {
        PackPusher current = delivery;
        delivery = null;
        if (current != null) {
            current.close();
        }
    }

    private void startWatcher() {
        if (!config.watchEnabled()) {
            return;
        }
        SourceWatcher created = new SourceWatcher(this, config.watchSeconds(), this::watchedPaths, () -> {
            PackPublisher current = publisher;
            if (current == null) {
                return;
            }
            getLogger().info("检测到来源文件变化，开始重新合成");
            current.rebuild(true);
        });
        created.start();
        watcher = created;
    }

    private void stopWatcher() {
        SourceWatcher current = watcher;
        watcher = null;
        if (current != null) {
            current.stop();
        }
    }

    private List<Path> watchedPaths() {
        List<Path> paths = new ArrayList<>();
        SourceRegistry current = registry;
        if (current != null) {
            for (PackSource source : current.ordered()) {
                Path file = source.file();
                if (file != null && !paths.contains(file)) {
                    paths.add(file);
                }
            }
        }
        PackPublisher currentPublisher = publisher;
        if (currentPublisher != null) {
            for (ZipSource source : currentPublisher.communitySources()) {
                Path file = source.file();
                if (file != null && !paths.contains(file)) {
                    paths.add(file);
                }
            }
        }
        return paths;
    }

    /** 重新读取社区包目录并按已安装插件过滤；来源变化后立即重建一次。 */
    public void refreshCommunity() {
        refreshCommunity(true);
    }

    /**
     * 重新读取社区包目录并按已安装插件过滤。
     *
     * @param rebuild 设置完来源后是否立刻重建；reload 传 false，等配置全部就位后统一构建
     */
    public void refreshCommunity(boolean rebuild) {
        PackPublisher current = publisher;
        RpsConfig snapshot = config;
        if (current == null || snapshot == null) {
            return;
        }
        if (!snapshot.communityEnabled()) {
            // 社区关闭时清空来源，避免上一轮的社区包继续参与合成。
            boolean changed = current.setCommunitySources(List.of());
            if (communityListener != null) {
                communityListener.refresh();
            }
            if (rebuild && changed) {
                current.rebuild(false);
            }
            return;
        }
        SelectionContext context = selectionContext(snapshot);
        CommunityBundle bundle = new CommunityBundle(
                snapshot.communityDir(),
                snapshot.communityPriority(),
                context.installed(),
                context.minecraftVersion(),
                context.packFormat(),
                context.pluginVersion(),
                message -> getLogger().info(message));
        List<ZipSource> sources;
        try {
            sources = bundle.load();
        } catch (Throwable error) {
            getLogger().warning("社区资源包读取失败，本次按空集合处理：" + error.getMessage());
            sources = List.of();
        }
        boolean changed = current.setCommunitySources(sources);
        if (communityListener != null) {
            communityListener.refresh();
        }
        if (rebuild && changed) {
            current.rebuild(false);
        }
    }

    /**
     * 插件启停后刷新社区包绑定：manifest 解析放到异步线程，应用来源回到 global region 线程。
     */
    public void refreshCommunityOffThread() {
        refreshCommunityOffThread(null, false);
    }

    /** 传入刚启停的插件，避免事件时刻 isEnabled 尚未翻转导致绑定判断滞后一轮。 */
    public void refreshCommunityOffThread(String changedPlugin, boolean removed) {
        PackPublisher current = publisher;
        RpsConfig snapshot = config;
        if (current == null || snapshot == null) {
            return;
        }
        if (!snapshot.communityEnabled()) {
            getServer().getGlobalRegionScheduler().run(this, task -> refreshCommunity(false));
            return;
        }
        SelectionContext context = snapshotContext(snapshot, changedPlugin, removed);
        getServer().getAsyncScheduler().runNow(this, task -> {
            List<ZipSource> sources;
            try {
                sources = new CommunityBundle(
                    snapshot.communityDir(),
                    snapshot.communityPriority(),
                    context.installed(),
                    context.minecraftVersion(),
                    context.packFormat(),
                    context.pluginVersion(),
                    message -> getLogger().info(message)
                ).load();
            } catch (Throwable error) {
                getLogger().warning("社区资源包刷新失败：" + error.getMessage());
                return;
            }
            getServer().getGlobalRegionScheduler().run(this, applied -> {
                if (publisher != current || config != snapshot) {
                    return;
                }
                boolean changed = current.setCommunitySources(sources);
                if (communityListener != null) {
                    communityListener.refresh();
                }
                if (changed) {
                    current.rebuild(false);
                }
            });
        });
    }

    /** 在 region 线程快照已启用插件与版本，异步筛选时不再访问 PluginManager。 */
    private SelectionContext snapshotContext(RpsConfig snapshot) {
        return snapshotContext(snapshot, null, false);
    }

    private SelectionContext snapshotContext(RpsConfig snapshot, String changedPlugin, boolean removed) {
        java.util.Map<String, String> versions = new java.util.HashMap<>();
        for (Plugin plugin : getServer().getPluginManager().getPlugins()) {
            if (plugin.isEnabled()) {
                versions.put(plugin.getName(), plugin.getPluginMeta().getVersion());
            }
        }
        if (changedPlugin != null && !changedPlugin.isBlank()) {
            if (removed) {
                versions.remove(changedPlugin);
            } else {
                Plugin changed = getServer().getPluginManager().getPlugin(changedPlugin);
                if (changed != null && changed.isEnabled()) {
                    versions.put(changed.getName(), changed.getPluginMeta().getVersion());
                }
            }
        }
        String minecraft = snapshot.communityMinecraft().isBlank()
            ? org.bukkit.Bukkit.getMinecraftVersion()
            : snapshot.communityMinecraft();
        return new SelectionContext(versions::containsKey, versions::get, minecraft, snapshot.communityPackFormat());
    }

    /** 社区包绑定判断只看插件是否真的安装并启用。 */
    public boolean isPluginInstalled(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return false;
        }
        org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    public Path communityDirectory() {
        RpsConfig snapshot = config;
        return snapshot == null ? getDataFolder().toPath().resolve("community") : snapshot.communityDir();
    }

    /** 组装选择上下文：已安装判断、插件版本、Minecraft 版本与 pack_format。 */
    private SelectionContext selectionContext(RpsConfig snapshot) {
        String minecraft = snapshot.communityMinecraft().isBlank()
                ? org.bukkit.Bukkit.getMinecraftVersion()
                : snapshot.communityMinecraft();
        return new SelectionContext(
                this::isPluginInstalled,
                name -> {
                    Plugin plugin = getServer().getPluginManager().getPlugin(name);
                    return plugin == null ? null : plugin.getPluginMeta().getVersion();
                },
                minecraft,
                snapshot.communityPackFormat());
    }

    /** 启动远程索引轮询；默认关闭，配置打开才会发起网络请求。 */
    private void startRemote() {
        stopRemote();
        RpsConfig snapshot = config;
        if (snapshot == null || !snapshot.communityRemoteEnabled()) {
            return;
        }
        URI index;
        try {
            index = URI.create(snapshot.communityIndexUrl());
        } catch (IllegalArgumentException error) {
            communityRemoteMessage = "索引地址无效：" + error.getMessage();
            getLogger().severe(communityRemoteMessage);
            return;
        }
        CommunityUpdater updater = new CommunityUpdater(
                snapshot.communityDir(),
                index,
                snapshot.communityRequireHttps(),
                snapshot.communityTimeoutMs(),
                snapshot.communityMaxPackBytes(),
                snapshot.communityPinSha1(),
                selectionContext(snapshot),
                message -> getLogger().info(message));
        communityUpdater = updater;
        long seconds = Math.max(5L, snapshot.communityIntervalMinutes()) * 60L;
        communityTask = getServer().getAsyncScheduler().runAtFixedRate(
                this, task -> runRemoteUpdate(false), 30L, seconds, TimeUnit.SECONDS);
        communityRemoteMessage = "等待首次检查";
    }

    private void stopRemote() {
        ScheduledTask task = communityTask;
        communityTask = null;
        if (task != null) {
            task.cancel();
        }
        CommunityUpdater updater = communityUpdater;
        communityUpdater = null;
        if (updater != null) {
            try {
                updater.close();
            } catch (Throwable error) {
                getLogger().warning("关闭社区更新器时出错：" + error.getMessage());
            }
        }
    }

    /** 后台跑一次索引检查；force 为 true 时忽略本地 sha1 缓存。 */
    private void runRemoteUpdate(boolean force) {
        CommunityUpdater updater = communityUpdater;
        if (updater == null) {
            return;
        }
        try {
            RemoteUpdateResult result = updater.update(force);
            // 更新期间可能已 reload / 停用：旧任务的返回值不得覆盖新更新器的状态。
            if (communityUpdater != updater) {
                return;
            }
            communityRemoteMessage = result.getMessage();
            communityRemoteLastCheck = System.currentTimeMillis();
            if (result.getChanged()) {
                getServer().getGlobalRegionScheduler().run(this, task -> {
                    if (communityUpdater != updater) {
                        return;
                    }
                    refreshCommunity();
                });
            }
        } catch (Throwable error) {
            if (communityUpdater != updater) {
                return;
            }
            communityRemoteMessage = "更新失败：" + error.getMessage();
            communityRemoteLastCheck = System.currentTimeMillis();
            getLogger().warning(communityRemoteMessage);
        }
    }

    /** 命令入口：立即检查一次远程索引，返回是否已排程。 */
    public boolean updateCommunity(boolean force) {
        if (communityUpdater == null) {
            return false;
        }
        getServer().getAsyncScheduler().runNow(this, task -> runRemoteUpdate(force));
        return true;
    }

    /** 远程索引状态，给 /rps community 显示。 */
    public String communityRemoteStatus() {
        RpsConfig snapshot = config;
        if (snapshot == null || !snapshot.communityRemoteEnabled()) {
            return "未启用";
        }
        String base = "已启用 · 每 " + snapshot.communityIntervalMinutes() + " 分钟 · "
                + snapshot.communityIndexUrl();
        if (communityRemoteLastCheck > 0L) {
            String at = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(communityRemoteLastCheck));
            base += " · 上次检查 " + at;
        }
        if (communityRemoteMessage != null && !communityRemoteMessage.isBlank()) {
            base += " · " + communityRemoteMessage;
        }
        return base;
    }
    private SourceRegistry currentRegistry() {
        return registry;
    }

    @Override
    public Logger logger() {
        return getLogger();
    }

    @Override
    public Path dataFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public Path serverRoot() {
        return getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    @Override
    public List<Path> conflictScanRoots() {
        Path folder = getDataFolder().toPath().toAbsolutePath();
        Path plugins = folder.getParent();
        return plugins == null ? List.of() : List.of(plugins);
    }

    public RpsConfig config() {
        return config;
    }

    @Override
    public Scheduler scheduler() {
        return new FoliaScheduler(this);
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        List<PlayerRef> online = new ArrayList<>();
        for (Player player : getServer().getOnlinePlayers()) {
            online.add(new BukkitPlayerRef(player));
        }
        return online;
    }

    public EngineConfig engineConfig() {
        return engineConfig;
    }

    public SourceRegistry registry() {
        return registry;
    }

    public StateStore stateStore() {
        return stateStore;
    }

    public PackPublisher publisher() {
        return publisher;
    }

    public PackPusher delivery() {
        return delivery;
    }

    public RegisteredSources registeredSources() {
        return registeredSources;
    }

    /**
     * 早期版本的插件名带空格，Paper 26.x 会拒绝加载这种描述符。改名后数据目录会从
     * {@code plugins/Resource Pack Service} 变成 {@code plugins/ResourcePackService}；
     * 这里在首次启用时把旧目录内容搬过去，避免升级后配置与社区包凭空消失。
     */
    private void migrateLegacyDataFolder() {
        File current = getDataFolder();
        File parent = current.getParentFile();
        if (parent == null) {
            return;
        }
        File legacy = new File(parent, "Resource Pack Service");
        if (!legacy.isDirectory() || legacy.equals(current)) {
            return;
        }
        if (new File(current, "config.yml").isFile()) {
            return;
        }
        try {
            Files.createDirectories(current.toPath());
            moveChildren(legacy.toPath(), current.toPath());
            getLogger().info("已将旧数据目录迁移到 " + current.getName());
        } catch (IOException e) {
            getLogger().warning("迁移旧数据目录失败：" + e.getMessage());
        }
    }

    private static void moveChildren(Path from, Path to) throws IOException {
        List<Path> children;
        try (Stream<Path> stream = Files.list(from)) {
            children = stream.toList();
        }
        for (Path child : children) {
            Path target = to.resolve(child.getFileName().toString());
            if (Files.isDirectory(child)) {
                Files.createDirectories(target);
                moveChildren(child, target);
                try {
                    Files.deleteIfExists(child);
                } catch (IOException ignored) {
                    // 旧目录残留不影响新目录使用。
                }
                continue;
            }
            try {
                Files.move(child, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                Files.copy(child, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
