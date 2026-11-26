package moe.nekomc.resourcepack.merge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 内置的 rps-pack：二进制随 jar 一起发布，首次启动时解到插件数据目录，避免部署时多传一个文件。
 *
 * <p>解包只比较内容，同尺寸同内容就跳过；升级 jar 后自动换成新版本。
 */
public final class BundledRust {

    static final String RESOURCE_DIR = "rps-pack/bin/";

    private BundledRust() {
    }

    /** 平台对应的资源名，例如 rps-pack-windows-x86_64.exe。 */
    public static String platformName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
        String suffix = family.equals("windows") ? ".exe" : "";
        return "rps-pack-" + family + "-" + cpu + suffix;
    }

    /** 解出内置二进制；jar 里没有当前平台的产物时返回 null。 */
    public static Path ensure(Path dataFolder, Logger logger) {
        return ensure(BundledRust.class.getClassLoader(), platformName(), dataFolder, logger);
    }

    static Path ensure(ClassLoader loader, String name, Path dataFolder, Logger logger) {
        String resource = RESOURCE_DIR + name;
        byte[] payload;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                String builtFor = builtPlatform(loader);
                if (builtFor != null) {
                    logger.warning("jar 内没有当前平台的合并器 " + name + "，这个 jar 是为 " + builtFor
                            + " 构建的；跨平台请用对应平台的发行包");
                }
                return null;
            }
            payload = in.readAllBytes();
        } catch (IOException error) {
            logger.warning("读取内置 " + resource + " 失败：" + error.getMessage());
            return null;
        }
        Path binDir = dataFolder.resolve("bin");
        Path target = binDir.resolve(name);
        try {
            if (Files.isRegularFile(target) && Files.size(target) == payload.length
                    && java.util.Arrays.equals(Files.readAllBytes(target), payload)) {
                makeExecutable(target);
                return target;
            }
            Files.createDirectories(binDir);
            Path temp = binDir.resolve(name + ".tmp");
            Files.write(temp, payload);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(target);
            logger.info("已解出内置合并器：" + target + "（" + payload.length + " 字节）");
            return target;
        } catch (IOException error) {
            logger.warning("解出内置合并器失败，本次只用 Java 合并：" + error.getMessage());
            return null;
        }
    }

    /** 构建期写进 jar 的平台标记（`rps-pack/bin/platform.txt`），老 jar 没有时返回 null。 */
    private static String builtPlatform(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE_DIR + "platform.txt")) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException error) {
            return null;
        }
    }

    private static void makeExecutable(Path file) {
        try {
            file.toFile().setExecutable(true, false);
        } catch (RuntimeException ignored) {
            // Windows 上不需要，失败也不影响 process start
        }
    }
}
