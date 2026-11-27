package moe.nekomc.resourcepack.delivery;

import java.util.List;
import java.util.UUID;

/**
 * 下发通道端口：把当前合成包推给某个玩家。
 *
 * <p>发布器只关心“推了几个人”，发包细节（协议、必装标记、断开策略）在适配层。</p>
 */
public interface PackDelivery {

    /**
     * @return 1 表示已发起推送，0 表示当前没有可下发的包
     */
    int push(UUID player, boolean force);

    /**
     * 按指定摘要推送；实现方应把它当作“本次实际发出的摘要”，回执与去重都以它为准。
     * 默认实现忽略 sha1，老平台照旧用最近一次发布的摘要。
     */
    default int push(UUID player, String sha1, boolean force) {
        return push(player, force);
    }

    /** 玩家当前已加载的包摘要；没有加载成功或平台不跟踪时返回空串。 */
    default String loadedSha1(UUID player) {
        return "";
    }

    /** 当前对外下载地址（HTTP 出口或 delivery.http.public-url）；没有可用地址时返回空串。 */
    String baseUrl();

    /** 合成成功后回调：记住最新地址与摘要，并同步 HTTP 出口的缓存标记。 */
    void publish(String url, String sha1);

    /** 状态页上的下发方式名称。 */
    default String name() {
        return "本插件内置推送";
    }

    /** 状态页上的一行细节：监听地址与路径，或者自定义地址。 */
    default String detail() {
        return "";
    }

    /** 最近的状态记录，新的在前；平台没有记录能力时返回空表。 */
    default List<DeliveryStatusStore.Entry> recent(int limit) {
        return List.of();
    }
}
