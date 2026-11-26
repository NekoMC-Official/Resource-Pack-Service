package moe.nekomc.resourcepack.platform;

import java.util.UUID;

/**
 * 在线玩家的最小视图。
 *
 * <p>内核只按 UUID 认人、按名字展示、按 MiniMessage 发消息；具体玩家对象留在适配层。</p>
 */
public interface PlayerRef {

    UUID uniqueId();

    String name();

    /** 发送一条 MiniMessage 文案；解析与样式在适配层完成。 */
    void sendMessage(String miniMessage);
}
