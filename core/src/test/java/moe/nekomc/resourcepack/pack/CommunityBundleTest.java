package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import moe.nekomc.resourcepack.config.CommunityBundle;
import moe.nekomc.resourcepack.core.ZipSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityBundleTest {

    @TempDir
    Path root;

    private void writeManifest(String json) throws Exception {
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private void touch(String id) throws Exception {
        Files.write(root.resolve(id + ".zip"), new byte[] {0});
    }

    @Test
    void loadsOnlyBoundPacksAndAppliesPriorityOffset() throws Exception {
        writeManifest("""
                {
                  "version": 1,
                  "packs": [
                    {"id": "companion-base", "plugin": "companion", "relation": "official", "priority": 5, "also": [], "release": "auto"},
                    {"id": "other-base", "plugin": "missing", "relation": "official", "priority": 1, "also": [], "release": "auto"},
                    {"id": "theme", "plugin": "universal", "relation": "standalone", "priority": 2, "also": [], "release": "auto"}
                  ],
                  "bundle": {"selected": ["companion-base", "other-base", "theme"]}
                }
                """);
        touch("companion-base");
        touch("other-base");
        touch("theme");

        List<String> logs = new ArrayList<>();
        CommunityBundle bundle = new CommunityBundle(root, 10, "companion"::equals, logs::add);
        List<ZipSource> sources = bundle.load();

        assertEquals(List.of("community/companion-base", "community/theme"), sources.stream().map(ZipSource::id).toList());
        assertEquals(15, sources.get(0).priority());
        assertEquals(12, sources.get(1).priority());
        assertTrue(logs.stream().anyMatch(line -> line.contains("other-base") && line.contains("未安装")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("纳入 2 个")));
    }

    @Test
    void missingZipIsSkippedWithReason() throws Exception {
        writeManifest("""
                {
                  "version": 1,
                  "packs": [
                    {"id": "companion-base", "plugin": "companion", "relation": "official", "priority": 0, "also": [], "release": "auto"}
                  ],
                  "bundle": {"selected": ["companion-base"]}
                }
                """);

        List<String> logs = new ArrayList<>();
        List<ZipSource> sources = new CommunityBundle(root, 0, "companion"::equals, logs::add).load();

        assertEquals(List.of(), sources);
        assertTrue(logs.stream().anyMatch(line -> line.contains("缺少 zip")));
    }

    @Test
    void malformedManifestIsIgnored() throws Exception {
        writeManifest("{not json");
        List<String> logs = new ArrayList<>();
        List<ZipSource> sources = new CommunityBundle(root, 0, plugin -> true, logs::add).load();

        assertEquals(List.of(), sources);
        assertTrue(logs.stream().anyMatch(line -> line.contains("解析失败")));
    }
}
