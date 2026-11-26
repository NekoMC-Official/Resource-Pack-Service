package moe.nekomc.resourcepack.fabric;

import java.util.UUID;
import moe.nekomc.resourcepack.command.CommandSenderRef;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * 命令源到命令端口的适配。
 *
 * <p>Fabric 没有权限节点体系，这里按原版权限位折算：玩家固定放行 {@code rps.use}，
 * 其余节点（含 {@code rps.conflicts}）按 op 判定；控制台与命令方块不是无条件放行，
 * 而是要求原版 {@code Permissions.COMMANDS_ADMIN}。将来接权限模组只需改这一个方法。</p>
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
            // 控制台/命令方块：按原版管理员权限位判，不再一律放行。
            return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
        }
        // 与 plugin.yml 对齐：只有 rps.use 对普通玩家开放，conflicts 需要 op。
        if ("rps.use".equals(node)) {
            return true;
        }
        return source.getServer().getPlayerList().isOp(new NameAndId(player.getUUID(), player.getGameProfile().name()));
    }

    @Override
    public void sendMessage(String miniMessage) {
        source.sendSystemMessage(MiniMessages.parse(miniMessage));
    }
}
