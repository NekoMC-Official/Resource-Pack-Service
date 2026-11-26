package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void servesZipWithEtag() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(BODY, response.body());
        assertEquals("\"" + SHA1 + "\"", response.headers().firstValue("ETag").orElse(""));
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(String.valueOf(BODY.length), response.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void answers304OnMatchingEtag() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("If-None-Match", "\"" + SHA1 + "\"")
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
}
