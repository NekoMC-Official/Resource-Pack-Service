package moe.nekomc.resourcepack.fabric;

import java.util.UUID;
import moe.nekomc.resourcepack.platform.PlayerRef;
import net.minecraft.server.level.ServerPlayer;

/** 在线玩家的 Fabric 视图：只暴露内核用到的三件事。 */
public record FabricPlayerRef(ServerPlayer handle) implements PlayerRef {

    @Override
    public UUID uniqueId() {
        return handle.getUUID();
    }

    @Override
    public String name() {
        return handle.getGameProfile().name();
    }

    @Override
    public void sendMessage(String miniMessage) {
        handle.sendSystemMessage(MiniMessages.parse(miniMessage));
    }
}
