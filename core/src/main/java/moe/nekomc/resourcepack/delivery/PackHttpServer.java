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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 只提供固定一个文件的 HTTP 出口（JDK 内置 HttpServer）。
 *
 * <p>没有路由、没有目录、没有参数：请求路径必须与构造时给的路径完全一致，其余一律 404，
 * 因此天然不存在路径穿越。带 ETag / If-None-Match，同一版本重复请求只花一个 304。</p>
 */
public final class PackHttpServer implements AutoCloseable {

    private static final String THREAD_PREFIX = "rps-http-";
    private static final Logger LOGGER = Logger.getLogger("rps-http");
    private static final int DEFAULT_MAX_CONCURRENT = 64;
    // 每个在途下载占一个线程；上限压住配置误填，避免一次开出几千个线程。
    private static final int MAX_SLOTS = 256;
    private static final int MAX_POOL_THREADS = MAX_SLOTS + 8;
    private static final int MIN_POOL_THREADS = 8;
    private static final int TRANSFER_CHUNK = 64 * 1024;
    private static final long SLOT_WAIT_MILLIS = 100L;
    // 线程忙时最多排这么多连接；再多说明已经过载，直接拒绝，不再无限堆任务。
    private static final int QUEUE_CAPACITY = 512;

    private final Path zipFile;
    private final String bind;
    private final int configuredPort;
    private final String urlPath;
    private final int threads;
    private final Semaphore slots;
    private final TokenBucket egress;
    private final AtomicLong rejected = new AtomicLong();
    // 同一个文件的摘要只算一次：并发冷启动不会各自把整包读一遍。
    private final ConcurrentHashMap<String, CompletableFuture<String>> etagComputing = new ConcurrentHashMap<>();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int actualPort = -1;
    private volatile EtagEntry etagEntry = new EtagEntry("", "");
    private volatile String publishedSha1 = "";
    private volatile String lastUrl = "";

    /** 摘要缓存的键值对：一起替换，读者不会看到「新 key + 旧 value」。 */
    private record EtagEntry(String key, String value) {
    }

    public PackHttpServer(Path zipFile, String bind, int port, String urlPath) {
        this(zipFile, bind, port, urlPath, DEFAULT_MAX_CONCURRENT, 0L);
    }

    /**
     * @param maxConcurrent 同时在途的下载上限，占满时新请求直接 503
     * @param egressBytesPerSecond 全局限速（字节/秒），0 表示不限
     */
    public PackHttpServer(
            Path zipFile, String bind, int port, String urlPath, int maxConcurrent, long egressBytesPerSecond) {
        this.zipFile = zipFile;
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
        // 端口合法性留到 start 里检查：那里能把失败统一转成 IOException，由调用方降级而不是让插件半死。
        this.configuredPort = port;
        String path = urlPath == null || urlPath.isBlank() ? "/pack/merged.zip" : urlPath.trim();
        this.urlPath = path.startsWith("/") ? path : "/" + path;
        int window = Math.max(1, Math.min(maxConcurrent, MAX_SLOTS));
        this.slots = new Semaphore(window, true);
        // 线程要比槽位多几条，否则槽位占满时没人能接住新连接、也就发不出 503。
        this.threads = Math.min(MAX_POOL_THREADS, Math.max(MIN_POOL_THREADS, window + MIN_POOL_THREADS));
        this.egress = new TokenBucket(egressBytesPerSecond);
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
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                factory,
                (task, owner) -> {
                    rejected.incrementAndGet();
                    LOGGER.warning("HTTP 请求队列已满，拒绝新连接；累计 " + rejected.get() + " 次");
                });
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
            String current = etagFor(file);
            if (!current.isEmpty()) {
                exchange.getResponseHeaders().set("ETag", current);
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && matchesEtag(ifNoneMatch, current)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            // 并发窗口兜底：拿不到槽位直接 503，让客户端稍后重试，避免线程池与出口带宽一起被拖死。
            if (!acquireSlot()) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            try {
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
                            int want = (int) Math.min((long) TRANSFER_CHUNK, size - position);
                            egress.acquire(want);
                            long moved;
                            try {
                                moved = channel.transferTo(position, want, target);
                            } catch (IOException error) {
                                // transferTo 抛错时不知道已写出多少，按整块退：最多多退一个块，
                                // 方向偏保守（放宽限速），不会超出端口带宽。
                                egress.refund(want);
                                throw error;
                            }
                            if (moved <= 0) {
                                egress.refund(want);
                                LOGGER.fine("客户端提前关闭，剩余 " + (size - position) + " 字节未发送");
                                break;
                            }
                            egress.refund(want - (int) moved);
                            position += moved;
                        }
                    }
                }
            } finally {
                slots.release();
            }
        }
    }

    private boolean acquireSlot() {
        try {
            return slots.tryAcquire(SLOT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 全局限速：按字节预约发送时刻，锁内只记账、锁外睡，避免把所有 HTTP 线程串成一条。 */
    private static final class TokenBucket {

        private final double bytesPerSecond;
        private long nextFreeNanos = System.nanoTime();

        TokenBucket(long bytesPerSecond) {
            this.bytesPerSecond = Math.max(0L, bytesPerSecond);
        }

        void acquire(int bytes) {
            if (bytesPerSecond <= 0 || bytes <= 0) {
                return;
            }
            long waitNanos;
            synchronized (this) {
                long now = System.nanoTime();
                if (nextFreeNanos < now) {
                    nextFreeNanos = now;
                }
                waitNanos = nextFreeNanos - now;
                nextFreeNanos += (long) Math.ceil(bytes / bytesPerSecond * 1_000_000_000.0);
            }
            sleep(waitNanos);
        }

        /** 实际只传出更少字节时退回额度，避免一个早断的连接让后面的人白等。 */
        void refund(int bytes) {
            if (bytesPerSecond <= 0 || bytes <= 0) {
                return;
            }
            synchronized (this) {
                long now = System.nanoTime();
                nextFreeNanos = Math.max(now, nextFreeNanos - (long) Math.ceil(bytes / bytesPerSecond * 1_000_000_000.0));
            }
        }

        private static void sleep(long waitNanos) {
            if (waitNanos <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 按大小与修改时间缓存摘要，避免每次请求都把整包回读一遍。 */
    private String etagFor(Path file) throws IOException {
        String key;
        try {
            key = file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException error) {
            return "";
        }
        EtagEntry cached = etagEntry;
        if (cached.key().equals(key)) {
            return cached.value();
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = etagComputing.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.get(30L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception error) {
                return "";
            }
        }
        try {
            String digest = sha1Of(file);
            String value = digest.isEmpty() ? "" : Sha1Hex.quote(digest);
            if (!value.isEmpty()) {
                // 读失败时不要把空摘要写进缓存，否则这个版本会一直不发 ETag。
                this.etagEntry = new EtagEntry(key, value);
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException error) {
            mine.completeExceptionally(error);
            throw error;
        } finally {
            etagComputing.remove(key, mine);
        }
    }

    /** 按 RFC 9110 逐个比对 If-None-Match：支持逗号分隔、W/ 前缀与通配符 *。 */
    private static boolean matchesEtag(String header, String etag) {
        if (etag.isEmpty()) {
            return false;
        }
        for (String part : header.split(",")) {
            String value = part.trim();
            if (value.equals("*")) {
                return true;
            }
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (value.equals(etag)) {
                return true;
            }
        }
        return false;
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
        // 摘要还会给状态页看；ETag 交给 etagFor 按文件实际内容算。
        this.publishedSha1 = sha1 == null ? "" : sha1;
        this.etagEntry = new EtagEntry("", "");
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

    /** 最近一次发布的摘要；状态页用来对照。 */
    public String sha1() {
        return publishedSha1;
    }

    /** 最近一次发布的下载地址，仅用于状态显示。 */
    public String lastUrl() {
        return lastUrl;
    }

    public boolean running() {
        return server != null;
    }

    /** 因线程池队列已满被拒绝的连接数。 */
    public long rejectedRequests() {
        return rejected.get();
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
