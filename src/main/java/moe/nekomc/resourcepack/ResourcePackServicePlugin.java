package moe.nekomc.resourcepack;

import java.nio.file.Path;
import java.util.List;
import moe.nekomc.resourcepack.api.ResourcePackService;
import moe.nekomc.resourcepack.bridge.DispatchBridge;
import moe.nekomc.resourcepack.command.RpsCommand;
import moe.nekomc.resourcepack.core.EngineConfig;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.core.RpsConfig;
import moe.nekomc.resourcepack.core.SourceRegistry;
import moe.nekomc.resourcepack.core.StateStore;
import moe.nekomc.resourcepack.pack.PackPublisher;
import moe.nekomc.resourcepack.pack.RegisteredSources;
import moe.nekomc.resourcepack.watch.SourceWatcher;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourcePackServicePlugin extends JavaPlugin {

    private volatile RpsConfig config;
    private volatile SourceRegistry registry = new SourceRegistry();
    private volatile StateStore stateStore;
    private volatile DispatchBridge bridge;
    private volatile PackPublisher publisher;
    private volatile SourceWatcher watcher;
    private volatile RegisteredSources registeredSources;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.bridge = new DispatchBridge(this);
        this.registeredSources = new RegisteredSources(this::registry, new RegisteredSources.Host() {
            @Override
            public void rebuild(boolean announce) {
                PackPublisher current = publisher;
                if (current != null) {
                    current.rebuild(announce);
                }
            }

            @Override
            public RegisteredSources.Status status() {
                PackPublisher current = publisher;
                if (current == null) {
                    return RegisteredSources.Status.empty();
                }
                PackPublisher.Snapshot snapshot = current.snapshot();
                return new RegisteredSources.Status(snapshot.version(), snapshot.url(), snapshot.sha1());
            }

            @Override
            public int push(java.util.UUID player, boolean force) {
                DispatchBridge current = bridge;
                return current == null ? 0 : current.push(player, force);
            }
        });
        PluginCommand command = getCommand("rps");
        if (command != null) {
            RpsCommand executor = new RpsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        reload();
        getServer().getServicesManager().register(
                ResourcePackService.class, registeredSources, this, ServicePriority.Normal);
        getLogger().info("已启用，来源 " + registry.ordered().size() + " 个");
    }

    @Override
    public void onDisable() {
        SourceWatcher current = watcher;
        if (current != null) {
            current.stop();
            watcher = null;
        }
        if (bridge != null) {
            bridge.detach();
        }
    }

    /** 重读 config.yml、重建注册表、重启侦测、立即重建一次。 */
    public void reload() {
        reloadConfig();
        Path serverRoot = getDataFolder().getParentFile().getParentFile().toPath();
        this.config = RpsConfig.load(getConfig(), serverRoot);
        this.engineConfig = EngineConfig.load(getConfig(), getDataFolder().toPath());
        if (stateStore == null) {
            this.stateStore = new StateStore(getDataFolder().toPath().resolve("state.json"));
        }

        SourceRegistry fresh = new SourceRegistry();
        for (ZipSource source : config.sources()) {
            fresh.put(source);
        }
        this.registry = fresh;
        if (registeredSources != null) {
            int restored = registeredSources.reapply(fresh);
            if (restored > 0) {
                getLogger().info("已恢复 " + restored + " 个插件注册的资源包来源");
            }
        }

        SourceWatcher previous = watcher;
        if (previous != null) {
            previous.stop();
        }
        this.publisher = new PackPublisher(this);
        if (config.watchEnabled() && config.watchSeconds() > 0) {
            this.watcher = new SourceWatcher(this, config.watchSeconds(), this::watchedPaths, () -> publisher.rebuild(true));
            watcher.start();
        } else {
            this.watcher = null;
        }

        if (bridge != null && !bridge.attach(publisher)) {
            getLogger().warning("资源包不会下发：" + bridge.unavailableReason());
        }
        publisher.rebuild(true);
        getLogger().info("合并引擎：" + publisher.engineLabel());
    }

    private List<Path> watchedPaths() {
        return registry.ordered().stream().map(ZipSource::path).filter(java.util.Objects::nonNull).toList();
    }

    private EngineConfig engineConfig;

    public EngineConfig engineConfig() {
        return engineConfig;
    }

    public RpsConfig config() {
        return config;
    }

    public SourceRegistry registry() {
        return registry;
    }

    public StateStore stateStore() {
        return stateStore;
    }

    public DispatchBridge bridge() {
        return bridge;
    }

    public PackPublisher publisher() {
        return publisher;
    }

    /** 供其它插件通过 ServicesManager 取得的统一入口。 */
    public ResourcePackService resourcePackService() {
        return registeredSources;
    }
}
