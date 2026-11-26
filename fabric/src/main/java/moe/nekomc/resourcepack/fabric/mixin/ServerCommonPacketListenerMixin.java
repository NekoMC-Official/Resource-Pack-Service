package moe.nekomc.resourcepack.fabric.mixin;

import moe.nekomc.resourcepack.fabric.FabricPlatform;
import moe.nekomc.resourcepack.fabric.RpsFabricMod;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 资源包回执的唯一入口。
 *
 * <p>26.3 把 {@code handleResourcePackResponse} 放在 {@link ServerCommonPacketListenerImpl}
 * 上，game 监听器没有覆写；这里只观测不取消，原版流程照旧，
 * 我们额外把状态交给下发器（拒绝时按配置断开）。</p>
 *
 * <p>注入点用 TAIL 而不是 HEAD：原版方法开头会把执行切回主线程
 * （{@code ensureRunningOnSameThread}），HEAD 注入会让我们跑在 Netty 线程上，
 * 与主线程那次回执争同一份状态。</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void resourcepackservice$onResponse(ServerboundResourcePackPacket packet, CallbackInfo info) {
        if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
            FabricPlatform platform = RpsFabricMod.platform();
            if (platform != null && listener.player != null) {
                platform.onResponse(listener.player, packet.action());
            }
        }
    }
}
