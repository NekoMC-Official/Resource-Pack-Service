package moe.nekomc.resourcepack.platform;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 把 Bukkit 的 {@code FileConfiguration} 接到内核的配置端口上。
 */
public final class BukkitConfigSource implements ConfigSource {

    private final FileConfiguration config;

    public BukkitConfigSource(FileConfiguration config) {
        this.config = config;
    }

    @Override
    public boolean isSet(String path) {
        return config.isSet(path);
    }

    @Override
    public String getString(String path, String fallback) {
        return config.getString(path, fallback);
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        return config.getBoolean(path, fallback);
    }

    @Override
    public long getLong(String path, long fallback) {
        return config.getLong(path, fallback);
    }

    @Override
    public int getInt(String path, int fallback) {
        return config.getInt(path, fallback);
    }

    @Override
    public Object get(String path) {
        return config.get(path);
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        return config.getMapList(path);
    }
}
