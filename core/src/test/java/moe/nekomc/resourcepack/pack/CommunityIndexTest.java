package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommunityIndexTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    private static String index(String id, String sha1, String file) {
        return """
                {
                  "schema": 1,
                  "generatedAt": "2026-11-25T00:00:00Z",
                  "commit": "abc123",
                  "packs": [
                    {
                      "id": "%s",
                      "plugin": "Demo",
                      "relation": "official",
                      "priority": 10,
                      "also": [],
                      "release": "auto",
                      "version": "1.2.3",
                      "minecraft": ">=26.3 <26.4",
                      "requires": ">=1.0.0",
                      "variant": "",
                      "default": true,
                      "format_min": 64,
                      "format_max": 64,
                      "sha1": "%s",
                      "file": "%s",
                      "bytes": 123
                    }
                  ]
                }
                """.formatted(id, sha1, file);
    }

    @Test
    void parsesOnePack() {
        CommunityIndex parsed = CommunityIndex.parse(index("demo", SHA1, "objects/" + SHA1 + ".zip"));
        assertNotNull(parsed);
        assertEquals("abc123", parsed.getCommit());
        assertEquals(1, parsed.getPacks().size());
        IndexedPack entry = parsed.getPacks().get(0);
        assertEquals("demo", entry.getPack().id());
        assertEquals("Demo", entry.getPack().plugin());
        assertEquals("official", entry.getPack().relation());
        assertEquals(10, entry.getPack().priority());
        assertEquals("1.2.3", entry.getPack().version());
        assertEquals(">=26.3 <26.4", entry.getPack().minecraft());
        assertEquals(">=1.0.0", entry.getPack().requires());
        assertTrue(entry.getPack().isDefault());
        assertEquals(64, entry.getPack().formatMin());
        assertEquals(64, entry.getPack().formatMax());
        assertEquals(SHA1, entry.getSha1());
        assertEquals("objects/" + SHA1 + ".zip", entry.getFile());
        assertEquals(123L, entry.getBytes());
    }

    @Test
    void rejectsPathTraversal() {
        assertNull(CommunityIndex.parse(index("demo", SHA1, "objects/../demo.zip")));
        assertNull(CommunityIndex.parse(index("demo", SHA1, "/objects/demo.zip")));
        assertNull(CommunityIndex.parse(index("demo", SHA1, "objects\\demo.zip")));
    }

    @Test
    void rejectsBadSha1AndSchema() {
        assertNull(CommunityIndex.parse(index("demo", "xyz", "objects/x.zip")));
        assertNull(CommunityIndex.parse(index("demo", SHA1, "objects/x.zip").replace("\"schema\": 1", "\"schema\": 2")));
    }

    @Test
    void rejectsDuplicateIds() {
        String one = index("demo", SHA1, "objects/" + SHA1 + ".zip");
        String body = one.substring(one.indexOf('[') + 1, one.lastIndexOf(']'));
        String doubled = one.replace("[" + body + "]", "[" + body + "," + body + "]");
        assertNull(CommunityIndex.parse(doubled));
    }

    @Test
    void manifestRoundTripsFields() {
        CommunityIndex parsed = CommunityIndex.parse(index("demo", SHA1, "objects/" + SHA1 + ".zip"));
        assertNotNull(parsed);
        String manifest = CommunityIndex.manifest(parsed.getPacks());
        assertTrue(manifest.contains("\"version\": 1"), manifest);
        assertTrue(manifest.contains("\"id\": \"demo\""), manifest);
        assertTrue(manifest.contains("\"format_min\": 64"), manifest);
        assertFalse(manifest.contains("objects/"), "manifest 不应写下载路径");
    }
}
