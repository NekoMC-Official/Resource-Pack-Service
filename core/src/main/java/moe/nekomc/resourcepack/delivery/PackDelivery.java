package moe.nekomc.resourcepack.delivery;

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

    /** 当前对外下载地址（HTTP 出口或 delivery.http.public-url）；没有可用地址时返回空串。 */
    String baseUrl();

    /** 合成成功后回调：记住最新地址与摘要，并同步 HTTP 出口的缓存标记。 */
    void publish(String url, String sha1);
}
