package moe.nekomc.resourcepack.pack;

import java.util.List;

/**
 * 社区资源包清单里的一个包。
 *
 * <p>字段直接对应 {@code manifest.json} 的 packs 数组，只用于服务端判断绑定关系；
 * 不参与客户端资源包内容。</p>
 *
 * <p>{@code isDefault} 对应清单里的 {@code default} 字段。Java 不能把组件命名为
 * {@code default}（保留字），所以这里用 {@code isDefault}。</p>
 */
public record CommunityPack(
        String id,
        String plugin,
        String relation,
        int priority,
        List<String> also,
        String release,
        String version,
        String minecraft,
        String requires,
        String variant,
        boolean isDefault,
        int formatMin,
        int formatMax
) {

    /** 兼容旧清单：没有运行兼容字段时按“不限制”处理。 */
    public CommunityPack(String id, String plugin, String relation, int priority, List<String> also, String release) {
        this(id, plugin, relation, priority, also, release, "", "", "", "", false, 0, 0);
    }

    public CommunityPack {
        id = id == null ? "" : id;
        plugin = plugin == null ? "" : plugin;
        relation = relation == null ? "" : relation;
        also = also == null ? List.of() : List.copyOf(also);
        release = release == null ? "" : release;
        version = version == null ? "" : version;
        minecraft = minecraft == null ? "" : minecraft;
        requires = requires == null ? "" : requires;
        variant = variant == null ? "" : variant;
        formatMin = Math.max(0, formatMin);
        formatMax = Math.max(0, formatMax);
        if (formatMin > 0 && formatMax > 0 && formatMax < formatMin) {
            int swap = formatMin;
            formatMin = formatMax;
            formatMax = swap;
        }
    }
}
