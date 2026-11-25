package moe.nekomc.resourcepack.pack;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 服务端绑定选择需要的环境。
 *
 * @param installed        插件是否已安装并启用
 * @param pluginVersion    读取插件版本；读取不到时返回 null
 * @param minecraftVersion 服务端 Minecraft 版本，例如 26.3
 * @param packFormat       服务端当前资源包格式；0 表示不检查
 */
public record SelectionContext(
        Predicate<String> installed,
        Function<String, String> pluginVersion,
        String minecraftVersion,
        int packFormat
) {

    public SelectionContext {
        installed = installed == null ? name -> false : installed;
        pluginVersion = pluginVersion == null ? name -> null : pluginVersion;
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        packFormat = Math.max(0, packFormat);
    }
}
