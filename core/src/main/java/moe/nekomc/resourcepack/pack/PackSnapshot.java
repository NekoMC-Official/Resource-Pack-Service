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
        int entries,
        String builtAt,
        String summary,
        String error) {

    /**
     * 兼容旧调用点的构造器：条目数未知时按 0 处理。
     */
    public PackSnapshot(
            String url,
            String sha1,
            int version,
            long bytes,
            String builtAt,
            String summary,
            String error) {
        this(url, sha1, version, bytes, 0, builtAt, summary, error);
    }

    /** 还没构建过的空快照。 */
    public static PackSnapshot empty() {
        return new PackSnapshot("", "", 0, 0L, 0, "", "", "");
    }

    /**
     * 构建失败时的新快照：保留下载地址、摘要、版本与条目数，只替换错误信息。
     *
     * <p>失败不会清空上一版，客户端仍可继续下载旧包。</p>
     */
    public PackSnapshot failed(String detail) {
        return new PackSnapshot(url, sha1, version, bytes, entries, builtAt, summary, detail);
    }

    /** 有可下发的地址与摘要。 */
    public boolean ready() {
        return url != null && !url.isBlank() && sha1 != null && !sha1.isBlank();
    }
}
