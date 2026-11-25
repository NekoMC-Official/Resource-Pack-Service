package moe.nekomc.resourcepack.api;

/**
 * 一次构建的不可变结果。
 *
 * @param ok         是否成功
 * @param sha1       成功时为 merged.zip 的 SHA-1；失败时保留上一次成功的值，可能为空串
 * @param version    成功时为本次世代号；失败时为上一次成功的世代号，可能为 0
 * @param url        成功时客户端实际可 GET 的完整地址；失败时保留上一次成功的地址
 * @param entryCount merged.zip 的条目数；失败时保留上一次成功构建的值，没有则为 0
 * @param bytes      merged.zip 的字节数；失败时保留上一次成功构建的值，没有则为 0
 * @param error      失败原因；成功时为 {@code null}
 */
public record BuildResult(
        boolean ok,
        String sha1,
        long version,
        String url,
        int entryCount,
        long bytes,
        String error) {

    public BuildResult {
        sha1 = sha1 == null ? "" : sha1;
        url = url == null ? "" : url;
        error = error == null || error.isBlank() ? null : error;
    }

    /** 尚未构建过、或发布器已被替换时的结果。 */
    public static BuildResult unavailable(String reason) {
        return new BuildResult(false, "", 0L, "", 0, 0L, reason);
    }
}
