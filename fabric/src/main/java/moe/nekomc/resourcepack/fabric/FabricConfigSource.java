package moe.nekomc.resourcepack.fabric;

import java.util.List;
import java.util.Map;
import moe.nekomc.resourcepack.platform.ConfigSource;

/**
 * SnakeYAML 解析结果上的配置读取端口。
 *
 * <p>取值语义对齐 Bukkit 的 FileConfiguration：按点号分段、类型不符就当没有，
 * 缺省值一律由调用方给。</p>
 */
public final class FabricConfigSource implements ConfigSource {

    private final Map<?, ?> root;

    public FabricConfigSource(Map<?, ?> root) {
        this.root = root == null ? Map.of() : root;
    }

    private Object raw(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Override
    public boolean isSet(String path) {
        return raw(path) != null;
    }

    @Override
    public String getString(String path, String fallback) {
        Object value = raw(path);
        // 与 Bukkit 的 getString 对齐：数字/布尔也按文本读，免得 YAML 里 pack-format: 97 被当成缺省。
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        Object value = raw(path);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    @Override
    public long getLong(String path, long fallback) {
        Object value = raw(path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public int getInt(String path, int fallback) {
        Object value = raw(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public Object get(String path) {
        return raw(path);
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        Object value = raw(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        java.util.ArrayList<Map<?, ?>> rows = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(map);
            }
        }
        return rows;
    }
}
