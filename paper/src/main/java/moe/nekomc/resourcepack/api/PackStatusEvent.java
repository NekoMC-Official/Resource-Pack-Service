package moe.nekomc.resourcepack.api;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 某个玩家的资源包状态发生变化。
 *
 * <p><b>线程：</b>事件在 Folia 的玩家实体调度器上触发，不在全局区域线程，也不保证在
 * 服务端主线程。监听器只应做轻量记录；需要访问世界或实体时，请把工作再调度回该玩家的
 * 实体调度器。使用 {@link org.bukkit.event.EventPriority#MONITOR} 可以只观察、不干扰。</p>
 *
 * <p>该事件不参与取消；它描述的是已经发生的客户端回执。</p>
 */
public final class PackStatusEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final PackStatus status;
    private final String sha1;

    public PackStatusEvent(UUID playerId, PackStatus status, String sha1) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.status = Objects.requireNonNull(status, "status");
        this.sha1 = sha1 == null ? "" : sha1;
    }

    /** 玩家 UUID。 */
    public UUID playerId() {
        return playerId;
    }

    /** 新状态。 */
    public PackStatus status() {
        return status;
    }

    /** 这次状态对应的包 SHA-1；没有对应包时为空串。 */
    public String sha1() {
        return sha1;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
