package moe.nekomc.resourcepack.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只提供固定一个文件的 HTTP 出口（JDK 内置 HttpServer）。
 *
 * <p>没有路由、没有目录、没有参数：请求路径必须与构造时给的路径完全一致，其余一律 404，
 * 因此天然不存在路径穿越。带 ETag / If-None-Match，同一版本重复请求只花一个 304。</p>
 */
public final class PackHttpServer implements AutoCloseable {

    private static final String THREAD_PREFIX = "rps-http-";

    private final Path zipFile;
    private final String bind;
    private final int configuredPort;
    private final String urlPath;

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int actualPort = -1;
    private volatile String etag = "";
    private volatile String lastUrl = "";

    public PackHttpServer(Path zipFile, String bind, int port, String urlPath) {
        this.zipFile = zipFile;
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
        // 端口合法性留到 start 里检查：那里能把失败统一转成 IOException，由调用方降级而不是让插件半死。
        this.configuredPort = port;
        String path = urlPath == null || urlPath.isBlank() ? "/pack/merged.zip" : urlPath.trim();
        this.urlPath = path.startsWith("/") ? path : "/" + path;
    }

    /** 绑定端口并开始服务；port 传 0 时由系统分配，实际端口用 {@link #port()} 取。 */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        if (configuredPort < 0 || configuredPort > 65535) {
            throw new IOException("delivery.http.port 超出范围：" + configuredPort);
        }
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_PREFIX + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService pool = Executors.newFixedThreadPool(8, factory);
        HttpServer created = null;
        try {
            created = HttpServer.create(new InetSocketAddress(bind, configuredPort), 0);
            created.setExecutor(pool);
            created.createContext("/", this::handle);
            created.start();
        } catch (IOException error) {
            if (created != null) {
                created.stop(0);
            }
            pool.shutdownNow();
            throw error;
        } catch (RuntimeException error) {
            if (created != null) {
                created.stop(0);
            }
            pool.shutdownNow();
            throw new IOException("HTTP 出口启动失败：" + error.getMessage(), error);
        }
        this.server = created;
        this.executor = pool;
        this.actualPort = created.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!urlPath.equals(rawPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Path file = zipFile;
            if (file == null || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String current = etag;
            if (current.isEmpty()) {
                current = Sha1Hex.quote(sha1Of(file));
                etag = current;
            }
            exchange.getResponseHeaders().set("ETag", current);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && !ifNoneMatch.isBlank() && ifNoneMatch.contains(current)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            // 用同一个句柄取长度并输出：合成换包时也不会把新文件多写或少写到已经声明的长度之外。
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                long size = channel.size();
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                if ("HEAD".equals(method)) {
                    exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, size);
                try (OutputStream out = exchange.getResponseBody()) {
                    WritableByteChannel target = Channels.newChannel(out);
                    long position = 0;
                    while (position < size) {
                        long moved = channel.transferTo(position, size - position, target);
                        if (moved <= 0) {
                            break;
                        }
                        position += moved;
                    }
                }
            }
        }
    }

    private static String sha1Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(40);
            for (byte value : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "";
        }
    }

    /** 发布新版本时更新 ETag；url 只用于状态显示。 */
    public void publish(String url, String sha1) {
        this.lastUrl = url == null ? "" : url;
        this.etag = Sha1Hex.quote(sha1);
    }

    /** 实际监听的端口；未启动时为 -1。 */
    public int port() {
        return actualPort;
    }

    /** 配置里的路径，例如 /pack/merged.zip。 */
    public String urlPath() {
        return urlPath;
    }

    /** 配置里的绑定地址。 */
    public String bind() {
        return bind;
    }

    /** 最近一次发布的下载地址，仅用于状态显示。 */
    public String lastUrl() {
        return lastUrl;
    }

    public boolean running() {
        return server != null;
    }

    @Override
    public synchronized void close() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        actualPort = -1;
    }
}
