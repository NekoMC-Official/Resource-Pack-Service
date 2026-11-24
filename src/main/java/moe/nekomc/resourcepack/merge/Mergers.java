package moe.nekomc.resourcepack.merge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import moe.nekomc.resourcepack.core.EngineConfig;
import moe.nekomc.resourcepack.core.RpsConfig;

/**
 * 合并实现只有 Rust 一个：计算全部交给 rps-pack。
 *
 * <p>二进制优先用配置指定的位置，找不到就解 jar 内置的那份；两者都没有时直接报错，
 * 让构建失败信息里写清楚缺什么，而不是悄悄退回一个更慢的实现。
 */
public final class Mergers {

    private Mergers() {
    }

    public static Merger rust(RpsConfig config, EngineConfig engine, Path dataFolder, Logger logger) {
        Path binary = engine.rustBinary();
        if (!Files.isRegularFile(binary)) {
            Path bundled = BundledRust.ensure(dataFolder, logger);
            if (bundled == null) {
                throw new MergeUnavailableException("找不到 rps-pack：配置路径 " + binary
                        + " 不存在，jar 内也没有 " + BundledRust.platformName()
                        + "。请确认服务器架构与本机构建产物一致，或在 merge.rust.binary 指定可执行文件。");
            }
            binary = bundled;
        }
        return new RustPackMerger(binary, dataFolder, engine.rustTimeoutMs(), config.policy(), config.jsonMerge());
    }

    public static String label(Merger merger) {
        return merger instanceof RustPackMerger ? "rust" : merger.getClass().getSimpleName();
    }
}
