package moe.nekomc.resourcepack.merge;

import java.nio.file.Path;
import java.util.List;

/** 一次合并的结果摘要。 */
public record MergeResult(
        Path file,
        String sha1,
        long bytes,
        int entries,
        int deduped,
        int format,
        int formatMin,
        int formatMax,
        List<Layer> layers,
        List<Conflict> conflicts,
        List<String> warnings,
        long millis) {

    public record Layer(String id, int priority, int format, int entries) {
    }

    public record Conflict(String path, String winner, List<String> losers) {
    }

    public String summary() {
        return "条目 " + entries + " / 去重 " + deduped + " / 冲突 " + conflicts.size()
                + " / 格式 " + format + "（" + formatMin + "-" + formatMax + "）"
                + " / " + bytes + " 字节 / " + millis + "ms";
    }
}
