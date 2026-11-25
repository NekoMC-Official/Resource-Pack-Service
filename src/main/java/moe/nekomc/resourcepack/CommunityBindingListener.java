package moe.nekomc.resourcepack;

import java.io.File;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * 插件启停后重新计算社区包绑定。
 *
 * <p>社区包声明了它服务哪个插件；插件被卸下后，它的 official 包以及依赖它的
 * addon、patch、integration 都必须从来源里消失。启用同样要重新计算，否则新装的
 * 插件要等到下一次 /rps reload 才生效。</p>
 */
public final class CommunityBindingListener implements Listener {

    private final ResourcePackServicePlugin plugin;
    private File manifest;

    public CommunityBindingListener(ResourcePackServicePlugin plugin) {
        this.plugin = plugin;
        this.manifest = new File(plugin.communityDirectory().toFile(), "manifest.json");
    }

    /** 目录可能被 /rps reload 改过，重新定位清单。 */
    public void refresh() {
        this.manifest = new File(plugin.communityDirectory().toFile(), "manifest.json");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == plugin) {
            return;
        }
        scheduleRefresh(event.getPlugin().getName(), false);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            return;
        }
        scheduleRefresh(event.getPlugin().getName(), true);
    }

    private void scheduleRefresh(String changedPlugin, boolean removed) {
        if (manifest == null || !manifest.isFile()) {
            return;
        }
        plugin.refreshCommunityOffThread(changedPlugin, removed);
    }
}
