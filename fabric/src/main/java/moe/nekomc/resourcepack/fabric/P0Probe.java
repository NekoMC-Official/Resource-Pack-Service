package moe.nekomc.resourcepack.fabric;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/**
 * P0 探针：编译期验证 26.3 官方映射下资源包相关类型与回执钩子都在。
 */
public final class P0Probe {

    private P0Probe() {
    }

    public static Class<?>[] types() {
        return new Class<?>[] {
            ClientboundResourcePackPushPacket.class,
            ServerboundResourcePackPacket.class,
            ServerCommonPacketListenerImpl.class
        };
    }

    public static boolean responseHookAvailable() {
        try {
            Method method = ServerCommonPacketListenerImpl.class
                .getMethod("handleResourcePackResponse", ServerboundResourcePackPacket.class);
            return !Modifier.isStatic(method.getModifiers());
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }
}
