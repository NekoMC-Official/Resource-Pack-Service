package moe.nekomc.resourcepack.core;

import java.nio.file.Files;
import java.nio.file.Path;
import moe.nekomc.resourcepack.platform.ConfigSource;

/**
 * 合并引擎配置。计算只有 Rust 一个实现，这里负责二进制位置与超时。
 */
public record EngineConfig(Path rustBinary, int rustTimeoutMs) {

    public static EngineConfig load(ConfigSource config, Path dataFolder) {
        String binary = config.getString("merge.rust.binary", "bin/rps-pack").trim();
        Path resolved = Path.of(binary);
        if (!resolved.isAbsolute()) {
            resolved = dataFolder.resolve(binary);
        }
        if (Files.isDirectory(resolved) || !resolved.getFileName().toString().contains(".")) {
            Path exe = resolved.resolveSibling(resolved.getFileName() + ".exe");
            if (Files.isRegularFile(exe) || !Files.isRegularFile(resolved)) {
                resolved = exe;
            }
        }
        int timeout = Math.max(5_000, config.getInt("merge.rust.timeout-ms", 60_000));
        return new EngineConfig(resolved, timeout);
    }
}
