package moe.nekomc.resourcepack.fabric;

import java.util.logging.Logger;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

/**
 * 26.3 的客户端回执只有 {@code ServerCommonPacketListenerImpl#handleResourcePackResponse} 这一个落点，
 * mixin 按方法名注入。升级 Minecraft 后若这个方法改名或挪位置，mixin 会静默失效，
 * 于是启动时先做一次反射自检，不通过就打警告，便于第一时间发现。
 */
public final class CompatibilityCheck {

    private CompatibilityCheck() {
    }

    public static boolean responseHookAvailable() {
        try {
            ServerCommonPacketListenerImpl.class.getMethod("handleResourcePackResponse", ServerboundResourcePackPacket.class);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    public static void verify(Logger logger) {
        if (!responseHookAvailable()) {
            logger.warning("[资源包] 当前 Minecraft 版本没有 ServerCommonPacketListenerImpl#handleResourcePackResponse，"
                + "资源包回执无法跟踪。mixin 配置为 required=true，正常情况服务端会直接启动失败；"
                + "若服务端仍然启动，说明注入被跳过，请检查 mod 冲突与 mixin 日志。");
        }
    }
}
