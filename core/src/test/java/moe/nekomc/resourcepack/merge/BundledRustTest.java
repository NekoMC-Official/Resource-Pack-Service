package moe.nekomc.resourcepack.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledRustTest {

    private static final Logger LOGGER = Logger.getLogger("BundledRustTest");

    @Test
    void extractsEmbeddedBinary(@TempDir Path dir) throws IOException {
        Path extracted = BundledRust.ensure(BundledRustTest.class.getClassLoader(),
                "rps-pack-windows-x86_64.exe", dir, LOGGER);
        assertNotNull(extracted);
        assertEquals("fake-binary-v1", Files.readString(extracted));
        assertTrue(Files.isRegularFile(extracted));
    }

    @Test
    void secondCallKeepsFileAndIsIdempotent(@TempDir Path dir) throws IOException {
        Path first = BundledRust.ensure(BundledRustTest.class.getClassLoader(),
                "rps-pack-windows-x86_64.exe", dir, LOGGER);
        long stamp = Files.getLastModifiedTime(first).toMillis();
        Path second = BundledRust.ensure(BundledRustTest.class.getClassLoader(),
                "rps-pack-windows-x86_64.exe", dir, LOGGER);
        assertEquals(first, second);
        assertEquals(stamp, Files.getLastModifiedTime(second).toMillis(), "内容相同时不应重写");
    }

    @Test
    void missingPlatformReturnsNull(@TempDir Path dir) {
        assertNull(BundledRust.ensure(BundledRustTest.class.getClassLoader(),
                "rps-pack-plan9-x86_64", dir, LOGGER));
    }

    @Test
    void platformNameLooksSane() {
        String name = BundledRust.platformName();
        assertTrue(name.startsWith("rps-pack-"), name);
        assertTrue(name.contains("x86_64") || name.contains("aarch64"), name);
    }
}
