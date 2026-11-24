package moe.nekomc.resourcepack.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config.yml 的不可变快照。 */
public record RpsConfig(
        String providerId,
        int providerPriority,
        boolean required,
        String prompt,
        String policy,
        boolean jsonMerge,
        int packFormat,
        long maxBytes,
        String description,
        Path outputDir,
        String publicUrl,
        boolean httpEnabled,
        int httpPort,
        List<ZipSource> sources,
        boolean watchEnabled,
        int watchSeconds,
        String pushMode,
        String notifyMessage) {

    public static RpsConfig load(FileConfiguration config, Path serverRoot) {
        ConfigurationSection provider = config.getConfigurationSection("provider");
        String providerId = provider == null ? "resourcepack-service/active" : provider.getString("id", "resourcepack-service/active");
        int providerPriority = provider == null ? 0 : provider.getInt("priority", 0);
        boolean required = provider != null && provider.getBoolean("required", false);
        String prompt = provider == null ? "" : provider.getString("prompt", "");

        String policy = config.getString("merge.policy", "override").toLowerCase(java.util.Locale.ROOT);
        boolean jsonMerge = config.getBoolean("merge.json-merge", true);
        String packFormatRaw = config.getString("merge.pack-format", "auto");
        int packFormat = parseFormat(packFormatRaw);
        long maxBytes = config.getLong("merge.max-bytes", 104_857_600L);
        String description = config.getString("merge.description", "NekoMC 统一资源包");

        Path outputDir = resolve(serverRoot, config.getString("output.dir", "merged"));
        String publicUrl = config.getString("output.public-url", "").trim();
        boolean httpEnabled = config.getBoolean("output.http.enabled", false);
        int httpPort = config.getInt("output.http.port", 25583);

        List<ZipSource> sources = new ArrayList<>();
        List<?> rawSources = config.getList("sources", List.of());
        for (Object raw : rawSources) {
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            Object idRaw = map.get("id");
            if (idRaw == null) {
                continue;
            }
            String id = String.valueOf(idRaw);
            boolean enabled = !(map.get("enabled") instanceof Boolean b) || b;
            String pathRaw = map.get("path") == null ? "" : String.valueOf(map.get("path"));
            String sha1 = map.get("sha1") == null ? null : String.valueOf(map.get("sha1"));
            int priority = map.get("priority") instanceof Number n ? n.intValue() : 0;
            List<String> namespaces = new ArrayList<>();
            if (map.get("namespaces") instanceof List<?> list) {
                for (Object ns : list) {
                    namespaces.add(String.valueOf(ns));
                }
            }
            sources.add(new ZipSource(id, enabled, resolve(serverRoot, pathRaw), sha1, priority, namespaces));
        }

        boolean watchEnabled = config.getBoolean("watch.enabled", true);
        int watchSeconds = Math.max(10, config.getInt("watch.interval-seconds", 60));
        String pushMode = config.getString("push.mode", "notify").toLowerCase(java.util.Locale.ROOT);
        String notifyMessage = config.getString("push.notify-message",
                "<yellow>资源包已更新，输入 <click:run_command:'/companion pack update'><white>/companion pack update</white></click> 或重新进服即可应用。");

        return new RpsConfig(providerId, providerPriority, required, prompt, policy, jsonMerge, packFormat,
                maxBytes, description, outputDir, publicUrl, httpEnabled, httpPort, List.copyOf(sources),
                watchEnabled, watchSeconds, pushMode, notifyMessage);
    }

    private static int parseFormat(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Path resolve(Path serverRoot, String raw) {
        if (raw == null || raw.isBlank()) {
            return serverRoot;
        }
        Path path = Path.of(raw);
        return path.isAbsolute() ? path.normalize() : serverRoot.resolve(path).normalize();
    }

    public String urlFor(int generation) {
        if (publicUrl.isBlank()) {
            return "";
        }
        String separator = publicUrl.contains("?") ? "&" : "?";
        return publicUrl + separator + "v=" + generation;
    }
}
