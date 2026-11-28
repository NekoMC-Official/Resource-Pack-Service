package moe.nekomc.resourcepack.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import moe.nekomc.resourcepack.core.Hashing;
import moe.nekomc.resourcepack.core.ZipSource;

/**
 * 调用 rust/rps-pack 的合并实现。进程间只走 JSON：请求进 stdin，报告出 stdout。
 */
public final class RustPackMerger implements Merger {

    private static final int STDOUT_LIMIT = 8 << 20;

    private final Path binary;
    private final Path workDir;
    private final int timeoutMs;
    private final String policy;
    private final boolean jsonMerge;

    public RustPackMerger(Path binary, Path workDir, int timeoutMs, String policy, boolean jsonMerge) {
        this.binary = binary;
        this.workDir = workDir;
        this.timeoutMs = timeoutMs;
        this.policy = policy;
        this.jsonMerge = jsonMerge;
    }

    @Override
    public MergeResult merge(List<ZipSource> sources, int formatOverride, String description, Path stagingFile)
            throws IOException {
        if (!Files.isRegularFile(binary)) {
            throw new MergeUnavailableException("找不到 rps-pack 可执行文件：" + binary);
        }
        Path output = stagingFile.toAbsolutePath();
        long started = System.nanoTime();
        JsonObject served = RustPackServe.call(
                binary,
                workDir,
                serveFrame(sources, formatOverride, description, stagingFile),
                timeoutMs);
        if (served != null) {
            return result(served.toString(), started, output);
        }
        Path errFile = output.resolveSibling(output.getFileName() + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "merge", "--request", "-", "--output", output.toString());
        builder.directory(workDir.toFile());
        builder.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new MergeUnavailableException("无法启动 rps-pack：" + error.getMessage(), error);
        }
        try (Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            stdin.write(request(sources, formatOverride, description).toString());
        } catch (IOException error) {
            process.destroyForcibly();
            throw new MergeUnavailableException("写入 rps-pack 请求失败：" + error.getMessage(), error);
        }
        // stdout 必须一边写一边读：遇到卡住的进程时也要能走到 waitFor 的超时分支。
        StringBuilder stdoutBuffer = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (Reader reader = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                String text = readLimited(reader);
                synchronized (stdoutBuffer) {
                    stdoutBuffer.append(text);
                }
            } catch (IOException ignored) {
                // 进程被强杀时读线程随之结束，退出码/超时由主线程报告。
            }
        }, "rps-pack-stdout");
        pump.setDaemon(true);
        pump.start();
        int exit;
        try {
            boolean finished;
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new MergeUnavailableException("等待 rps-pack 时被中断", error);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new MergeUnavailableException("rps-pack 超过 " + timeoutMs + "ms 未完成");
            }
            exit = process.exitValue();
            try {
                pump.join(2000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            String stdout;
            synchronized (stdoutBuffer) {
                stdout = stdoutBuffer.toString();
            }
            String stderr = stderrTail(errFile, stdout);
            if (exit != 0) {
                throw new MergeUnavailableException("rps-pack 退出码 " + exit + "：" + stderr);
            }
            return result(stdout, started, output);
        } finally {
            // 超时/中断/退出码失败都不该在 output 目录里留下 .stderr 垃圾
            deleteQuietly(errFile);
        }
    }

    private JsonObject request(List<ZipSource> sources, int formatOverride, String description) {
        JsonObject request = new JsonObject();
        request.addProperty("format", formatOverride);
        request.addProperty("description", description == null ? "" : description);
        request.addProperty("policy", policy == null ? "override" : policy);
        request.addProperty("json_merge", jsonMerge);
        JsonArray array = new JsonArray();
        for (ZipSource source : sources) {
            JsonObject item = new JsonObject();
            item.addProperty("id", source.id());
            item.addProperty("path", source.path().toAbsolutePath().toString());
            item.addProperty("enabled", source.enabled());
            item.addProperty("priority", source.priority());
            array.add(item);
        }
        request.add("sources", array);
        return request;
    }

    private JsonObject serveFrame(List<ZipSource> sources, int formatOverride, String description, Path stagingFile) {
        JsonObject frame = new JsonObject();
        frame.addProperty("output", stagingFile.toAbsolutePath().toString());
        frame.add("request", request(sources, formatOverride, description));
        return frame;
    }

    private MergeResult result(String stdout, long started, Path output) {
        JsonObject json;
        try {
            json = JsonParser.parseString(stdout.strip()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new MergeUnavailableException("rps-pack 输出不是合法 JSON：" + head(stdout), error);
        }
        if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
            throw new MergeUnavailableException("rps-pack 合并失败："
                    + (json.has("error") ? json.get("error").getAsString() : "未给出原因"));
        }
        List<MergeResult.Layer> layers = new ArrayList<>();
        if (json.has("sources")) {
            for (var element : json.getAsJsonArray("sources")) {
                JsonObject layer = element.getAsJsonObject();
                layers.add(new MergeResult.Layer(
                        layer.get("id").getAsString(),
                        layer.has("priority") ? layer.get("priority").getAsInt() : 0,
                        layer.has("format") ? layer.get("format").getAsInt() : 0,
                        layer.has("entries") ? layer.get("entries").getAsInt() : 0));
            }
        }
        List<MergeResult.Conflict> conflicts = new ArrayList<>();
        if (json.has("conflicts")) {
            for (var element : json.getAsJsonArray("conflicts")) {
                JsonObject conflict = element.getAsJsonObject();
                conflicts.add(new MergeResult.Conflict(
                        text(conflict, "path", ""),
                        text(conflict, "winner", ""),
                        strings(conflict, "losers"),
                        text(conflict, "kind", "other"),
                        text(conflict, "severity", "low"),
                        text(conflict, "mode", "replace"),
                        integer(conflict, "winnerPriority", 0),
                        integers(conflict, "loserPriorities"),
                        text(conflict, "winnerSha1", ""),
                        strings(conflict, "loserSha1s"),
                        bool(conflict, "samePriority", false),
                        strings(conflict, "fields")));
            }
        }
        MergeResult.ConflictSummary conflictSummary = conflictSummary(json, conflicts);
        List<String> warnings = new ArrayList<>();
        if (json.has("warnings")) {
            for (var element : json.getAsJsonArray("warnings")) {
                warnings.add(element.getAsString());
            }
        }
        String sha1 = sha1(json, output, warnings);
        long millis = json.has("millis") ? json.get("millis").getAsLong() : (System.nanoTime() - started) / 1_000_000L;
        if (!json.has("bytes") || !json.has("entries")) {
            throw new MergeUnavailableException("rps-pack 报告缺少 bytes/entries");
        }
        long bytes = json.get("bytes").getAsLong();
        int entries = json.get("entries").getAsInt();
        return new MergeResult(
                output,
                sha1,
        bytes,
        entries,
                json.has("deduped") ? json.get("deduped").getAsInt() : 0,
                integer(json, "format", 0),
                integer(json, "format_min", 0),
                integer(json, "format_max", 0),
                layers,
                conflicts,
                conflictSummary,
                warnings,
                json.has("rawBytes") ? json.get("rawBytes").getAsLong() : 0L,
                integer(json, "rawCopied", 0),
                millis);
    }

    private static String sha1(JsonObject json, Path output, List<String> warnings) {
        String reported = json.has("sha1") && json.get("sha1").isJsonPrimitive()
                ? json.get("sha1").getAsString()
                : "";
        if (reported.length() == 40
                && reported.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return reported;
        }
        warnings.add("rps-pack 返回的 sha1 不是 40 位小写十六进制，已按产物重新计算");
        try {
            return Hashing.sha1Hex(output);
        } catch (IOException error) {
            throw new IllegalStateException("无法按产物重新计算 SHA-1", error);
        }
    }

    private static MergeResult.ConflictSummary conflictSummary(
            JsonObject json, List<MergeResult.Conflict> conflicts) {
        if (json.has("conflictSummary") && json.get("conflictSummary").isJsonObject()) {
            JsonObject summary = json.getAsJsonObject("conflictSummary");
            Map<String, Integer> byKind = new LinkedHashMap<>();
            if (summary.has("byKind") && summary.get("byKind").isJsonObject()) {
                for (var entry : summary.getAsJsonObject("byKind").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        byKind.put(entry.getKey(), entry.getValue().getAsInt());
                    }
                }
            }
            return new MergeResult.ConflictSummary(
                    integer(summary, "total", conflicts.size()),
                    integer(summary, "high", 0),
                    integer(summary, "medium", 0),
                    integer(summary, "low", 0),
                    byKind,
                    bool(summary, "truncated", false));
        }
        Map<String, Integer> byKind = new LinkedHashMap<>();
        int high = 0;
        int medium = 0;
        int low = 0;
        for (MergeResult.Conflict conflict : conflicts) {
            switch (conflict.severity()) {
                case "high" -> high++;
                case "medium" -> medium++;
                default -> low++;
            }
            byKind.merge(conflict.kind(), 1, Integer::sum);
        }
        return new MergeResult.ConflictSummary(conflicts.size(), high, medium, low, byKind, false);
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        return integer(object.get(key), fallback);
    }

    private static int integer(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return values;
        }
        for (var element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static List<Integer> integers(JsonObject object, String key) {
        List<Integer> values = new ArrayList<>();
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return values;
        }
        for (var element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                try {
                    values.add(element.getAsInt());
                } catch (RuntimeException ignored) {
                    // 报告字段损坏时跳过该元素，不影响整次合并结果解析。
                }
            }
        }
        return values;
    }

    private static String readLimited(Reader reader) throws IOException {
        char[] buffer = new char[4096];
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        int read;
        while ((read = reader.read(buffer)) != -1) {
            if (text.length() + read > STDOUT_LIMIT) {
                truncated = true;
                break;
            }
            text.append(buffer, 0, read);
        }
        if (truncated) {
            throw new IOException("rps-pack 报告超过 " + STDOUT_LIMIT + " 字节，已拒绝解析");
        }
        return text.toString();
    }

    private static String stderrTail(Path file, String stdout) {
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!text.isEmpty()) {
                    return text.length() > 400 ? text.substring(text.length() - 400) : text;
                }
            }
        } catch (IOException ignored) {
            // 诊断信息拿不到就算了，不要把异常叠在异常上
        }
        return head(stdout);
    }

    private static String head(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() > 400 ? stripped.substring(0, 400) : stripped;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时诊断文件，删除失败不影响合并结果
        }
    }
}