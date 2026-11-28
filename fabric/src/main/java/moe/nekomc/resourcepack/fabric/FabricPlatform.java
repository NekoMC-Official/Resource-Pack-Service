package moe.nekomc.resourcepack.fabric;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.command.CommandHost;
import moe.nekomc.resourcepack.config.CommunityBundle;
import moe.nekomc.resourcepack.config.RpsConfig;
import moe.nekomc.resourcepack.core.EngineConfig;
import moe.nekomc.resourcepack.core.SourceRegistry;
import moe.nekomc.resourcepack.core.StateStore;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.delivery.PackDelivery;
import moe.nekomc.resourcepack.delivery.PackHttpServer;
import moe.nekomc.resourcepack.pack.CommunityUpdater;
import moe.nekomc.resourcepack.pack.PackHost;
import moe.nekomc.resourcepack.pack.PackPublisher;
import moe.nekomc.resourcepack.pack.RemoteUpdateResult;
import moe.nekomc.resourcepack.pack.SelectionContext;
import moe.nekomc.resourcepack.platform.PlayerRef;
import moe.nekomc.resourcepack.platform.Scheduler;
import moe.nekomc.resourcepack.watch.SourceWatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.yaml.snakeyaml.Yaml;

/**
 * Fabric 侧的宿主：把内核需要的端口全部落到 MinecraftServer 上。
 *
 * <p>生命周期只有三步：{@link #attach} 绑定服务端、{@link #reload} 按配置重建一遍内核、
 * {@link #tick} 每个 tick 推进自己的调度器。与 Paper 的 reload 顺序保持一致，
 * 两边行为差异要能在这里逐行对上。</p>
 */
public final class FabricPlatform implements PackHost, CommandHost {

    private static final String DEFAULT_REJECT =
        "<red>本服资源包为必装项。请重新进入并在提示中点“继续”下载。";

    private final Logger logger;
    private final Path dataFolder;
    private final Path serverRoot;
    private final TickScheduler scheduler;

    private volatile MinecraftServer server;
    private volatile RpsConfig config;
    private volatile EngineConfig engineConfig;
    private volatile StateStore stateStore;
    private volatile SourceRegistry registry = new SourceRegistry();
    private volatile PackPublisher publisher;
    private volatile FabricPackDelivery delivery;
    private volatile PackHttpServer http;
    private volatile SourceWatcher watcher;
    private volatile CommunityUpdater communityUpdater;
    private volatile Scheduler.Cancellable remoteTask;
    private volatile String communityRemoteMessage = "";
    private volatile long communityRemoteLastCheck;

    public FabricPlatform(Logger logger, Path dataFolder, Path serverRoot) {
        this.logger = logger;
        this.scheduler = new TickScheduler(logger);
        this.dataFolder = dataFolder.toAbsolutePath().normalize();
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.stateStore = new StateStore(this.dataFolder.resolve("state.json"));
    }

    /** 服务端启动完成：拿到 server 实例并做第一次装配。 */
    public void attach(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        this.scheduler.attach(minecraftServer);
        reload();
    }

    /** 每个 tick 推一次调度器。 */
    public void tick() {
        scheduler.tick();
    }

    public MinecraftServer server() {
        return server;
    }

    // ---------------------------------------------------------------- PackHost

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public RpsConfig config() {
        return config;
    }

    @Override
    public SourceRegistry registry() {
        return registry;
    }

    @Override
    public StateStore stateStore() {
        return stateStore;
    }

    @Override
    public PackDelivery delivery() {
        return delivery;
    }

    @Override
    public EngineConfig engineConfig() {
        return engineConfig;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public List<PlayerRef> onlinePlayers() {
        MinecraftServer current = server;
        if (current == null) {
            return List.of();
        }
        List<PlayerRef> players = new ArrayList<>();
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            players.add(new FabricPlayerRef(player));
        }
        return players;
    }

    // ------------------------------------------------------------- CommandHost

    @Override
    public Path serverRoot() {
        return serverRoot;
    }

    @Override
    public PackPublisher publisher() {
        return publisher;
    }

    @Override
    public List<Path> conflictScanRoots() {
        // mod 的配置都在 config/ 下（TOML 居多），扫描根就是它自己。
        return List.of(FabricLoader.getInstance().getConfigDir());
    }

    @Override
    public void reload() {
        try {
            reloadStaged();
        } catch (RuntimeException error) {
            if (this.config == null) {
                // 首次装配就失败：没有"上一份"可用配置，服务保持未启用，别让 /rps 半死不活。
                logger.severe("[ResourcePackService] 首次装配失败，服务未启用；修正 config.yml 后执行 /rps reload：" + error);
                return;
            }
            // 能走到这里说明失败发生在切换到新配置之前，旧配置与旧下发仍然生效。
            logger.severe("[ResourcePackService] 重载失败，继续使用上一份可用配置：" + error);
            if (this.publisher == null) {
                this.publisher = new PackPublisher(this);
            }
            try {
                startWatcher();
            } catch (RuntimeException watcherError) {
                logger.warning("[ResourcePackService] 重载失败后恢复来源侦测也失败：" + watcherError);
            }
        }
    }

    private void reloadStaged() {
        stopWatcher();
        ensureConfigFile();
        ConfigSourceHolder holder = readConfig();
        // 先把新配置全部读完再落字段：任何一步抛错都不会污染正在生效的旧配置。
        RpsConfig nextConfig = RpsConfig.load(holder.source(), serverRoot, dataFolder.resolve("community"));
        EngineConfig nextEngine = EngineConfig.load(holder.source(), dataFolder);
        StateStore nextState = new StateStore(dataFolder.resolve("state.json"));
        SourceRegistry nextRegistry = new SourceRegistry();
        for (ZipSource source : nextConfig.sources()) {
            nextRegistry.put(source);
        }
        this.config = nextConfig;
        this.engineConfig = nextEngine;
        this.stateStore = nextState;
        this.registry = nextRegistry;

        PackPublisher previous = this.publisher;
        this.publisher = null;
        if (previous != null) {
            previous.supersede();
        }
        stopDelivery();
        stopRemote();

        PackPublisher fresh = new PackPublisher(this);
        this.publisher = fresh;
        // 配置已生效，后面的每一步都不该把整次 reload 判成失败：单个通道起不来只降级，
        // 否则外层 catch 会打出"仍用旧配置"这种与事实相反的日志。
        try {
            startDelivery();
        } catch (RuntimeException error) {
            logger.warning("[ResourcePackService] 下发通道启动失败，资源包暂时无法推送：" + error);
        }
        try {
            refreshCommunity(false);
        } catch (RuntimeException error) {
            logger.warning("[ResourcePackService] 社区包刷新失败：" + error.getMessage());
        }
        try {
            fresh.rebuild(true);
        } catch (RuntimeException error) {
            logger.warning("[ResourcePackService] 首次合成排程失败：" + error);
        }
        try {
            startRemote();
        } catch (RuntimeException error) {
            logger.warning("[ResourcePackService] 社区远端更新启动失败：" + error);
        }
        try {
            startWatcher();
        } catch (RuntimeException error) {
            logger.warning("[ResourcePackService] 来源侦测启动失败：" + error);
        }
    }

    private record ConfigSourceHolder(FabricConfigSource source) {
    }

    private void ensureConfigFile() {
        Path file = dataFolder.resolve("config.yml");
        if (Files.isRegularFile(file)) {
            return;
        }
        try {
            Files.createDirectories(dataFolder);
            try (var stream = FabricPlatform.class.getResourceAsStream("/config.yml")) {
                if (stream == null) {
                    logger.warning("[ResourcePackService] 缺少内置 config.yml，按默认值运行");
                    return;
                }
                Files.copy(stream, file);
            }
        } catch (IOException error) {
            logger.warning("[ResourcePackService] 写入默认 config.yml 失败：" + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ConfigSourceHolder readConfig() {
        Path file = dataFolder.resolve("config.yml");
        if (!Files.isRegularFile(file)) {
            return new ConfigSourceHolder(new FabricConfigSource(java.util.Map.of()));
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (loaded instanceof java.util.Map<?, ?> map) {
                return new ConfigSourceHolder(new FabricConfigSource(map));
            }
            return new ConfigSourceHolder(new FabricConfigSource(java.util.Map.of()));
        } catch (Exception error) {
            // 解析失败不能静默回默认值：一次缩进错误就会把整份服务器配置悄悄清空。
            throw new IllegalStateException("config.yml 解析失败：" + error.getMessage(), error);
        }
    }

    private void startDelivery() {
        RpsConfig snapshot = config;
        PackHttpServer next = null;
        if (snapshot.deliveryHttpEnabled()) {
            try {
                next = new PackHttpServer(
                    snapshot.outputDir().resolve("merged.zip"),
                    snapshot.deliveryHttpBind(),
                    snapshot.deliveryHttpPort(),
                    snapshot.deliveryHttpPath(),
                    snapshot.deliveryHttpMaxConcurrent(),
                    snapshot.deliveryHttpEgressKbps() * 1024L);
                next.start();
            } catch (Exception error) {
                // 端口越界抛的是 IllegalArgumentException，不只 IOException。
                logger.warning("[ResourcePackService] HTTP 出口启动失败：" + error.getMessage());
                if (next != null) {
                    next.close();
                }
                next = null;
            }
        }
        this.http = next;
        if (next == null && snapshot.deliveryPublicUrl().isBlank()) {
            logger.severe("[ResourcePackService] 没有可用的下载地址：请开启 delivery.http.enabled 或填 delivery.http.public-url");
        }
        this.delivery = new FabricPackDelivery(
            this,
            next,
            snapshot.deliveryJoinDelayTicks(),
            snapshot.deliveryStatusFeedback(),
            snapshot.required(),
            snapshot.announceRequired(),
            snapshot.prompt(),
            snapshot.rejectMessage());
    }

    private void stopDelivery() {
        FabricPackDelivery current = this.delivery;
        this.delivery = null;
        if (current != null) {
            current.close();
        }
        PackHttpServer currentHttp = this.http;
        this.http = null;
        if (currentHttp != null) {
            currentHttp.close();
        }
    }

    private void startWatcher() {
        RpsConfig snapshot = config;
        if (snapshot == null) {
            // 装配失败时没有配置可读，别在这里补一个 NPE 让调用方再吞一次。
            return;
        }
        if (!snapshot.watchEnabled()) {
            return;
        }
        this.watcher = new SourceWatcher(this, snapshot.watchSeconds(), this::watchedPaths, () -> {
            PackPublisher current = publisher;
            if (current != null) {
                current.rebuild(true);
            }
        });
        this.watcher.start();
    }

    private void stopWatcher() {
        SourceWatcher current = this.watcher;
        this.watcher = null;
        if (current != null) {
            current.stop();
        }
    }

    private List<Path> watchedPaths() {
        List<Path> paths = new ArrayList<>();
        SourceRegistry current = registry;
        if (current != null) {
            for (var source : current.ordered()) {
                if (source != null && source.file() != null) {
                    paths.add(source.file());
                }
            }
        }
        PackPublisher currentPublisher = publisher;
        if (currentPublisher != null) {
            for (ZipSource source : currentPublisher.communitySources()) {
                paths.add(source.file());
            }
        }
        return paths;
    }

    // ------------------------------------------------------------- 社区资源包

    public void refreshCommunity(boolean rebuild) {
        PackPublisher current = publisher;
        RpsConfig snapshot = config;
        if (current == null || snapshot == null) {
            return;
        }
        if (!snapshot.communityEnabled()) {
            boolean changed = current.setCommunitySources(List.of());
            if (rebuild && changed) {
                current.rebuild(false);
            }
            return;
        }
        SelectionContext context = selectionContext(snapshot);
        List<ZipSource> sources;
        try {
            sources = new CommunityBundle(
                snapshot.communityDir(),
                snapshot.communityPriority(),
                context.installed(),
                context.minecraftVersion(),
                context.packFormat(),
                context.pluginVersion(),
                message -> logger.info(message)).load();
        } catch (Throwable error) {
            logger.warning("[ResourcePackService] 社区资源包读取失败，本次按空集合处理：" + error.getMessage());
            sources = List.of();
        }
        boolean changed = current.setCommunitySources(sources);
        if (rebuild && changed) {
            current.rebuild(false);
        }
    }

    private SelectionContext selectionContext(RpsConfig snapshot) {
        String minecraft = snapshot.communityMinecraft();
        if (minecraft == null || minecraft.isBlank()) {
            MinecraftServer current = server;
            minecraft = current == null ? "26.3" : current.getServerVersion();
        }
        return new SelectionContext(
            this::isModInstalled,
            this::modVersion,
            minecraft,
            snapshot.communityPackFormat());
    }

    private boolean isModInstalled(String modId) {
        if (modId == null || modId.isBlank()) {
            return false;
        }
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    private String modVersion(String modId) {
        if (modId == null || modId.isBlank()) {
            return null;
        }
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse(null);
    }

    private void startRemote() {
        stopRemote();
        RpsConfig snapshot = config;
        if (snapshot == null || !snapshot.communityRemoteEnabled()) {
            return;
        }
        java.net.URI index;
        try {
            index = java.net.URI.create(snapshot.communityIndexUrl());
        } catch (IllegalArgumentException error) {
            communityRemoteMessage = "索引地址无效：" + error.getMessage();
            logger.severe("[ResourcePackService] " + communityRemoteMessage);
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
            message -> logger.info(message));
        this.communityUpdater = updater;
        long periodMillis = Math.max(5L, snapshot.communityIntervalMinutes()) * 60_000L;
        this.remoteTask = scheduler.runAsyncTimer(30_000L, periodMillis, () -> runRemoteUpdate(false));
        this.communityRemoteMessage = "等待首次检查";
    }

    private void stopRemote() {
        Scheduler.Cancellable task = this.remoteTask;
        this.remoteTask = null;
        if (task != null) {
            task.cancel();
        }
        CommunityUpdater updater = this.communityUpdater;
        this.communityUpdater = null;
        if (updater != null) {
            try {
                updater.close();
            } catch (Throwable error) {
                logger.warning("[ResourcePackService] 关闭社区更新器时出错：" + error.getMessage());
            }
        }
    }

    private void runRemoteUpdate(boolean force) {
        CommunityUpdater updater = this.communityUpdater;
        if (updater == null) {
            return;
        }
        try {
            RemoteUpdateResult result = updater.update(force);
            if (this.communityUpdater != updater) {
                return;
            }
            this.communityRemoteMessage = result.getMessage();
            this.communityRemoteLastCheck = System.currentTimeMillis();
            if (result.getChanged()) {
                runOnServer(() -> {
                    if (this.communityUpdater == updater) {
                        refreshCommunity(true);
                    }
                });
            }
        } catch (Throwable error) {
            if (this.communityUpdater != updater) {
                return;
            }
            this.communityRemoteMessage = "更新失败：" + error.getMessage();
            this.communityRemoteLastCheck = System.currentTimeMillis();
            logger.warning("[ResourcePackService] " + communityRemoteMessage);
        }
    }

    @Override
    public boolean updateCommunity(boolean force) {
        if (communityUpdater == null) {
            return false;
        }
        scheduler.runAsyncNow(() -> runRemoteUpdate(force));
        return true;
    }

    @Override
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

    // ------------------------------------------------------------- 主线程工具

    /** 切回服务端线程执行；服务端还没起来时直接跑。 */
    public void runOnServer(Runnable task) {
        MinecraftServer current = server;
        if (current == null) {
            task.run();
            return;
        }
        current.executeIfPossible(task);
    }

    /** 若干 tick 后回主线程执行。 */
    public void runOnServerLater(Runnable task, int ticks) {
        scheduler.runAsyncTimer(Math.max(1, ticks) * 50L, 0L, () -> runOnServer(task));
    }

    public ServerPlayer player(UUID id) {
        MinecraftServer current = server;
        return current == null ? null : current.getPlayerList().getPlayer(id);
    }

    /** 状态变化：目前只写日志，页面与命令读 {@link FabricPackDelivery}。 */
    public void statusChanged(UUID playerId, String playerName, PackStatus status, String sha1) {
        logger.fine("[ResourcePackService] " + playerName + " 状态 " + status + " sha1=" + sha1);
    }

    /** 玩家进服：交给下发器按 join-delay 推一次。 */
    public void onJoin(ServerPlayer player) {
        FabricPackDelivery current = delivery;
        if (current != null) {
            current.onJoin(player);
        }
    }

    /** 玩家离线：清本地状态。 */
    public void onQuit(UUID playerId) {
        FabricPackDelivery current = delivery;
        if (current != null) {
            current.onQuit(playerId);
        }
    }

    /** 客户端回执（由 mixin 转进来）。 */
    public void onResponse(ServerPlayer player, net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action action) {
        FabricPackDelivery current = delivery;
        if (current != null) {
            current.onResponse(player, action);
        }
    }

    /** 关服：停调度器、停 HTTP、停看门狗。 */
    public void shutdown() {
        stopWatcher();
        PackPublisher current = publisher;
        this.publisher = null;
        if (current != null) {
            current.supersede();
        }
        stopDelivery();
        stopRemote();
        scheduler.shutdown();
    }
}
