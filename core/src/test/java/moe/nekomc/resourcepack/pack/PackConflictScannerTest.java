package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackConflictScannerTest {

    @TempDir
    Path temp;

    private Path write(String relative, String content) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void findsPackUrlInPluginConfig() throws IOException {
        write("plugins/Foo/config.yml", "settings:\n  resource-pack: \"https://cdn.example.com/foo.zip\"\n");
        List<PackConflictScanner.Finding> findings = PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 10);
        assertEquals(1, findings.size());
        PackConflictScanner.Finding finding = findings.get(0);
        assertEquals("Foo", finding.plugin());
        assertEquals(2, finding.line());
        assertEquals("resource-pack", finding.key());
        assertTrue(finding.value().startsWith("https://"));
    }

    @Test
    void ignoresDisabledAndUnrelatedKeys() throws IOException {
        write("plugins/Bar/config.yml",
                "resource-pack: \"\"\nsend-on-join: false\nview-distance: 12\nresource-pack-id: \"\"\n");
        assertTrue(PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 10).isEmpty());
    }

    @Test
    void flagsBooleanToggle() throws IOException {
        write("plugins/Baz/settings.yml", "resource_pack: true\n");
        assertEquals(1, PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 10).size());
    }

    @Test
    void looksOneLevelDeeper() throws IOException {
        write("plugins/Qux/config/config.yml", "pack-url: https://example.com/q.zip\n");
        assertEquals(1, PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 10).size());
    }

    @Test
    void skipsOwnFolderAndComments() throws IOException {
        write("plugins/ResourcePackService/config.yml", "resource-pack: https://example.com/self.zip\n");
        write("plugins/Ok/config.yml", "# resource-pack: https://example.com/old.zip\n");
        assertTrue(PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 10).isEmpty());
    }

    @Test
    void respectsLimit() throws IOException {
        write("plugins/A/config.yml", "resource-pack: https://a/1.zip\nresource-pack: https://a/2.zip\n");
        write("plugins/B/config.yml", "resource-pack: https://b/1.zip\n");
        assertEquals(2, PackConflictScanner.scan(temp.resolve("plugins"), "ResourcePackService", 2).size());
    }

    @Test
    void findsTomlInFabricConfigDir() throws IOException {
        write("config/somemod.toml", "[general]\nresource_pack = \"https://cdn.example.com/some.zip\"\n");
        List<PackConflictScanner.Finding> findings =
                PackConflictScanner.scanRoots(List.of(temp.resolve("config")), "resource-pack-service", 10);
        assertEquals(1, findings.size());
        assertEquals("somemod", findings.get(0).plugin());
        assertEquals(2, findings.get(0).line());
    }

    @Test
    void findsNestedTomlUnderConfigDir() throws IOException {
        write("config/othermod/settings.toml", "server-resource-pack = \"https://example.com/x.zip\"\n");
        assertEquals(1,
                PackConflictScanner.scanRoots(List.of(temp.resolve("config")), "resource-pack-service", 10).size());
    }

    @Test
    void readsLooseYamlAtRoot() throws IOException {
        write("config/legacy.yml", "pack-url: https://example.com/l.zip\n");
        assertEquals(1,
                PackConflictScanner.scanRoots(List.of(temp.resolve("config")), "resource-pack-service", 10).size());
    }

    @Test
    void skipsOwnFolderInConfigRoot() throws IOException {
        write("config/resource-pack-service/config.yml", "resource-pack: https://example.com/self.zip\n");
        assertTrue(PackConflictScanner.scanRoots(List.of(temp.resolve("config")), "resource-pack-service", 10).isEmpty());
    }
}
