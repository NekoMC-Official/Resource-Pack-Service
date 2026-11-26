package moe.nekomc.resourcepack.platform;

import java.util.List;
import java.util.Map;

/**
 * 配置读取端口。
 *
 * <p>内核只认这几个方法：Paper 侧包 {@code FileConfiguration}，Fabric 侧包 SnakeYAML，
 * 具体 YAML 库不进入 :core。取值语义与 Bukkit 保持一致（缺省值在调用处给）。</p>
 */
public interface ConfigSource {

    /** 键是否存在；用于区分“没写”和“写了默认值”。 */
    boolean isSet(String path);

    /** 字符串值；键不存在或类型不符时返回 fallback。 */
    String getString(String path, String fallback);

    boolean getBoolean(String path, boolean fallback);

    long getLong(String path, long fallback);

    int getInt(String path, int fallback);

    /** 原始对象；只用于需要在调用处自行解释类型的键。 */
    Object get(String path);

    /** 形如 sources 的列表；每项是一张键值表。 */
    List<Map<?, ?>> getMapList(String path);
}
