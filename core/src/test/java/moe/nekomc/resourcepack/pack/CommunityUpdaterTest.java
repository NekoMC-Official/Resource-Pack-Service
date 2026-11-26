package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityUpdaterTest {

    @TempDir
    Path directory;

    private HttpServer server;
    private String base;
    private byte[] zip;
    private String sha1;
    private final List<String> logs = new ArrayList<>();
    private final AtomicInteger indexRequests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        zip = zipOf("{\"pack\":{\"pack_format\":64}}");
        sha1 = sha1(zip);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/community-index.json", exchange -> {
            indexRequests.incrementAndGet();
            String etag = "\"v1\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = index(sha1).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", etag);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/objects/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/objects/" + sha1 + ".zip")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, zip.length);
            exchange.getResponseBody().write(zip);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String index(String sha1) {
        return indexOf(packJson("demo", "Demo", sha1, "objects/" + sha1 + ".zip", zip.length));
    }

    private static String indexOf(String... entries) {
        return """
            {
              "schema": 1,
              "generatedAt": "2026-11-25T00:00:00Z",
              "commit": "abc123",
              "packs": [
                %s
              ]
            }
            """.formatted(String.join(",\n", entries));
    }

    private static String packJson(String id, String plugin, String sha1, String file, int bytes) {
        return """
            {
              "id": "%s",
              "plugin": "%s",
              "relation": "official",
              "priority": 0,
              "also": [],
              "release": "auto",
              "version": "1.0.0",
              "minecraft": "",
              "requires": "",
              "variant": "",
              "default": true,
              "format_min": 64,
              "format_max": 64,
              "sha1": "%s",
              "file": "%s",
              "bytes": %d
            }
            """.formatted(id, plugin, sha1, file, bytes);
    }

    private static byte[] zipOf(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private CommunityUpdater updater(boolean https) {
        return updater(URI.create(base + "/community-index.json"), https, "Demo"::equals);
    }

    private CommunityUpdater updater(URI uri, boolean https, Predicate<String> installed) {
        return new CommunityUpdater(
            directory,
            uri,
            https,
            5000,
            1_000_000,
            "",
            new SelectionContext(installed, name -> "1.0.0", "26.3", 64),
            logs::add);
    }

    private void assertNoTemporaryDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            List<String> leftovers = entries
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(".community-"))
                .toList();
            assertTrue(leftovers.isEmpty(), "残留临时目录：" + leftovers);
        }
    }

    @Test
    void downloadsSelectedPackAndWritesManifest() throws Exception {
        CommunityUpdater updater = updater(false);
        RemoteUpdateResult first = updater.update(true);
        assertTrue(first.getChanged(), first.getMessage());
        assertTrue(first.getDownloaded() >= 1, first.getMessage());
        assertArrayEquals(zip, Files.readAllBytes(directory.resolve("demo.zip")));
        String manifest = Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"demo\""), manifest);
        assertNoTemporaryDirectories();

        RemoteUpdateResult second = updater.update(false);
        assertFalse(second.getChanged());
        assertTrue(second.getMessage().contains("没有变化"), second.getMessage());
    }

    @Test
    void rejectsPlainHttpWhenHttpsRequired() {
        RemoteUpdateResult result = updater(true).update(true);
        assertFalse(result.getChanged());
        assertTrue(result.getMessage().contains("HTTPS"), result.getMessage());
        assertEquals(0, indexRequests.get(), "HTTPS 预检失败时不能发出任何请求");
        assertFalse(Files.exists(directory.resolve("manifest.json")));
    }

    @Test
    void closePreventsFurtherRequests() {
        CommunityUpdater updater = updater(false);
        updater.close();

        RemoteUpdateResult result = updater.update(true);
        assertFalse(result.getChanged());
        assertTrue(result.getMessage().contains("关闭"), result.getMessage());
        assertEquals(0, indexRequests.get(), "close 后不能再发出请求");
        assertFalse(Files.exists(directory.resolve("manifest.json")));
    }

    @Test
    void secondPackFailureKeepsOldZipAndManifest() throws Exception {
        RemoteUpdateResult initial = updater(false).update(true);
        assertTrue(initial.getChanged(), initial.getMessage());
        byte[] oldZip = Files.readAllBytes(directory.resolve("demo.zip"));
        String oldManifest = Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8);

        byte[] newZip = zipOf("{\"pack\":{\"pack_format\":65}}");
        String newSha = sha1(newZip);
        String missingSha = "1".repeat(40);
        String newIndex = indexOf(
            packJson("demo", "Demo", newSha, "objects/" + newSha + ".zip", newZip.length),
            packJson("extra", "Extra", missingSha, "objects/" + missingSha + ".zip", 32));

        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/community-index.json", exchange -> {
            byte[] body = newIndex.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        broken.createContext("/objects/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/objects/" + newSha + ".zip")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, newZip.length);
            exchange.getResponseBody().write(newZip);
            exchange.close();
        });
        broken.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + broken.getAddress().getPort() + "/community-index.json");
            CommunityUpdater updater = updater(
                uri, false, name -> name.equals("Demo") || name.equals("Extra"));

            RemoteUpdateResult result = updater.update(true);
            assertFalse(result.getChanged(), result.getMessage());
            assertTrue(result.getMessage().contains("404"), result.getMessage());
            assertArrayEquals(oldZip, Files.readAllBytes(directory.resolve("demo.zip")));
            assertEquals(
                oldManifest,
                Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8));
            assertNoTemporaryDirectories();
        } finally {
            broken.stop(0);
        }
    }

    @Test
    void secondPackShaMismatchKeepsOldZipAndManifest() throws Exception {
        RemoteUpdateResult initial = updater(false).update(true);
        assertTrue(initial.getChanged(), initial.getMessage());
        byte[] oldZip = Files.readAllBytes(directory.resolve("demo.zip"));
        String oldManifest = Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8);

        byte[] newZip = zipOf("{\"pack\":{\"pack_format\":66}}");
        String newSha = sha1(newZip);
        String badSha = "2".repeat(40);
        String newIndex = indexOf(
            packJson("demo", "Demo", newSha, "objects/" + newSha + ".zip", newZip.length),
            packJson("extra", "Extra", badSha, "objects/" + badSha + ".zip", newZip.length));

        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/community-index.json", exchange -> {
            byte[] body = newIndex.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        broken.createContext("/objects/", exchange -> {
            exchange.sendResponseHeaders(200, newZip.length);
            exchange.getResponseBody().write(newZip);
            exchange.close();
        });
        broken.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + broken.getAddress().getPort() + "/community-index.json");
            CommunityUpdater updater = updater(
                uri, false, name -> name.equals("Demo") || name.equals("Extra"));

            RemoteUpdateResult result = updater.update(true);
            assertFalse(result.getChanged(), result.getMessage());
            assertTrue(result.getMessage().contains("sha1"), result.getMessage());
            assertArrayEquals(oldZip, Files.readAllBytes(directory.resolve("demo.zip")));
            assertEquals(
                oldManifest,
                Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8));
            assertNoTemporaryDirectories();
        } finally {
            broken.stop(0);
        }
    }

    @Test
    void shaMismatchKeepsOldManifest() throws Exception {
        String badSha = "0".repeat(40);
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/community-index.json", exchange -> {
            byte[] body = indexOf(
                packJson("demo", "Demo", badSha, "objects/" + badSha + ".zip", zip.length))
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        broken.createContext("/objects/", exchange -> {
            exchange.sendResponseHeaders(200, zip.length);
            exchange.getResponseBody().write(zip);
            exchange.close();
        });
        broken.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + broken.getAddress().getPort() + "/community-index.json");
            CommunityUpdater updater = updater(uri, false, "Demo"::equals);
            RemoteUpdateResult result = updater.update(true);
            assertFalse(result.getChanged());
            assertTrue(result.getMessage().contains("sha1"), result.getMessage());
            assertFalse(Files.exists(directory.resolve("demo.zip")));
            assertFalse(Files.exists(directory.resolve("manifest.json")));
            assertNoTemporaryDirectories();
        } finally {
            broken.stop(0);
        }
    }
}
