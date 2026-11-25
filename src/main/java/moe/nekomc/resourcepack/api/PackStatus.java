package moe.nekomc.resourcepack.api;

/**
 * 一个玩家对当前合成包的处理状态。
 *
 * <p>状态来自客户端回执，由服务端在玩家实体调度器上更新。未推送过、已离线清理，
 * 或者服务端尚未构建出包时都是 {@link #UNKNOWN}。</p>
 */
public enum PackStatus {
    /** 没有收到过回执，或玩家状态已被清理。 */
    UNKNOWN,
    /** 已排程推送 / 客户端已接受，等待下载或加载结果。 */
    PENDING,
    /** 客户端报告已成功加载。 */
    LOADED,
    /** 玩家在提示框里选择了“否”。 */
    DECLINED,
    /** 下载或应用失败。 */
    FAILED,
    /** 客户端认为地址无效（通常是地址不可达或格式错误）。 */
    INVALID_URL
}
