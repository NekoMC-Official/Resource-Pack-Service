package moe.nekomc.resourcepack.merge;

import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import moe.nekomc.resourcepack.core.ZipSource;

/**
 * 调用 rust/rps-pack 的合并实现。进程间只走 JSON：请求进 stdin，报告出 stdout。
 */
public final class RustPackMerger implements Merger {

    private static final int STDOUT_LIMIT = 1 << 20;

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
        Path errFile = output.resolveSibling(output.getFileName() + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "merge", "--request", "-", "--output", output.toString());
        builder.directory(workDir.toFile());
        builder.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));
        long started = System.nanoTime();
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
        String stdout;
        try (Reader reader = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            stdout = readLimited(reader);
        } catch (IOException error) {
            process.destroyForcibly();
            throw new MergeUnavailableException("读取 rps-pack 输出失败：" + error.getMessage(), error);
        }
        int exit;
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new MergeUnavailableException("rps-pack 超过 " + timeoutMs + "ms 未完成");
            }
            exit = process.exitValue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MergeUnavailableException("等待 rps-pack 时被中断", error);
        } finally {
            deleteQuietly(errFile);
        }
        if (exit != 0) {
            throw new MergeUnavailableException("rps-pack 退出码 " + exit + "：" + stderrTail(errFile, stdout));
        }
        return result(stdout, started, output);
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
            item.addProperty("priority", source.priority());
            array.add(item);
        }
        request.add("sources", array);
        return request;
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
                List<String> losers = new ArrayList<>();
                if (conflict.has("losers")) {
                    for (var loser : conflict.getAsJsonArray("losers")) {
                        losers.add(loser.getAsString());
                    }
                }
                conflicts.add(new MergeResult.Conflict(
                        conflict.get("path").getAsString(), conflict.get("winner").getAsString(), losers));
            }
        }
        List<String> warnings = new ArrayList<>();
        if (json.has("warnings")) {
            for (var element : json.getAsJsonArray("warnings")) {
                warnings.add(element.getAsString());
            }
        }
        long millis = json.has("millis") ? json.get("millis").getAsLong() : (System.nanoTime() - started) / 1_000_000L;
        return new MergeResult(
                output,
                json.get("sha1").getAsString(),
                json.get("bytes").getAsLong(),
                json.get("entries").getAsInt(),
                json.has("deduped") ? json.get("deduped").getAsInt() : 0,
                json.has("format") ? json.get("format").getAsInt() : 0,
                json.has("format_min") ? json.get("format_min").getAsInt() : 0,
                json.has("format_max") ? json.get("format_max").getAsInt() : 0,
                layers,
                conflicts,
                warnings,
                millis);
    }

    private static String readLimited(Reader reader) throws IOException {
        char[] buffer = new char[4096];
        StringBuilder text = new StringBuilder();
        int read;
        while ((read = reader.read(buffer)) != -1) {
            if (text.length() < STDOUT_LIMIT) {
                text.append(buffer, 0, read);
            }
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
