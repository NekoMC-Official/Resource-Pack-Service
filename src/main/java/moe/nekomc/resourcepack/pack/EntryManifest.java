package moe.nekomc.resourcepack.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import moe.nekomc.resourcepack.core.Hashing;

/**
 * 合成包条目清单：记录每个条目的 sha1，用来回答"这次构建到底改了什么"。
 *
 * <p>协议层没有增量下载，能省的只有"没变就不重下"；清单是给服主看运营代价用的。
 */
public final class EntryManifest {

    public static final String CURRENT = "entries.txt";
    public static final String PREVIOUS = "entries.prev.txt";

    private EntryManifest() {
    }

    public static void rotateAndWrite(Path directory, Path zip) throws IOException {
        Map<String, String> entries = scan(zip);
        Path current = directory.resolve(CURRENT);
        Path previous = directory.resolve(PREVIOUS);
        if (Files.isRegularFile(current)) {
            Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        StringBuilder text = new StringBuilder();
        entries.forEach((path, sha1) -> text.append(sha1).append(' ').append(path).append('\n'));
        Files.writeString(current, text.toString(), StandardCharsets.UTF_8);
    }

    public static Map<String, String> scan(Path zip) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream raw = Files.newInputStream(zip);
                ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), Hashing.sha1Hex(in.readAllBytes()));
            }
        }
        return entries;
    }

    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int space = line.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            entries.put(line.substring(space + 1), line.substring(0, space));
        }
        return entries;
    }

    /** 上一版 → 当前版的差异，按新增/删除/变更三组输出，最多 limit 行。 */
    public static List<String> report(Path directory, int limit) throws IOException {
        Map<String, String> before = read(directory.resolve(PREVIOUS));
        Map<String, String> after = read(directory.resolve(CURRENT));
        if (before.isEmpty() && after.isEmpty()) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String old = before.get(entry.getKey());
            if (old == null) {
                added.add("+ " + entry.getKey());
            } else if (!old.equals(entry.getValue())) {
                changed.add("~ " + entry.getKey());
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                removed.add("- " + path);
            }
        }
        List<String> all = new ArrayList<>();
        all.add("新增 " + added.size() + " · 变更 " + changed.size() + " · 删除 " + removed.size()
                + " （清单 " + after.size() + " 条）");
        all.addAll(changed);
        all.addAll(added);
        all.addAll(removed);
        return all.size() > limit + 1 ? new ArrayList<>(all.subList(0, limit + 1)) : all;
    }
}
