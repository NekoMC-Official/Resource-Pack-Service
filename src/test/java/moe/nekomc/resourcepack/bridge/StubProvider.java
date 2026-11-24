package moe.nekomc.resourcepack.bridge;

/** 测试用的 PackProvider 形状：七个取值方法加一个未来可能新增的方法。 */
public interface StubProvider {

    String id();

    int version();

    String url();

    default String sha1() {
        return "";
    }

    default int priority() {
        return 0;
    }

    default boolean required() {
        return false;
    }

    default String prompt() {
        return "";
    }

    default String unknownFutureMethod() {
        return "fallback";
    }
}
