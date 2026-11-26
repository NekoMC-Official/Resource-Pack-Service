package moe.nekomc.resourcepack.fabric;

import java.util.UUID;
import moe.nekomc.resourcepack.command.CommandSenderRef;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * 命令源到命令端口的适配。
 *
 * <p>Fabric 没有权限节点体系，这里按原版权限位折算：控制台全权，玩家只有
 * {@code rps.use} 公开，其余按 op 判定。将来接权限模组只需改这一个方法。</p>
 */
public final class FabricCommandSender implements CommandSenderRef {

    private final CommandSourceStack source;

    public FabricCommandSender(CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public UUID getPlayerId() {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : player.getUUID();
    }

    @Override
    public boolean hasPermission(String node) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        if ("rps.use".equals(node) || "rps.conflicts".equals(node)) {
            return true;
        }
        return source.getServer().getPlayerList().isOp(new NameAndId(player.getUUID(), player.getGameProfile().name()));
    }

    @Override
    public void sendMessage(String miniMessage) {
        source.sendSystemMessage(MiniMessages.parse(miniMessage));
    }
}
