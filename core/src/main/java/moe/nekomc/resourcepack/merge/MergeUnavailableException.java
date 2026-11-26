package moe.nekomc.resourcepack.merge;

/** 某个合并实现当前不可用（二进制缺失、超时、协议不符）。 */
public final class MergeUnavailableException extends RuntimeException {

    public MergeUnavailableException(String message) {
        super(message);
    }

    public MergeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
