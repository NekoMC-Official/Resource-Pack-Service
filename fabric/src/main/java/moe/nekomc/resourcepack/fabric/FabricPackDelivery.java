package moe.nekomc.resourcepack.fabric;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.config.RpsConfig;
import moe.nekomc.resourcepack.delivery.DeliveryStatusStore;
import moe.nekomc.resourcepack.delivery.PackDelivery;
import moe.nekomc.resourcepack.delivery.PackHttpServer;
import moe.nekomc.resourcepack.pack.PackSnapshot;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 侧的下发器：直接发 {@link ClientboundResourcePackPushPacket}，回执由 mixin 转进来。
 *
 * <p>语义与 Paper 侧保持一致：{@code announce-required} 决定包里 required 标记；
 * 玩家拒绝后按 {@code provider.required} 断开并用自己的文案，避免被客户端内置文案抢先。</p>
 */
public final class FabricPackDelivery implements PackDelivery, AutoCloseable {

    private static final String LOG_PREFIX = "[资源包] ";
    private static final String DEFAULT_REJECT =
        "<red>本服资源包为必装项。请重新进入并在提示中点“继续”下载。";

    private final FabricPlatform platform;
    private final PackHttpServer server;
    private final int joinDelayTicks;
    private final boolean statusFeedback;
    private final boolean required;
    private final boolean announceRequired;
    private final String prompt;
    private final String rejectMessage;
    private final DeliveryStatusStore statuses = new DeliveryStatusStore();
    private final ConcurrentHashMap<UUID, String> pushedSha1 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> lastPushId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastSha1ByPlayer = new ConcurrentHashMap<>();
    private volatile String lastUrl = "";
    private volatile String lastSha1 = "";
    private volatile boolean closed;

    public FabricPackDelivery(
        FabricPlatform platform,
        PackHttpServer server,
        int joinDelayTicks,
        boolean statusFeedback,
        boolean required,
        boolean announceRequired,
        String prompt,
        String rejectMessage
    ) {
        this.platform = platform;
        this.server = server;
        this.joinDelayTicks = Math.max(0, joinDelayTicks);
        this.statusFeedback = statusFeedback;
        this.required = required;
        this.announceRequired = announceRequired;
        this.prompt = prompt == null ? "" : prompt;
        this.rejectMessage = rejectMessage == null || rejectMessage.isBlank() ? DEFAULT_REJECT : rejectMessage;
    }

    @Override
    public String name() {
        return "本插件内置推送";
    }

    @Override
    public String detail() {
        PackHttpServer current = server;
        if (current != null && current.running()) {
            return current.bind() + ":" + current.port() + current.urlPath();
        }
        RpsConfig config = platform.config();
        if (config != null && !config.deliveryPublicUrl().isBlank()) {
            return "自定义地址 " + config.deliveryPublicUrl();
        }
        return "没有开启 HTTP 出口";
    }

    @Override
    public String baseUrl() {
        RpsConfig config = platform.config();
        if (config != null && !config.deliveryPublicUrl().isBlank()) {
            return config.deliveryPublicUrl().trim();
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
            host = localAddress();
        }
        return host == null ? "" : host;
    }

    /** 取一个客户端真能连上的本机地址：优先站点内网 IPv4，其次其它非回环 IPv4，最后才回退本机名。 */
    private static String localAddress() {
        String fallback = "";
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof java.net.Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                    if (fallback.isEmpty()) {
                        fallback = address.getHostAddress();
                    }
                }
            }
        } catch (Exception exception) {
            return "";
        }
        if (!fallback.isEmpty()) {
            return fallback;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "";
        }
    }

    @Override
    public void publish(String url, String sha1) {
        this.lastUrl = url == null ? "" : url;
        this.lastSha1 = sha1 == null ? "" : sha1;
        PackHttpServer current = server;
        if (current != null) {
            current.publish(this.lastUrl, this.lastSha1);
        }
    }

    @Override
    public int push(UUID playerId, boolean force) {
        return push(playerId, "", force);
    }

    /** 按指定摘要推送；sha1 为空时回退到最近一次发布的摘要。 */
    @Override
    public int push(UUID playerId, String sha1Override, boolean force) {
        if (playerId == null || closed) {
            return 0;
        }
        String url = lastUrl;
        String sha1 = sha1Override == null || sha1Override.isBlank() ? lastSha1 : sha1Override;
        if (url.isBlank()) {
            var publisher = platform.publisher();
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
        Optional<net.minecraft.network.chat.Component> hint =
            prompt.isBlank() ? Optional.empty() : Optional.of(MiniMessages.parse(prompt));
        boolean needed = announceRequired;
        UUID pushId = UUID.randomUUID();
        platform.runOnServer(() -> {
            ServerPlayer online = platform.player(playerId);
            if (online == null) {
                var publisher = platform.publisher();
                var controller = publisher == null ? null : publisher.fanoutOrNull();
                if (controller != null) {
                    controller.playerLeft(playerId);
                }
                return;
            }
            platform.logger().info(LOG_PREFIX + "向玩家 " + online.getGameProfile().name() + " 推送资源包（必装提示=" + needed + "）：" + target);
            online.connection.send(new ClientboundResourcePackPushPacket(
                pushId, target, targetSha1, needed, hint));
            pushedSha1.put(pushId, targetSha1);
            lastSha1ByPlayer.put(playerId, targetSha1);
            UUID previousPush = lastPushId.put(playerId, pushId);
            if (previousPush != null) {
                pushedSha1.remove(previousPush);
            }
            statuses.record(playerId, online.getGameProfile().name(), PackStatus.PENDING, targetSha1);
            platform.statusChanged(playerId, online.getGameProfile().name(), PackStatus.PENDING, targetSha1);
        });
        return 1;
    }

    public int refresh(UUID player) {
        return push(player, false);
    }

    public PackStatus status(UUID player) {
        return statuses.status(player);
    }

    public String hashOf(UUID player) {
        return statuses.hash(player);
    }

    @Override
    public String loadedSha1(UUID player) {
        return statuses.hash(player);
    }

    @Override
    public List<DeliveryStatusStore.Entry> recent(int limit) {
        return statuses.recent(limit);
    }

    /** 玩家进服：按 join-delay 排一次推送。 */
    public void onJoin(ServerPlayer player) {
        if (closed) {
            return;
        }
        UUID id = player.getUUID();
        long delay = Math.max(1L, joinDelayTicks) * 50L;
        platform.scheduler().runAsyncTimer(delay, 0L, () -> {
            var publisher = platform.publisher();
            var controller = publisher == null ? null : publisher.fanout();
            if (controller != null) {
                controller.enqueue(java.util.List.of(id), lastSha1, true);
            } else {
                push(id, false);
            }
        });
    }

    /** 玩家下线：清掉当前状态，历史留给 /rps rejected。 */
    public void onQuit(UUID playerId) {
        statuses.remove(playerId);
        lastSha1ByPlayer.remove(playerId);
        UUID sent = lastPushId.remove(playerId);
        if (sent != null) {
            pushedSha1.remove(sent);
        }
        var publisher = platform.publisher();
        var controller = publisher == null ? null : publisher.fanoutOrNull();
        if (controller != null) {
            controller.playerLeft(playerId);
        }
    }

    /** 客户端回执：mixin 已经把我们发的包交给这里。 */
    public void onResponse(ServerPlayer player, UUID pushId, ServerboundResourcePackPacket.Action action) {
        if (player == null || action == null) {
            return;
        }
        UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        UUID sentId = pushId != null ? pushId : lastPushId.get(id);
        String fallback = lastSha1ByPlayer.getOrDefault(id, lastSha1);
        String sha1 = sentId == null ? fallback : pushedSha1.getOrDefault(sentId, fallback);
        PackStatus mapped = switch (action) {
            case SUCCESSFULLY_LOADED -> PackStatus.LOADED;
            case DECLINED -> PackStatus.DECLINED;
            case DISCARDED -> null;
            case FAILED_DOWNLOAD, FAILED_RELOAD -> PackStatus.FAILED;
            case INVALID_URL -> PackStatus.INVALID_URL;
            case ACCEPTED, DOWNLOADED -> PackStatus.PENDING;
        };
        if (closed) {
            return;
        }
        if (mapped == null) {
            // DISCARDED 是客户端丢弃旧包（换包/退出）的通知，Paper 侧同样不记录、不断开。
            platform.logger().fine(LOG_PREFIX + "玩家 " + name + " 丢弃了资源包通知");
            return;
        }
        if (sentId != null && mapped != PackStatus.PENDING) {
            // 终态回执才消费 pushId 映射：ACCEPTED/DOWNLOADED 之后还有一次 SUCCESSFULLY_LOADED。
            pushedSha1.remove(sentId);
        }
        // 26.3 的同一次加载会从 Netty 与主线程各投递一次同样回执，重复状态只记第一次，
        // 免得重复打日志、重复触发断开排程。
        if (statuses.status(id) == mapped && java.util.Objects.equals(statuses.hash(id), sha1)) {
            return;
        }
        statuses.record(id, name, mapped, sha1);
        platform.statusChanged(id, name, mapped, sha1);
        var publisher = platform.publisher();
        var controller = publisher == null ? null : publisher.fanout();
        if (controller != null) {
            controller.onStatus(id, mapped, sha1);
        }
        switch (action) {
            case SUCCESSFULLY_LOADED -> {
                if (statusFeedback) {
                    platform.logger().info(LOG_PREFIX + "玩家 " + name + " 已加载资源包");
                } else {
                    platform.logger().fine(LOG_PREFIX + "玩家 " + name + " 已加载资源包");
                }
            }
            case DECLINED -> {
                if (required) {
                    platform.logger().warning(LOG_PREFIX + "玩家 " + name + " 拒绝了必装资源包，已排程断开");
                    platform.runOnServerLater(() -> {
                        ServerPlayer online = platform.player(id);
                        if (online != null) {
                            online.connection.disconnect(MiniMessages.parse(rejectMessage));
                        }
                    }, 1);
                } else if (statusFeedback) {
                    platform.logger().info(LOG_PREFIX + "玩家 " + name + " 拒绝了资源包");
                    player.sendSystemMessage(MiniMessages.parse(LOG_PREFIX + "你拒绝了资源包，可在选项里重新启用"));
                } else {
                    platform.logger().fine(LOG_PREFIX + "玩家 " + name + " 拒绝了资源包");
                }
            }
            case FAILED_DOWNLOAD -> platform.logger().warning(
                LOG_PREFIX + "玩家 " + name + " 下载资源包失败：" + describeUrl());
            case INVALID_URL -> platform.logger().warning(LOG_PREFIX + "资源包地址无效：" + describeUrl());
            default -> platform.logger().fine(LOG_PREFIX + "玩家 " + name + " 回执 " + action);
        }
    }

    private String describeUrl() {
        String url = lastUrl;
        return url.isBlank() ? "（还没有可用地址）" : url;
    }

    @Override
    public void close() {
        closed = true;
        PackHttpServer current = server;
        if (current != null) {
            current.close();
        }
        lastUrl = "";
        lastSha1 = "";
        pushedSha1.clear();
        lastPushId.clear();
        lastSha1ByPlayer.clear();
    }
}
