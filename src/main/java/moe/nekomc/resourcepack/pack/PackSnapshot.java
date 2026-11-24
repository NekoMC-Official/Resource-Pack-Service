package moe.nekomc.resourcepack.pack;

/**
 * 一次合成包构建结果的快照，供状态展示和推送读取。
 *
 * <p>跨语言 DTO 用 Java record：Kotlin 发布器负责构造，Java 侧（插件入口、推送器）只读。</p>
 */
public record PackSnapshot(
        String url,
        String sha1,
        int version,
        long bytes,
        String builtAt,
        String summary,
        String error) {

    /** 还没构建过的空快照。 */
    public static PackSnapshot empty() {
        return new PackSnapshot("", "", 0, 0L, "", "", "");
    }

    /** 有可下发的地址与摘要。 */
    public boolean ready() {
        return url != null && !url.isBlank() && sha1 != null && !sha1.isBlank();
    }
}
