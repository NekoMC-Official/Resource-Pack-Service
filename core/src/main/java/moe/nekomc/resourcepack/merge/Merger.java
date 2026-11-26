package moe.nekomc.resourcepack.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import moe.nekomc.resourcepack.core.ZipSource;

/**
 * 合并器接口。Java 实现始终可用，Rust 侧（rps-pack）作为同语义的第二实现，
 * 两者由 engine 配置选择，失败时回落到可用的那个。
 */
public interface Merger {

    MergeResult merge(List<ZipSource> sources, int formatOverride, String description, Path stagingFile)
            throws IOException;
}
