package moe.nekomc.resourcepack.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 一个资源包声明的 format 支持区间。 */
public record FormatRange(int format, int min, int max) {

    public static FormatRange read(JsonObject mcmeta) {
        JsonObject pack = mcmeta.has("pack") && mcmeta.get("pack").isJsonObject()
                ? mcmeta.getAsJsonObject("pack")
                : new JsonObject();
        int format = pack.has("pack_format") ? pack.get("pack_format").getAsInt() : 0;
        int min = format;
        int max = format;
        JsonElement supported = pack.get("supported_formats");
        if (supported != null && !supported.isJsonNull()) {
            if (supported.isJsonArray()) {
                List<Integer> values = new ArrayList<>();
                for (JsonElement element : supported.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        values.add(element.getAsInt());
                    }
                }
                if (!values.isEmpty()) {
                    min = values.stream().mapToInt(Integer::intValue).min().orElse(format);
                    max = values.stream().mapToInt(Integer::intValue).max().orElse(format);
                }
            } else if (supported.isJsonObject()) {
                JsonObject object = supported.getAsJsonObject();
                if (object.has("min_inclusive")) {
                    min = object.get("min_inclusive").getAsInt();
                }
                if (object.has("max_inclusive")) {
                    max = object.get("max_inclusive").getAsInt();
                }
            }
        }
        return new FormatRange(format, min, max);
    }

    /** 所有来源都能接受的区间；为空即失败。 */
    public static FormatRange intersect(List<FormatRange> ranges) {
        if (ranges.isEmpty()) {
            throw new MergeException("没有可用的 format 声明");
        }
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        for (FormatRange range : ranges) {
            min = Math.max(min, range.min());
            max = Math.min(max, range.max());
        }
        if (min > max) {
            StringBuilder detail = new StringBuilder();
            for (FormatRange range : ranges) {
                if (detail.length() > 0) {
                    detail.append("、");
                }
                detail.append(range.min()).append("-").append(range.max());
            }
            throw new MergeException("来源的 pack_format 没有交集：" + detail);
        }
        return new FormatRange(min, min, max);
    }

    public static JsonArray rangeArray(int min, int max) {
        JsonArray array = new JsonArray();
        array.add(min);
        array.add(max);
        return array;
    }
}
