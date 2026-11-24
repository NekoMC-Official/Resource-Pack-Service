package moe.nekomc.resourcepack.pack;

import java.util.UUID;

/**
 * 注册表与发布器之间的接缝：注册表只管登记，重建/查状态/重推都交回插件。
 *
 * <p>抽成接口是为了让 {@link RegisteredSources} 不直接依赖插件的其它部分，
 * 测试里可以塞一个假实现。</p>
 */
public interface Host {

    void rebuild(boolean announce);

    HostStatus status();

    int push(UUID player, boolean force);
}
