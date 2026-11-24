package moe.nekomc.resourcepack.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描 plugins/ 下的配置文件，找出"自己也会下发资源包"的插件。
 *
 * <p>没有 Bukkit 事件能观察别人调用 {@code Player#setResourcePack}，所以只能在配置层找线索：
 * 资源包地址、进服发送开关一类的键。结果只是提示，不是判定——真正的证据仍然是
 * 宿主槽位的归属（见 {@code /rps status} 的"下发宿主生效"）。
 */
public final class PackConflictScanner {

    /** 一条可疑配置。 */
    public record Finding(String plugin, Path file, int line, String key, String value) {
    }

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^(?:-\\s*)?([A-Za-z0-9_.-]*(?:resource[-_]?pack|pack[-_]?url|send[-_]?on[-_]?join|pack[-_]?send)[A-Za-z0-9_.-]*)\\s*[:=]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final String[] CONFIG_NAMES = {"config.yml", "config.yaml", "settings.yml", "settings.yaml"};
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final int MAX_FILES = 400;

    private PackConflictScanner() {
    }

    public static List<Finding> scan(Path pluginsDir, String ownFolder, int limit) {
        if (pluginsDir == null || limit <= 0 || !Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        List<Path> pluginDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (ownFolder != null && child.getFileName().toString().equalsIgnoreCase(ownFolder)) {
                    continue;
                }
                pluginDirs.add(child);
            }
        } catch (IOException ignored) {
            return List.of();
        }
        pluginDirs.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));

        List<Finding> findings = new ArrayList<>();
        int visited = 0;
        for (Path dir : pluginDirs) {
            for (Path file : candidates(dir)) {
                if (visited++ >= MAX_FILES) {
                    return findings;
                }
                scanFile(dir.getFileName().toString(), file, limit, findings);
                if (findings.size() >= limit) {
                    return findings;
                }
            }
        }
        return findings;
    }

    private static List<Path> candidates(Path dir) {
        List<Path> files = new ArrayList<>();
        for (String name : CONFIG_NAMES) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                for (String name : CONFIG_NAMES) {
                    Path file = child.resolve(name);
                    if (Files.isRegularFile(file)) {
                        files.add(file);
                    }
                }
            }
        } catch (IOException ignored) {
            // 子目录不可读就只看本级
        }
        return files;
    }

    private static void scanFile(String plugin, Path file, int limit, List<Finding> out) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String raw = lines.get(index).strip();
                if (raw.isEmpty() || raw.startsWith("#")) {
                    continue;
                }
                Matcher matcher = ASSIGNMENT.matcher(raw);
                if (!matcher.matches()) {
                    continue;
                }
                String value = clean(matcher.group(2));
                if (!suspicious(value)) {
                    continue;
                }
                out.add(new Finding(plugin, file, index + 1, matcher.group(1), abbreviate(value)));
                if (out.size() >= limit) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // 读不了就跳过，体检不该因为别人的坏文件失败
        }
    }

    private static String clean(String raw) {
        String value = raw.strip();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment).strip();
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1).strip();
            }
        }
        return value;
    }

    private static boolean suspicious(String value) {
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("false") || lower.equals("none") || lower.equals("null") || lower.equals("~")) {
            return false;
        }
        if (lower.contains("http://") || lower.contains("https://")) {
            return true;
        }
        return lower.equals("true");
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }
}
