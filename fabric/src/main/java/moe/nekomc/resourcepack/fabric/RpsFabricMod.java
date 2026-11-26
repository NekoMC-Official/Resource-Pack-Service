package moe.nekomc.resourcepack.fabric;

import java.nio.file.Path;
import java.util.logging.Logger;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端入口：只装服务端，客户端不需要这个 mod。
 *
 * <p>装配顺序按 Fabric 生命周期来：命令先注册（服务端还没起来），
 * SERVER_STARTED 才碰配置与网络，STOPPING 收摊。</p>
 */
public final class RpsFabricMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "resource-pack-service";
    private static final Logger LOG = FabricLog.get("ResourcePackService");
    private static volatile FabricPlatform platform;

    /** 其它类（mixin、命令）取平台实例的入口。 */
    public static FabricPlatform platform() {
        return platform;
    }

    @Override
    public void onInitializeServer() {
        CompatibilityCheck.verify(LOG);
        FabricLoader loader = FabricLoader.getInstance();
        Path dataFolder = loader.getConfigDir().resolve(MOD_ID);
        Path serverRoot = loader.getGameDir();
        FabricPlatform created = new FabricPlatform(LOG, dataFolder, serverRoot);
        platform = created;

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            FabricCommands.register(dispatcher, created));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> created.attach(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> created.tick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                created.onJoin(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                created.onQuit(player.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> created.shutdown());
    }
}
