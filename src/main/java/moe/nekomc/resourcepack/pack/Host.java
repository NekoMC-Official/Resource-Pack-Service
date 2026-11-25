package moe.nekomc.resourcepack.pack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import moe.nekomc.resourcepack.api.BuildResult;
import moe.nekomc.resourcepack.api.PackStatus;

/**
 * 注册表与发布器之间的接缝：注册表只管登记，重建/查状态/重推都交回插件。
 *
 * <p>抽成接口是为了让 {@link RegisteredSources} 不直接依赖插件的其它部分，
 * 测试里可以塞一个假实现。</p>
 */
public interface Host {

    void rebuild(boolean announce);

    /** 发起构建并等待本轮（或合并后的下一轮）结果。 */
    default CompletableFuture<BuildResult> rebuildAsync(boolean announce) {
        rebuild(announce);
        return CompletableFuture.completedFuture(snapshot());
    }

    /** 当前构建快照；失败时保留上一版字段并带 error。 */
    default BuildResult snapshot() {
        HostStatus current = status();
        return new BuildResult(
                current.version() > 0 && current.url() != null && !current.url().isBlank(),
                current.sha1(),
                current.version(),
                current.url(),
                0,
                0L,
                null);
    }

    HostStatus status();

    int push(UUID player, boolean force);

    /** 玩家当前状态；未知返回 UNKNOWN。 */
    default PackStatus status(UUID player) {
        return PackStatus.UNKNOWN;
    }

    /** 玩家加载成功时使用的 SHA-1；没有则 null。 */
    default String hashOf(UUID player) {
        return null;
    }

    /** 最近一次构建失败原因；没有则 null。 */
    default String lastError() {
        return null;
    }
}
