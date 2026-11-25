package moe.nekomc.resourcepack.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildResultTest {

    @Test
    void normalizesNullAndBlankFields() {
        BuildResult result = new BuildResult(true, null, 3L, null, 12, 2048L, "  ");

        assertEquals("", result.sha1());
        assertEquals("", result.url());
        assertNull(result.error());
        assertTrue(result.ok());
    }

    @Test
    void unavailableIsNeverOk() {
        BuildResult result = BuildResult.unavailable("发布器已被替换");

        assertFalse(result.ok());
        assertEquals(0L, result.version());
        assertEquals(0, result.entryCount());
        assertEquals("发布器已被替换", result.error());
    }
}
