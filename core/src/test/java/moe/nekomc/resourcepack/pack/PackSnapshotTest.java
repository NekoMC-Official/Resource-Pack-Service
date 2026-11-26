package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackSnapshotTest {

    @Test
    void failedKeepsPreviousBuildFields() {
        PackSnapshot previous = new PackSnapshot(
                "http://127.0.0.1:25580/pack/merged.zip?v=7",
                "0123456789abcdef0123456789abcdef01234567",
                7,
                4096L,
                42,
                "2026-11-25T04:00:00Z",
                "3 个来源 / 42 条",
                "");

        PackSnapshot failed = previous.failed("rust merge failed");

        assertEquals(previous.url(), failed.url());
        assertEquals(previous.sha1(), failed.sha1());
        assertEquals(previous.version(), failed.version());
        assertEquals(previous.bytes(), failed.bytes());
        assertEquals(previous.entries(), failed.entries());
        assertEquals(previous.builtAt(), failed.builtAt());
        assertEquals(previous.summary(), failed.summary());
        assertEquals("rust merge failed", failed.error());
        assertTrue(failed.ready());
    }

    @Test
    void failedOnEmptySnapshotStaysNotReady() {
        PackSnapshot failed = PackSnapshot.empty().failed("还没有登记任何来源");

        assertFalse(failed.ready());
        assertEquals(0, failed.version());
        assertEquals(0, failed.entries());
        assertEquals("还没有登记任何来源", failed.error());
    }
}
