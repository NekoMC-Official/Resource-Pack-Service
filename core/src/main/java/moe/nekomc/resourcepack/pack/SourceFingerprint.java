package moe.nekomc.resourcepack.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 构建输入的指纹：来源元信息（含文件大小与修改时间）与影响产物内容的配置。
 *
 * <p>只用于判断"这一轮是否与上一轮完全相同"，不做内容哈希；文件内容变化只要改变大小或
 * 修改时间即可被发现。</p>
 */
public final class SourceFingerprint {

    private final MessageDigest digest;
    private boolean first = true;

    private SourceFingerprint(MessageDigest digest) {
        this.digest = digest;
    }

    public static SourceFingerprint create() {
        try {
            return new SourceFingerprint(MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 没有 SHA-256", exception);
        }
    }

    /** 追加一个字段；null 与空串都按"未设置"处理，字段之间用 0 字节分隔避免拼接歧义。 */
    public SourceFingerprint add(String value) {
        if (!first) {
            digest.update((byte) 0);
        }
        first = false;
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public String hex() {
        StringBuilder text = new StringBuilder(64);
        for (byte item : digest.digest()) {
            text.append(Character.forDigit((item >> 4) & 0xF, 16));
            text.append(Character.forDigit(item & 0xF, 16));
        }
        return text.toString();
    }
}
