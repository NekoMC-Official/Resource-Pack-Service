package moe.nekomc.resourcepack.platform;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * 把在线玩家包成内核认识的 {@link PlayerRef}；MiniMessage 在这里解析成 Adventure 组件。
 */
public final class BukkitPlayerRef implements PlayerRef {

    private final Player player;

    public BukkitPlayerRef(Player player) {
        this.player = player;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public void sendMessage(String miniMessage) {
        Component component = MiniMessage.miniMessage().deserialize(miniMessage);
        player.sendMessage(component);
    }
}
