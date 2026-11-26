package moe.nekomc.resourcepack.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构合并：只对能真正合并的 JSON 生效，其余一律按路径覆盖。
 *
 * <p>lang / sounds 是键值表，深层合并、高层胜；font / atlases 是列表，高层在前拼接并去重。</p>
 */
public final class JsonMerge {

    public enum Kind {
        OBJECT,
        ARRAY_PROVIDERS,
        ARRAY_SOURCES,
        SKIP
    }

    private JsonMerge() {
    }

    public static Kind kindOf(String path, boolean enabled) {
        if (!enabled || !path.startsWith("assets/") || !path.endsWith(".json")) {
            return Kind.SKIP;
        }
        if (path.matches("assets/[^/]+/lang/[^/]+\\.json")) {
            return Kind.OBJECT;
        }
        if (path.matches("assets/[^/]+/sounds\\.json")) {
            return Kind.OBJECT;
        }
        if (path.matches("assets/[^/]+/font/[^/]+\\.json")) {
            return Kind.ARRAY_PROVIDERS;
        }
        if (path.matches("assets/[^/]+/atlases/[^/]+\\.json")) {
            return Kind.ARRAY_SOURCES;
        }
        return Kind.SKIP;
    }

    /** layers 按层序从低到高。 */
    public static JsonObject merge(List<JsonObject> layers, Kind kind, String path) {
        if (layers.isEmpty()) {
            return new JsonObject();
        }
        JsonObject result = layers.get(0).deepCopy();
        for (int i = 1; i < layers.size(); i++) {
            deepMerge(result, layers.get(i));
        }
        if (kind == Kind.ARRAY_PROVIDERS) {
            result.add("providers", concatHighFirst(layers, "providers"));
        } else if (kind == Kind.ARRAY_SOURCES) {
            result.add("sources", concatHighFirst(layers, "sources"));
        }
        return result;
    }

    private static JsonArray concatHighFirst(List<JsonObject> layers, String field) {
        Set<String> seen = new LinkedHashSet<>();
        List<JsonElement> kept = new ArrayList<>();
        for (int i = layers.size() - 1; i >= 0; i--) {
            JsonElement element = layers.get(i).get(field);
            if (element == null || !element.isJsonArray()) {
                continue;
            }
            for (JsonElement item : element.getAsJsonArray()) {
                if (seen.add(item.toString())) {
                    kept.add(item.deepCopy());
                }
            }
        }
        JsonArray array = new JsonArray();
        for (JsonElement item : kept) {
            array.add(item);
        }
        return array;
    }

    private static void deepMerge(JsonObject target, JsonObject overlay) {
        for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) {
            String key = entry.getKey();
            JsonElement incoming = entry.getValue();
            JsonElement existing = target.get(key);
            if (existing != null && existing.isJsonObject() && incoming.isJsonObject()) {
                deepMerge(existing.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                target.add(key, incoming.deepCopy());
            }
        }
    }
}
