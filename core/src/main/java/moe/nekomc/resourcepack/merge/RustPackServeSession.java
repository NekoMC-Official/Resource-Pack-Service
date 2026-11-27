package moe.nekomc.resourcepack.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 rps-pack 的一个会话：stdin/stdout 上收发 u32 小端长度前缀的 JSON 帧。
 *
 * <p>一次启动可连续服务多次合并，省掉每次约 13–16 ms 的进程创建开销。协议错误、读线程断开或响应超时都会
 * 把会话标记为不可用，由调用方回退到一次性进程。
 */
final class RustPackServeSession implements AutoCloseable {

    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private static final JsonObject CLOSED = new JsonObject();

    private final Process process;
    private final BufferedOutputStream requests;
    private final BlockingQueue<JsonObject> responses = new ArrayBlockingQueue<>(4);
    private final Thread reader;
    private volatile boolean broken;

    static RustPackServeSession start(Path binary, Path workDir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "serve", "--idle-seconds", "300");
        builder.directory(workDir.toFile());
        builder.redirectError(ProcessBuilder.Redirect.appendTo(workDir.resolve("rps-pack-serve.log").toFile()));
        RustPackServeSession session = new RustPackServeSession(builder.start());
        session.reader.start();
        return session;
    }

    private RustPackServeSession(Process process) {
        this.process = process;
        this.requests = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
        this.reader = new Thread(this::pump, "rps-pack-serve-stdout");
        this.reader.setDaemon(true);
    }

    synchronized JsonObject call(JsonObject frame, int timeoutMs) throws IOException {
        if (broken) {
            throw new IOException("合并器会话已断开");
        }
        byte[] payload = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("请求帧过大：" + payload.length);
        }
        try {
            writeLength(payload.length);
            requests.write(payload);
            requests.flush();
        } catch (IOException error) {
            broken = true;
            throw error;
        }
        JsonObject response;
        try {
            response = responses.poll(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 rps-pack 响应时被中断", error);
        }
        if (response == null) {
            throw new IOException("rps-pack 超过 " + timeoutMs + "ms 未响应");
        }
        if (response == CLOSED) {
            throw new IOException("合并器进程已断开");
        }
        return response;
    }

    private void writeLength(int length) throws IOException {
        requests.write(length & 0xFF);
        requests.write((length >>> 8) & 0xFF);
        requests.write((length >>> 16) & 0xFF);
        requests.write((length >>> 24) & 0xFF);
    }

    private void pump() {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(process.getInputStream(), 64 * 1024))) {
            while (true) {
                int length = readLength(input);
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("响应帧长度异常：" + length);
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    throw new EOFException("响应帧不完整");
                }
                JsonObject response = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                responses.put(response);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            broken = true;
        } catch (IOException | RuntimeException error) {
            broken = true;
        } finally {
            responses.offer(CLOSED);
        }
    }

    private static int readLength(DataInputStream input) throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("读取响应长度失败");
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    @Override
    public void close() {
        broken = true;
        try {
            requests.close();
        } catch (IOException ignored) {
            // 关闭失败时直接强杀，进程退出本身由下面的 destroy 兜底。
        }
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
