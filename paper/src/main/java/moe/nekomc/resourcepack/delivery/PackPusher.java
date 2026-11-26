package moe.nekomc.resourcepack.delivery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import moe.nekomc.resourcepack.ResourcePackServicePlugin;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.delivery.PackDelivery;
import moe.nekomc.resourcepack.api.PackStatusEvent;
import moe.nekomc.resourcepack.pack.PackSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * 本插件自己的下发通道：内置 HTTP 出口 + Folia 实体调度器推送 + 客户端回执处理。
 *
 * <p>这里只做“平台边界”的事：事件监听、玩家线程调度、setResourcePack 调用、断开与提示。
 * 地址从哪来、ETag 怎么算，都在 {@link PackHttpServer}；什么时候该推，在发布器。</p>
 */
public final class PackPusher implements Listener, AutoCloseable, PackDelivery {

    private static final String LOG_PREFIX = "[资源包] ";
    private static final String DEFAULT_REJECT = "<red>本服资源包为必装项。请重新进入并在提示中点“是”，"
            + "或在 选项 → 资源包 中把服务器资源包设为“已启用”。</red>";

    private final ResourcePackServicePlugin plugin;
    private final PackHttpServer server;
    private final int joinDelayTicks;
    private final boolean statusFeedback;
    private final boolean required;
    private final boolean announceRequired;
    private final String prompt;
    private final String rejectMessage;

    private final DeliveryStatusStore statuses = new DeliveryStatusStore();
    private final ConcurrentHashMap<UUID, String> pushedSha1 = new ConcurrentHashMap<>();

    private volatile String lastUrl = "";
    private volatile String lastSha1 = "";

    public PackPusher(
            ResourcePackServicePlugin plugin,
            PackHttpServer server,
            int joinDelayTicks,
            boolean statusFeedback,
            boolean required,
            boolean announceRequired,
            String prompt,
            String rejectMessage) {
        this.plugin = plugin;
        this.server = server;
        this.joinDelayTicks = Math.max(0, joinDelayTicks);
        this.statusFeedback = statusFeedback;
        this.required = required;
        this.announceRequired = announceRequired;
        this.prompt = prompt == null ? "" : prompt;
        this.rejectMessage = rejectMessage == null || rejectMessage.isBlank() ? DEFAULT_REJECT : rejectMessage;
    }

    /** 状态页上的下发方式名称。 */
    public String name() {
        return "本插件内置推送";
    }

    /** 状态页上的一行细节：监听地址与路径，或者自定义地址。 */
    public String detail() {
        PackHttpServer current = server;
        if (current != null && current.running()) {
            return current.bind() + ":" + current.port() + current.urlPath();
        }
        String custom = plugin.config() == null ? "" : plugin.config().deliveryPublicUrl();
        if (!custom.isBlank()) {
            return "自定义地址 " + custom;
        }
        return "没有开启 HTTP 出口";
    }

    /** 下载地址：优先配置里的 public-url，否则按实际监听端口拼一个。 */
    public String baseUrl() {
        if (plugin.config() != null) {
            String custom = plugin.config().deliveryPublicUrl();
            if (!custom.isBlank()) {
                return custom.trim();
            }
        }
        PackHttpServer current = server;
        if (current == null || !current.running()) {
            return "";
        }
        String host = resolveHost(current.bind());
        if (host.isEmpty()) {
            return "";
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + current.port() + current.urlPath();
    }

    private static String resolveHost(String bind) {
        String host = bind == null ? "" : bind.trim();
        if (host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
            host = Bukkit.getIp();
        }
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException exception) {
            return "";
        }
    }

    /** 发布器构建成功后回调：记住最新地址与摘要，并同步 HTTP 出口的 ETag。 */
    public void publish(String url, String sha1) {
        this.lastUrl = url == null ? "" : url;
        this.lastSha1 = sha1 == null ? "" : sha1;
        PackHttpServer current = server;
        if (current != null) {
            current.publish(this.lastUrl, this.lastSha1);
        }
    }

    /**
     * 向一个在线玩家推送；返回排程数（0 表示没地址或玩家不在线）。
     *
     * <p>force 表示调用方要求重新推送；服务端不按 sha1 去重，同摘要也会重新调用
     * {@code setResourcePack}，客户端可命中缓存而不弹确认。客户端的“必需”标记只来自
     * {@code provider.required}，与 force 无关：必需包由服务端在收到 DECLINED 后按
     * {@code provider.reject-message} 断开，避免被客户端内置文案抢先。</p>
     */
    public int push(UUID player, boolean force) {
        if (player == null) {
            return 0;
        }
        Player online = Bukkit.getPlayer(player);
        if (online == null || !online.isOnline()) {
            return 0;
        }
        String url = lastUrl;
        String sha1 = lastSha1;
        if (url.isBlank()) {
            var publisher = plugin.publisher();
            PackSnapshot snapshot = publisher == null ? null : publisher.snapshot();
            if (snapshot != null) {
                url = snapshot.url();
                sha1 = snapshot.sha1();
            }
        }
        if (url == null || url.isBlank()) {
            return 0;
        }
        String target = url;
        String targetSha1 = sha1 == null ? "" : sha1;
        byte[] hash = Sha1Hex.toBytes(targetSha1);
        // provider.announce-required 决定是否把“必需”标记交给客户端：
        //   false（默认）——客户端只当可选包（Yes/No），拒绝后由服务端按 provider.required 断开，
        //            这样 provider.reject-message 一定是我们自己的文案。
        //   true ——客户端收到 required=true：连“已禁用”档位也会弹必装框（Proceed / Disconnect）；
        //            拒绝时客户端会先用它自己的文案断开，reject-message 只作服务端兜底。
        boolean needed = announceRequired;
        String hint = prompt;
        online.getScheduler().execute(plugin, () -> {
            if (online.isOnline()) {
                if (hash.length == 0) {
                    online.setResourcePack(target, hint, needed);
                } else {
                    online.setResourcePack(target, hash, hint, needed);
                }
                pushedSha1.put(player, targetSha1);
                statuses.record(player, online.getName(), PackStatus.PENDING, targetSha1);
                fire(online, PackStatus.PENDING, targetSha1);
            }
        }, null, 1L);
        return 1;
    }

    /** 让玩家重新加载一次资源包；不需要强制时由调用方传 false。 */
    public int refresh(UUID player) {
        return push(player, false);
    }

    /** 玩家当前状态；未知返回 UNKNOWN。 */
    public PackStatus status(UUID player) {
        return statuses.status(player);
    }

    /** 玩家成功加载时使用的 SHA-1；没有则 null。 */
    public String hashOf(UUID player) {
        return statuses.hash(player);
    }

    /** 最近的状态，新的在前。 */
    public List<DeliveryStatusStore.Entry> recent(int limit) {
        return statuses.recent(limit);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().execute(plugin,
                () -> push(player.getUniqueId(), false),
                null,
                Math.max(1L, joinDelayTicks));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        statuses.remove(player);
        pushedSha1.remove(player);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        String sha1 = pushedSha1.getOrDefault(player.getUniqueId(), lastSha1);
        PackStatus mapped = switch (status) {
            case SUCCESSFULLY_LOADED -> PackStatus.LOADED;
            case DECLINED -> PackStatus.DECLINED;
            case FAILED_DOWNLOAD -> PackStatus.FAILED;
            case INVALID_URL -> PackStatus.INVALID_URL;
            case ACCEPTED, DOWNLOADED -> PackStatus.PENDING;
            default -> null;
        };
        if (mapped != null) {
            statuses.record(player.getUniqueId(), player.getName(), mapped, sha1);
            fire(player, mapped, sha1);
        }
        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                if (statusFeedback) {
                    plugin.getLogger().info(LOG_PREFIX + "玩家 " + player.getName() + " 已加载资源包");
                } else {
                    plugin.getLogger().fine(LOG_PREFIX + "玩家 " + player.getName() + " 已加载资源包");
                }
            }
            case DECLINED -> {
                if (required) {
                    plugin.getLogger().warning(LOG_PREFIX + "玩家 " + player.getName() + " 拒绝了必装资源包，已排程断开");
                    player.getScheduler().execute(plugin, () -> {
                        if (player.isOnline()) {
                            player.kick(MiniMessage.miniMessage().deserialize(rejectMessage));
                        }
                    }, null, 1L);
                } else if (statusFeedback) {
                    plugin.getLogger().info(LOG_PREFIX + "玩家 " + player.getName() + " 拒绝了资源包");
                    player.sendMessage(Component.text(LOG_PREFIX + "你拒绝了资源包，可在选项里重新启用", NamedTextColor.YELLOW));
                } else {
                    plugin.getLogger().fine(LOG_PREFIX + "玩家 " + player.getName() + " 拒绝了资源包");
                }
            }
            case FAILED_DOWNLOAD -> plugin.getLogger().warning(
                    LOG_PREFIX + "玩家 " + player.getName() + " 下载资源包失败：" + describeUrl());
            case INVALID_URL -> plugin.getLogger().warning(
                    LOG_PREFIX + "资源包地址无效：" + describeUrl());
            default -> plugin.getLogger().fine(LOG_PREFIX + "玩家 " + player.getName() + " 回执 " + status);
        }
    }

    private void fire(Player player, PackStatus status, String sha1) {
        PackStatusEvent event = new PackStatusEvent(player.getUniqueId(), status, sha1);
        player.getScheduler().execute(plugin,
                () -> Bukkit.getPluginManager().callEvent(event),
                null,
                1L);
    }

    private String describeUrl() {
        String url = lastUrl;
        return url.isBlank() ? "（还没有可用地址）" : url;
    }

    /** 关闭 HTTP 出口并注销监听；重载时先关旧的再建新的，避免端口占用与重复监听。 */
    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        PackHttpServer current = server;
        if (current != null) {
            current.close();
        }
        lastUrl = "";
        lastSha1 = "";
        pushedSha1.clear();
    }
}
