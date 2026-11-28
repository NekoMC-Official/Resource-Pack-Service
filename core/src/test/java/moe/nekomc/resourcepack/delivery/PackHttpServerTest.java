package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackHttpServerTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final byte[] BODY = "PK 假装的资源包".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private PackHttpServer server;
    private HttpClient client;
    private String url;

    @BeforeEach
    void startServer() throws Exception {
        Path zip = tempDir.resolve("merged.zip");
        Files.write(zip, BODY);
        server = new PackHttpServer(zip, "127.0.0.1", 0, "/pack/merged.zip");
        server.start();
        url = "http://127.0.0.1:" + server.port() + "/pack/merged.zip";
        server.publish(url, SHA1);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
    @Test
    void servesZipWithEtag() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(BODY, response.body());
        assertEquals("\"" + sha1Hex(BODY) + "\"", response.headers().firstValue("ETag").orElse(""));
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(String.valueOf(BODY.length), response.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void answers304OnMatchingEtag() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("If-None-Match", "\"" + sha1Hex(BODY) + "\"")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void answers404ForOtherPath() throws Exception {
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/other.zip")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, response.statusCode());
    }

    @Test
    void headKeepsHeadersWithoutBody() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD",
                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals(String.valueOf(BODY.length), response.headers().firstValue("Content-Length").orElse(""));
        assertEquals(0, response.body().length);
    }

    @Test
    void rejectsPost() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, response.statusCode());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void boundPortIsReported() {
        assertTrue(server.port() > 0, "port=0 时应回报实际端口");
        assertTrue(server.running());
    }

    @Test
    void servesWhenEgressLimitIsEnabled() throws Exception {
        Path zip = tempDir.resolve("limited.zip");
        Files.write(zip, BODY);
        PackHttpServer limited = new PackHttpServer(zip, "127.0.0.1", 0, "/pack/merged.zip", 4, 4096L * 1024);
        limited.start();
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + limited.port() + "/pack/merged.zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(BODY, response.body());
        } finally {
            limited.close();
        }
    }

    @Test
    void egressLimitPacesTheBody() throws Exception {
        Path zip = tempDir.resolve("paced.zip");
        byte[] payload = new byte[96 * 1024];
        new java.util.Random(11).nextBytes(payload);
        Files.write(zip, payload);
        PackHttpServer limited = new PackHttpServer(zip, "127.0.0.1", 0, "/pack/merged.zip", 2, 128L * 1024);
        limited.start();
        try {
            long started = System.nanoTime();
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + limited.port() + "/pack/merged.zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(200, response.statusCode());
            assertArrayEquals(payload, response.body());
            // 96 KiB 走 128 KB/s 至少 700 ms；留足余量只校验「确实被限速」。
            assertTrue(elapsedMillis >= 400L, "限速后传输应明显变慢，实际 " + elapsedMillis + "ms");
        } finally {
            limited.close();
        }
    }

    @Test
    void etagFollowsThePublishedFile() throws Exception {
        String first = client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD",
                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray())
                .headers().firstValue("ETag").orElse("");
        assertEquals("\"" + sha1Hex(BODY) + "\"", first);
        byte[] replacement = "新包".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("merged.zip"), replacement);
        HttpResponse<byte[]> stale = client.send(HttpRequest.newBuilder(URI.create(url))
                .header("If-None-Match", first).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, stale.statusCode(), "文件换了就不能再回 304");
        assertArrayEquals(replacement, stale.body());
    }

    @Test
    void answers503WhenConcurrentSlotsAreExhausted() throws Exception {
        Path big = tempDir.resolve("big.zip");
        byte[] payload = new byte[8 * 1024 * 1024];
        new java.util.Random(7).nextBytes(payload);
        Files.write(big, payload);
        PackHttpServer limited = new PackHttpServer(big, "127.0.0.1", 0, "/pack/merged.zip", 1, 0L);
        limited.start();
        try (Socket first = new Socket()) {
            first.setReceiveBufferSize(16 * 1024);
            first.connect(new InetSocketAddress("127.0.0.1", limited.port()), 5000);
            OutputStream request = first.getOutputStream();
            request.write(("GET /pack/merged.zip HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            request.flush();
            InputStream response = first.getInputStream();
            // 只读一点点就不再消费：服务端会被发送缓冲挡住，正好把唯一的槽位占住。
            response.read(new byte[1024]);
            Thread.sleep(200);
            HttpResponse<byte[]> rejected = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + limited.port() + "/pack/merged.zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(503, rejected.statusCode());
            assertEquals("1", rejected.headers().firstValue("Retry-After").orElse(""));
        }
        // 客户端断开后槽位必须回收，否则一次超时就能把出口永久堵死。
        long deadline = System.currentTimeMillis() + 5000L;
        HttpResponse<byte[]> recovered = null;
        while (System.currentTimeMillis() < deadline) {
            recovered = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + limited.port() + "/pack/merged.zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (recovered.statusCode() == 200) {
                break;
            }
            Thread.sleep(50L);
        }
        assertEquals(200, recovered == null ? 0 : recovered.statusCode(), "断开后槽位应释放");
        limited.close();
    }
}
