package moe.nekomc.resourcepack.fabric;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内核用的是 {@link java.util.logging.Logger}（Paper 侧直接落到服务器日志），
 * Fabric 侧默认的 JUL 控制台处理器只写 stdout，不会进 logs/latest.log。
 * 这里把 JUL 记录转交 slf4j，让两边的日志落点一致。
 */
public final class FabricLog {

    private FabricLog() {
    }

    public static Logger get(String name) {
        Logger logger = Logger.getLogger(name);
        org.slf4j.Logger delegate = LoggerFactory.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null) {
                    return;
                }
                String message = record.getMessage();
                Throwable thrown = record.getThrown();
                Level level = record.getLevel();
                if (level.intValue() >= Level.SEVERE.intValue()) {
                    delegate.error(message, thrown);
                } else if (level.intValue() >= Level.WARNING.intValue()) {
                    delegate.warn(message, thrown);
                } else if (level.intValue() >= Level.INFO.intValue()) {
                    delegate.info(message, thrown);
                } else {
                    delegate.debug(message, thrown);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
