package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import moe.nekomc.resourcepack.merge.MergeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictReportTest {

    @Test
    void roundTripsAndFiltersDefaultKinds(@TempDir Path dir) throws IOException {
        MergeResult.Conflict texture = new MergeResult.Conflict(
                "assets/minecraft/textures/block/stone.png", "high", List.of("low"),
                "texture", "high", "replace", 10, List.of(0),
                "a".repeat(40), List.of("b".repeat(40)), false, List.of());
        MergeResult.Conflict lang = new MergeResult.Conflict(
                "assets/minecraft/lang/zh_cn.json", "high", List.of("low"),
                "lang", "medium", "json-deep", 10, List.of(0),
                "c".repeat(40), List.of("d".repeat(40)), false, List.of("/b: low -> high"));
        MergeResult result = new MergeResult(
                dir.resolve("merged.zip"), "deadbeef", 120L, 4, 0, 46, 46, 46,
                List.of(), List.of(texture, lang),
                new MergeResult.ConflictSummary(2, 1, 1, 0, Map.of("texture", 1, "lang", 1), false),
                List.of(), 1000L, 3, 8L);
        PackSnapshot snapshot = new PackSnapshot(
                "http://127.0.0.1:8080/pack.zip", "deadbeef", 3, 120L,
                "2026-01-01 00:00:00", "ok", "");

        ConflictReport.write(dir, snapshot, result);
        StoredConflictReport report = ConflictReport.read(dir);

        assertNotNull(report);
        assertEquals("deadbeef", report.getSha1());
        assertEquals(3, report.getVersion());
        assertEquals(2, report.getSummary().getTotal());
        assertEquals(1, report.getSummary().getHigh());
        assertEquals("texture", report.getConflicts().get(0).getKind());
        assertEquals(List.of(0), report.getConflicts().get(0).getLoserPriorities());
        assertTrue(result.summary().contains("直拷 3"), "摘要应显示直拷条目数");
        assertTrue(result.summary().contains("体积 12%"), "摘要应显示相对原始体积的比例");
        assertEquals(1, ConflictReport.visible(report, false).size());
        assertEquals(2, ConflictReport.visible(report, true).size());
        assertTrue(ConflictReport.visible(report, false).stream()
                .allMatch(conflict -> conflict.getKind().equals("texture")));
        assertFalse(ConflictReport.visible(report, false).stream()
                .anyMatch(conflict -> conflict.getKind().equals("lang")));
    }

    @Test
    void missingOrBrokenReportIsIgnored(@TempDir Path dir) throws IOException {
        assertNull(ConflictReport.read(dir));
        Files.writeString(dir.resolve(ConflictReport.FILE_NAME), "{oops", StandardCharsets.UTF_8);
        assertNull(ConflictReport.read(dir));
    }
    @Test
    void clipsLongFieldsByCodePoint(@TempDir Path dir) throws IOException {
        String longField = "😀".repeat(130);
        MergeResult.Conflict conflict = new MergeResult.Conflict(
                "assets/minecraft/textures/block/stone.png", "high", List.of("low"),
                "texture", "high", "replace", 10, List.of(0),
                "a".repeat(40), List.of("b".repeat(40)), false, List.of(longField));
        MergeResult result = new MergeResult(
                dir.resolve("merged.zip"), "deadbeef", 120L, 1, 0, 64, 64, 64,
                List.of(), List.of(conflict),
                new MergeResult.ConflictSummary(1, 1, 0, 0, Map.of("texture", 1), false),
                List.of(), 0L, 0, 1L);
        PackSnapshot snapshot = new PackSnapshot(
                "http://127.0.0.1:8080/pack.zip", "deadbeef", 1, 120L,
                "2026-01-01 00:00:00", "ok", "");

        ConflictReport.write(dir, snapshot, result);
        StoredConflictReport report = ConflictReport.read(dir);

        assertNotNull(report);
        String stored = report.getConflicts().get(0).getFields().get(0);
        assertEquals(120, stored.codePointCount(0, stored.length()));
        assertTrue(stored.endsWith("…"));
    }

    @Test
    void marksTruncatedWhenDetailsExceedLimit(@TempDir Path dir) throws IOException {
        MergeResult.Conflict conflict = new MergeResult.Conflict(
                "assets/minecraft/textures/block/stone.png", "high", List.of("low"),
                "texture", "high", "replace", 10, List.of(0),
                "a".repeat(40), List.of("b".repeat(40)), false, List.of());
        MergeResult result = new MergeResult(
                dir.resolve("merged.zip"), "deadbeef", 120L, 1, 0, 64, 64, 64,
                List.of(), java.util.Collections.nCopies(5001, conflict),
                null, List.of(), 0L, 0, 1L);
        PackSnapshot snapshot = new PackSnapshot(
                "http://127.0.0.1:8080/pack.zip", "deadbeef", 1, 120L,
                "2026-01-01 00:00:00", "ok", "");

        ConflictReport.write(dir, snapshot, result);
        StoredConflictReport report = ConflictReport.read(dir);

        assertNotNull(report);
        assertEquals(5001, report.getSummary().getTotal());
        assertTrue(report.getSummary().getTruncated());
        assertEquals(ConflictReport.MAX_DETAILS, report.getConflicts().size());
    }

}