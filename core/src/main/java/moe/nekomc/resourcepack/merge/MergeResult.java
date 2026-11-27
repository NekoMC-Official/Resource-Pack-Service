package moe.nekomc.resourcepack.merge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        ConflictSummary conflictSummary,
        List<String> warnings,
        int rawCopied,
        long millis) {

    public record Layer(String id, int priority, int format, int entries) {
    }

    /** 同路径冲突：保留旧字段，同时带上类型、严重度与两侧指纹。 */
    public record Conflict(
            String path,
            String winner,
            List<String> losers,
            String kind,
            String severity,
            String mode,
            int winnerPriority,
            List<Integer> loserPriorities,
            String winnerSha1,
            List<String> loserSha1s,
            boolean samePriority,
            List<String> fields) {

        public Conflict {
            path = path == null ? "" : path;
            winner = winner == null ? "" : winner;
            losers = losers == null ? List.of() : List.copyOf(losers);
            kind = kind == null ? "other" : kind;
            severity = severity == null ? "low" : severity;
            mode = mode == null ? "replace" : mode;
            loserPriorities = loserPriorities == null ? List.of() : List.copyOf(loserPriorities);
            winnerSha1 = winnerSha1 == null ? "" : winnerSha1;
            loserSha1s = loserSha1s == null ? List.of() : List.copyOf(loserSha1s);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record ConflictSummary(
            int total,
            int high,
            int medium,
            int low,
            Map<String, Integer> byKind,
            boolean truncated) {

        public ConflictSummary {
            byKind = byKind == null ? Map.of() : Map.copyOf(byKind);
        }
    }

    public String summary() {
        int total = conflictSummary == null ? conflicts.size() : conflictSummary.total();
        int high = conflictSummary == null ? 0 : conflictSummary.high();
        return "条目 " + entries + " / 去重 " + deduped + " / 冲突 " + total
                + "（高 " + high + "）"
                + " / 直拷 " + rawCopied
                + " / 格式 " + format + "（" + formatMin + "-" + formatMax + "）"
                + " / " + bytes + " 字节 / " + millis + "ms";
    }
}
