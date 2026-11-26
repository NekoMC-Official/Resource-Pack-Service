package moe.nekomc.resourcepack.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    @TempDir
    Path temp;

    @Test
    void commitIncrementsGenerationOnlyWhenShaChanges() {
        StateStore store = new StateStore(temp.resolve("state.json"));
        assertTrue(store.commit("aaa", "2026-11-27 02:00:00"));
        assertEquals(1, store.generation());
        assertFalse(store.commit("aaa", "2026-11-27 03:00:00"));
        assertEquals(1, store.generation());
        assertTrue(store.commit("bbb", "2026-11-27 04:00:00"));
        assertEquals(2, store.generation());
    }

    @Test
    void keepsBuildTimeWithSpacesAcrossReload() {
        Path file = temp.resolve("state.json");
        new StateStore(file).commit("abc", "2026-11-27 02:10:11");
        StateStore reloaded = new StateStore(file);
        assertEquals("abc", reloaded.lastSha1());
        assertEquals("2026-11-27 02:10:11", reloaded.lastBuildAt());
        assertEquals(1, reloaded.generation());
    }

    @Test
    void leavesNoTempFileBehind() throws IOException {
        Path file = temp.resolve("state.json");
        new StateStore(file).commit("abc", "x");
        try (var stream = Files.list(temp)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void toleratesBrokenFile() throws IOException {
        Path file = temp.resolve("state.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        StateStore store = new StateStore(file);
        assertEquals(0, store.generation());
        assertEquals("", store.lastSha1());
    }
}
