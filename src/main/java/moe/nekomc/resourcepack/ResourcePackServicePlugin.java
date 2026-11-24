package moe.nekomc.resourcepack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import moe.nekomc.resourcepack.api.PackSource;
import moe.nekomc.resourcepack.api.ResourcePackService;
import moe.nekomc.resourcepack.command.RpsCommand;
import moe.nekomc.resourcepack.config.RpsConfig;
import moe.nekomc.resourcepack.core.EngineConfig;
import moe.nekomc.resourcepack.core.SourceRegistry;
import moe.nekomc.resourcepack.core.StateStore;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.delivery.PackHttpServer;
import moe.nekomc.resourcepack.delivery.PackPusher;
import moe.nekomc.resourcepack.pack.Host;
import moe.nekomc.resourcepack.pack.HostStatus;
import moe.nekomc.resourcepack.pack.PackPublisher;
import moe.nekomc.resourcepack.pack.PackSnapshot;
import moe.nekomc.resourcepack.pack.RegisteredSources;
import moe.nekomc.resourcepack.watch.SourceWatcher;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件入口：装配配置、注册表、发布器、下载出口与推送器。
 *
 * <p>启动顺序是有讲究的：先有配置和状态，才有发布器；发布器要能在第一次构建时
 * 拿到下发器拼地址，所以下发器必须在 {@code publisher.rebuild(true)} 之前就位。
 * 文件监听放在最后，避免开服瞬间被自己的落盘触发一次。</p>
 */
public final class ResourcePackServicePlugin extends JavaPlugin {

    private RpsConfig config;
    private EngineConfig engineConfig;
    private SourceRegistry registry;
    private StateStore stateStore;
    private PackPublisher publisher;
    private PackPusher delivery;
    private SourceWatcher watcher;
    private RegisteredSources registeredSources;

    @Override
    public void onEnable() {
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
            RpsCommand executor = new RpsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getServicesManager().register(ResourcePackService.class, registeredSources, this, ServicePriority.Normal);
        getLogger().info("资源包服务已启动");
    }

    @Override
    public void onDisable() {
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
        config = RpsConfig.load(getConfig(), serverRoot);
        engineConfig = EngineConfig.load(getConfig(), dataFolder);
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
        stopDelivery();
        publisher = new PackPublisher(this);
        startDelivery();
        publisher.rebuild(true);
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
                config.prompt());
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
        if (current == null) {
            return paths;
        }
        for (PackSource source : current.ordered()) {
            Path file = source.file();
            if (file != null && !paths.contains(file)) {
                paths.add(file);
            }
        }
        return paths;
    }

    private SourceRegistry currentRegistry() {
        return registry;
    }

    public RpsConfig config() {
        return config;
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
}
