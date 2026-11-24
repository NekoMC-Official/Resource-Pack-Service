package moe.nekomc.resourcepack.merge;

/** 合并阶段的确定性失败：保留上一版输出，不产生半成品。 */
public class MergeException extends RuntimeException {

    public MergeException(String message) {
        super(message);
    }

    public MergeException(String message, Throwable cause) {
        super(message, cause);
    }
}
