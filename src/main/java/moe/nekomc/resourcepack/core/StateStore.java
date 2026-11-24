package moe.nekomc.resourcepack.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 持久化世代号与上一版 sha1，保证版本单调递增。 */
public final class StateStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private int generation;
    private String lastSha1 = "";
    private long lastBuildAt;

    public StateStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized int generation() {
        return generation;
    }

    public synchronized String lastSha1() {
        return lastSha1;
    }

    public synchronized long lastBuildAt() {
        return lastBuildAt;
    }

    /** 提交一次成功构建，返回是否产生了新版本。 */
    public synchronized boolean commit(String sha1, long buildAt) throws IOException {
        lastBuildAt = buildAt;
        if (sha1 != null && !sha1.isBlank() && sha1.equals(lastSha1)) {
            save();
            return false;
        }
        generation = Math.max(generation + 1, 1);
        lastSha1 = sha1 == null ? "" : sha1;
        save();
        return true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            generation = json.has("generation") ? json.get("generation").getAsInt() : 0;
            lastSha1 = json.has("sha1") ? json.get("sha1").getAsString() : "";
            lastBuildAt = json.has("builtAt") ? json.get("builtAt").getAsLong() : 0L;
        } catch (RuntimeException | IOException ignored) {
            generation = 0;
            lastSha1 = "";
            lastBuildAt = 0L;
        }
    }

    private void save() throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("generation", generation);
        json.addProperty("sha1", lastSha1);
        json.addProperty("builtAt", lastBuildAt);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
