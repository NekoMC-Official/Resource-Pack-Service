package moe.nekomc.resourcepack.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个待合并的资源包来源。
 *
 * @param id         来源标识，重复注册同 id 视为覆盖
 * @param enabled    是否参与合并
 * @param path       本地 zip 文件
 * @param sha1       声明值，可空；非空则必须与文件实际 sha1 一致
 * @param priority   层序，小的先叠，大的后叠（后叠覆盖先叠）
 * @param namespaces 该来源占用的 assets 命名空间，仅用于报告
 */
public record ZipSource(String id, boolean enabled, Path path, String sha1, int priority, List<String> namespaces) {

    public ZipSource {
        namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
    }

    public static ZipSource of(String id, Path path, int priority) {
        return new ZipSource(id, true, path, null, priority, List.of());
    }
}
