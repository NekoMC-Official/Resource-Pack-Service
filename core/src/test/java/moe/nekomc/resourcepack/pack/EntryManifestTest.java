package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryManifestTest {

    @Test
    void rotateKeepsPreviousForDiff(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), EntryManifest.report(dir, 10), "两份清单都不存在时无差异可报");
        EntryManifest.rotateAndWrite(dir, zip(dir.resolve("v1.zip"), Map.of("a.txt", "1", "b.txt", "2")));
        EntryManifest.rotateAndWrite(dir, zip(dir.resolve("v2.zip"), Map.of("a.txt", "1x", "c.txt", "3")));
        List<String> lines = EntryManifest.report(dir, 10);
        assertTrue(lines.getFirst().contains("新增 1"), lines.getFirst());
        assertTrue(lines.getFirst().contains("变更 1"), lines.getFirst());
        assertTrue(lines.getFirst().contains("删除 1"), lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> l.equals("~ a.txt")), "应报告 a.txt 变更");
        assertTrue(lines.stream().anyMatch(l -> l.equals("+ c.txt")), "应报告 c.txt 新增");
        assertTrue(lines.stream().anyMatch(l -> l.equals("- b.txt")), "应报告 b.txt 删除");
    }

    @Test
    void sameContentProducesNoChanges(@TempDir Path dir) throws IOException {
        EntryManifest.rotateAndWrite(dir, zip(dir.resolve("v1.zip"), Map.of("k.txt", "same")));
        EntryManifest.rotateAndWrite(dir, zip(dir.resolve("v2.zip"), Map.of("k.txt", "same")));
        assertEquals("新增 0 · 变更 0 · 删除 0 （清单 1 条）", EntryManifest.report(dir, 10).getFirst());
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
