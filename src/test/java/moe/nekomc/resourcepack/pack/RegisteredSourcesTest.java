package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import moe.nekomc.resourcepack.api.PackSource;
import moe.nekomc.resourcepack.core.SourceRegistry;
import org.junit.jupiter.api.Test;

class RegisteredSourcesTest {

    private final SourceRegistry registry = new SourceRegistry();
    private final List<Boolean> rebuilds = new ArrayList<>();

    private RegisteredSources service() {
        return new RegisteredSources(() -> registry, new RegisteredSources.Host() {
            @Override
            public void rebuild(boolean announce) {
                rebuilds.add(announce);
            }

            @Override
            public RegisteredSources.Status status() {
                return new RegisteredSources.Status(7, "https://cdn.example.com/merged.zip?v=7", "abc");
            }

            @Override
            public int push(UUID player, boolean force) {
                return 1;
            }
        });
    }

    private static PackSource source(String id, String file, int priority) {
        Path path = Paths.get(file);
        return new PackSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Path file() {
                return path;
            }

            @Override
            public int priority() {
                return priority;
            }
        };
    }

    @Test
    void registerFeedsRegistryAndTriggersRebuild() {
        RegisteredSources service = service();
        assertTrue(service.register(source("yourplugin", "packs/a.zip", 10)));
        assertEquals(List.of("yourplugin"), registry.ordered().stream().map(s -> s.id()).toList());
        assertEquals(10, registry.get("yourplugin").orElseThrow().priority());
        assertEquals(List.of(false), rebuilds);
        assertEquals(7, service.version());
        assertEquals("abc", service.sha1());
        assertEquals(1, service.push(UUID.randomUUID(), false));
    }

    @Test
    void rejectsIncompleteSource() {
        RegisteredSources service = service();
        assertFalse(service.register(null));
        assertTrue(registry.all().isEmpty());
        assertTrue(rebuilds.isEmpty());
    }

    @Test
    void unregisterRemovesSource() {
        RegisteredSources service = service();
        service.register(source("a", "packs/a.zip", 0));
        assertTrue(service.unregister("a"));
        assertFalse(service.unregister("a"));
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void reapplyRestoresAfterReload() {
        RegisteredSources service = service();
        service.register(source("a", "packs/a.zip", 5));
        SourceRegistry fresh = new SourceRegistry();
        assertEquals(1, service.reapply(fresh));
        assertEquals(List.of("a"), fresh.ordered().stream().map(s -> s.id()).toList());
    }
}
