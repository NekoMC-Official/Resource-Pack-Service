package moe.nekomc.resourcepack.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import moe.nekomc.resourcepack.core.ZipSource;
import moe.nekomc.resourcepack.pack.EntryManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** rps-pack 的行为契约：层序、JSON 合并、去重、确定性、缺二进制时的报错。 */
class RustPackMergerTest {

    private static Path binary() {
        String override = System.getenv("RPS_PACK_BIN");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path windows = Path.of("build", "dist", "bin", "rps-pack.exe");
        Path unix = Path.of("build", "dist", "bin", "rps-pack");
        return Files.isRegularFile(windows) ? windows : unix;
    }

    private static RustPackMerger merger() {
        Path binary = binary();
        Assumptions.assumeTrue(Files.isRegularFile(binary), "缺少 rps-pack 可执行文件，跳过");
        return new RustPackMerger(binary, Path.of(".").toAbsolutePath(), 60_000, "override", true);
    }

    @Test
    void layersMergeWithHighPriorityWinningAndLangDeepMerged(@TempDir Path dir) throws IOException {
        RustPackMerger merger = merger();
        Path low = zip(dir.resolve("low.zip"), Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"low\"}}",
                "assets/minecraft/lang/zh_cn.json", "{\"a\":\"1\",\"b\":\"low\"}",
                "assets/low/only.txt", "low"));
        Path high = zip(dir.resolve("high.zip"), Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"high\"}}",
                "assets/minecraft/lang/zh_cn.json", "{\"a\":\"1\",\"c\":\"high\"}",
                "assets/high/only.txt", "high"));
        List<ZipSource> sources = List.of(
                new ZipSource("low", true, low, "", 0, List.of()),
                new ZipSource("high", true, high, "", 10, List.of()));

        Path out = dir.resolve("merged.zip");
        MergeResult result = merger.merge(sources, 46, "test pack", out);

        Map<String, String> entries = EntryManifest.scan(out);
        assertTrue(entries.containsKey("assets/low/only.txt"), "低层独有条目应保留");
        assertTrue(entries.containsKey("assets/high/only.txt"), "高层独有条目应保留");
        var lang = JsonParser.parseString(readEntry(out, "assets/minecraft/lang/zh_cn.json")).getAsJsonObject();
        assertEquals("low", lang.get("b").getAsString(), "低层独有键应保留");
        assertEquals("high", lang.get("c").getAsString(), "高层独有键应保留");
        var meta = JsonParser.parseString(readEntry(out, "pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
        assertEquals(46, meta.get("pack_format").getAsInt());
        assertEquals("test pack", meta.get("description").getAsString());
        assertEquals(46, result.formatMin());
        assertEquals(46, result.formatMax());
    }

    @Test
    void sameInputProducesSameSha1(@TempDir Path dir) throws IOException {
        RustPackMerger merger = merger();
        List<ZipSource> sources = List.of(
                new ZipSource("a", true, zip(dir.resolve("a.zip"), Map.of("assets/x.txt", "x")), "", 0, List.of()),
                new ZipSource("b", true, zip(dir.resolve("b.zip"), Map.of("assets/y.txt", "y")), "", 1, List.of()));
        String first = merger.merge(sources, 46, "d", dir.resolve("one.zip")).sha1();
        String second = merger.merge(sources, 46, "d", dir.resolve("two.zip")).sha1();
        assertEquals(first, second, "同输入两次合并必须同 sha1");
    }

    @Test
    void missingBinaryIsReportedClearly(@TempDir Path dir) {
        RustPackMerger merger = new RustPackMerger(
                dir.resolve("nope/rps-pack.exe"), dir, 5_000, "override", true);
        MergeUnavailableException error = assertThrows(MergeUnavailableException.class, () -> merger.merge(
                List.of(new ZipSource("a", true, zip(dir.resolve("a.zip"), Map.of("assets/x.txt", "x")), "", 0, List.of())),
                46, "d", dir.resolve("out.zip")));
        assertTrue(error.getMessage().contains("rps-pack"), error.getMessage());
    }

    private static String readEntry(Path zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("缺少条目 " + name);
    }

    private static Path zip(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream out = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
