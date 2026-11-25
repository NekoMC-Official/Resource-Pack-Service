package moe.nekomc.resourcepack.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 资源包统一服务的对外入口，通过 Bukkit ServicesManager 取得：
 *
 * <pre>{@code
 * ResourcePackService service = Bukkit.getServicesManager().load(ResourcePackService.class);
 * service.register(myPackSource);
 * }</pre>
 *
 * <p>注册不会立刻影响已在线的客户端，服务会异步重建合成包；需要立即重推时调用
 * {@link #push(UUID, boolean)}。</p>
 *
 * <p>新增方法都带默认实现，已有实现类不需要改动即可编译。</p>
 */
public interface ResourcePackService {

    /** 登记或替换一个来源；id 为空、文件为空时返回 false。会触发一次异步重建。 */
    boolean register(PackSource source);

    /** 注销来源并触发异步重建；不存在时返回 false。 */
    boolean unregister(String id);

    /**
     * 通知服务某个已登记来源的内容已经变化；服务会异步重建。
     *
     * <p>id 为空或未登记时不做任何事。插件重写自己的 zip 后调用这个方法即可，
     * 不需要先 unregister 再 register。</p>
     */
    void invalidate(String id);

    /** 当前通过本接口登记的来源 id。 */
    Set<String> sourceIds();

    /** 当前合成包世代号；未构建成功时为 0。 */
    int version();

    /** 当前合成包的下发地址；未配置或未构建时为空串。 */
    String url();

    /** 当前合成包的 sha1；未构建时为空串。 */
    String sha1();

    /**
     * 手动重建；announce 为 true 时按 push.mode 通知在线玩家。
     *
     * <p>这是既有的“发令”方法，不等待结果。需要知道这次构建是否成功，请用
     * {@link #rebuildAsync(boolean)}。</p>
     */
    void rebuild(boolean announce);

    /**
     * 发起一次重建并等待结果。
     *
     * <p>Java 不能只按返回类型重载 {@code rebuild(boolean)}，所以异步版本另起名为
     * {@code rebuildAsync}，旧签名保持不变。同一时刻只跑一次构建；构建期间的重复请求会
     * 合并到下一次，返回的 future 在那一轮结束时完成。发布器被 reload / 关闭时，
     * 未完成的 future 会以失败结果结束，不会永远悬挂。</p>
     *
     * @param announce 成功后是否按 push.mode 通知在线玩家
     * @return 本次（或合并后的下一次）构建结果
     */
    default CompletableFuture<BuildResult> rebuildAsync(boolean announce) {
        rebuild(announce);
        return CompletableFuture.completedFuture(snapshot());
    }

    /**
     * 当前快照。{@code sha1} 与客户端实际下载的 merged.zip 逐字节一致；{@code url} 是
     * 带版本参数的完整可 GET 地址。失败时保留上一次成功构建的字段，并带上 {@code error}。
     */
    default BuildResult snapshot() {
        String hash = sha1();
        String address = url();
        return new BuildResult(
                hash != null && !hash.isBlank() && address != null && !address.isBlank(),
                hash,
                version(),
                address,
                0,
                0L,
                null);
    }

    /**
     * 玩家当前的资源包状态；未知玩家（包括已离线清理）返回 {@link PackStatus#UNKNOWN}，
     * 不会返回 {@code null}。
     */
    default PackStatus status(UUID player) {
        return PackStatus.UNKNOWN;
    }

    /**
     * 玩家加载成功时使用的包 SHA-1；没有成功加载过、或状态已被清理时返回 {@code null}。
     */
    default String hashOf(UUID player) {
        return null;
    }

    /** 最近一次构建失败的原因；成功或尚未构建时为 {@code null}。 */
    default String lastError() {
        return null;
    }

    /**
     * 向指定在线玩家重推，返回排程的推送数（0 表示没有地址或玩家不在线）。
     *
     * <p><b>force 语义：</b>服务端不按 SHA-1 去重，{@code force=false} 也会调用
     * {@code setResourcePack}，客户端可命中本地缓存而不弹确认框；{@code force=true} 会把
     * 包标记为必需，客户端必须处理。{@code provider.required=true} 时，即使
     * {@code force=false} 也会标记为必需。两条路径都可能出现客户端确认框，
     * “必需”只影响能否拒绝，不等于跳过提示。</p>
     */
    int push(UUID player, boolean force);
}
