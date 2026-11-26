package moe.nekomc.resourcepack.platform;

/**
 * 定时/异步调度端口。
 *
 * <p>Paper 侧接 Folia 的 asyncScheduler，Fabric 侧接服务端 tick 计数；内核只按毫秒描述
 * “多久之后、隔多久跑一次”，由实现决定落在哪个线程。</p>
 */
public interface Scheduler {

    /** 立刻在异步线程跑一次。 */
    Cancellable runAsyncNow(Runnable task);

    /** 延迟 delayMillis 后开始，每 periodMillis 跑一次。 */
    Cancellable runAsyncTimer(long delayMillis, long periodMillis, Runnable task);

    /** 可取消句柄。 */
    interface Cancellable {
        void cancel();
    }
}
