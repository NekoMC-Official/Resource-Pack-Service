package moe.nekomc.resourcepack.api;

import java.util.Set;
import java.util.UUID;

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
 */
public interface ResourcePackService {

    /** 登记或替换一个来源；id 为空、文件为空时返回 false。会触发一次异步重建。 */
    boolean register(PackSource source);

    /** 注销来源并触发异步重建；不存在时返回 false。 */
    boolean unregister(String id);

    /** 当前通过本接口登记的来源 id。 */
    Set<String> sourceIds();

    /** 当前合成包世代号；未构建成功时为 0。 */
    int version();

    /** 当前合成包的下发地址；未配置或未构建时为空串。 */
    String url();

    /** 当前合成包的 sha1；未构建时为空串。 */
    String sha1();

    /** 手动重建；announce 为 true 时按 push.mode 通知在线玩家。 */
    void rebuild(boolean announce);

    /** 向指定在线玩家重推，返回排程的推送数。 */
    int push(UUID player, boolean force);
}
