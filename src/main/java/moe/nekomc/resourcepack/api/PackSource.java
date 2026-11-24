package moe.nekomc.resourcepack.api;

import java.nio.file.Path;
import java.util.List;

/**
 * 本服务的资源包来源标准。
 *
 * <p>任何插件都可以实现这个接口，把自己产出的 zip 交给 Resource Pack Service 统一合并与下发，
 * 不需要关心服务器最终用哪个插件推送。实现类应是轻量的值对象：内容变化时重新
 * {@link ResourcePackService#register(PackSource) register} 一次即可。</p>
 *
 * <p>合并层的规则：{@link #priority()} 越大越靠上层，同名条目由上层覆盖下层；
 * {@code lang/*.json}、{@code sounds.json}、{@code font/}、{@code atlases/} 按 key 深合并；
 * 各来源的 {@code pack.mcmeta} 会被丢弃，由本服务按所有来源的格式交集重建。</p>
 */
public interface PackSource {

    /** 全局唯一标识，建议用插件名小写；与配置文件里的来源同 id 时以注册的为准。 */
    String id();

    /** 本地 zip 文件；不存在时本次构建会失败并保留上一版合成包。 */
    Path file();

    /** 层序，越大越靠上层。 */
    int priority();

    /** 为 false 时保留登记但不参与合并。 */
    default boolean enabled() {
        return true;
    }

    /** 声明的 sha1，可为 null；非 null 时必须与文件实际 sha1 一致。 */
    default String sha1() {
        return null;
    }

    /** 占用到的 assets 命名空间，仅用于报告。 */
    default List<String> namespaces() {
        return List.of();
    }
}
