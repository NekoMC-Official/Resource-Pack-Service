package moe.nekomc.resourcepack.core;

/** 体检项：level 决定是提示、告警还是阻断构建。 */
public record Issue(Level level, String message) {

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    public static Issue info(String message) {
        return new Issue(Level.INFO, message);
    }

    public static Issue warn(String message) {
        return new Issue(Level.WARN, message);
    }

    public static Issue error(String message) {
        return new Issue(Level.ERROR, message);
    }

    public boolean blocking() {
        return level == Level.ERROR;
    }

    @Override
    public String toString() {
        return switch (level) {
            case INFO -> "[信息] " + message;
            case WARN -> "[告警] " + message;
            case ERROR -> "[错误] " + message;
        };
    }
}
