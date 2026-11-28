package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.platform.Scheduler;
import org.junit.jupiter.api.Test;

/** 覆盖审计提出的生命周期与记账路径：迟到回执、退服、超时、抖动阻塞、降窗。 */
class FanoutControllerLifecycleTest {

    private static final Logger LOGGER = Logger.getLogger("fanout-test");

    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakeDelivery delivery = new FakeDelivery();
    private final AtomicLong clock = new AtomicLong(1_000L);

    private FanoutController controller(int maxConcurrent, int jitterMs, int retryLimit, boolean ramp) {
        return new FanoutController(
                delivery,
                scheduler,
                LOGGER,
                maxConcurrent,
                jitterMs,
                retryLimit,
                ramp,
                5_000L,
                120_000L,
                clock::get);
    }

    @Test
    void staleReceiptDoesNotReleaseSlot() {
        FanoutController controller = controller(64, 0, 1, false);
        UUID player = UUID.randomUUID();
        controller.enqueue(List.of(player), "new", false, clock.get());
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight());

        controller.onStatus(player, PackStatus.LOADED, "old", clock.get());
        assertEquals(1, controller.stats().getInflight(), "迟到回执不得清掉新代的槽位");
        assertEquals(1L, controller.stats().getDropped());
        assertEquals(0L, controller.stats().getCompleted(), "旧代回执不能算新代完成");
        assertEquals(0L, controller.stats().getRetried(), "迟到回执不得触发重试");
        scheduler.tick();
        assertEquals(1, delivery.pushed.size(), "迟到回执不应产生新推送");

        controller.onStatus(player, PackStatus.LOADED, "new", clock.get());
        assertEquals(0, controller.stats().getInflight(), "新代回执才释放槽位");
        assertEquals(1L, controller.stats().getCompleted());
        controller.close();
    }

    @Test
    void pushReturningZeroCountsFailure() {
        FanoutController controller = controller(64, 0, 1, false);
        delivery.accepted = 0;
        controller.enqueue(List.of(UUID.randomUUID()), "sha", false, clock.get());
        scheduler.tick();

        assertEquals(0, controller.stats().getInflight(), "平台没发出去就要立刻回收窗口");
        assertEquals(1L, controller.stats().getDropped());
        controller.close();
    }

    @Test
    void quitDuringInflightReleasesWindow() {
        FanoutController controller = controller(1, 0, 1, false);
        UUID quitting = UUID.randomUUID();
        controller.enqueue(List.of(quitting), "sha", false, clock.get());
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight());

        controller.playerLeft(quitting);
        assertEquals(0, controller.stats().getInflight());

        UUID next = UUID.randomUUID();
        controller.enqueue(List.of(next), "sha", false, clock.get());
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight(), "退服腾出的窗口应立刻给下一位");
        assertTrue(delivery.pushed.contains(next));
        controller.close();
    }

    @Test
    void timeoutAndClose() {
        FanoutController controller = controller(64, 0, 1, false);
        controller.enqueue(List.of(UUID.randomUUID()), "sha", false, clock.get());
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight());

        clock.addAndGet(120_001L);
        scheduler.tick();
        assertEquals(0, controller.stats().getInflight());
        assertEquals(1L, controller.stats().getFailed());

        controller.close();
        int before = delivery.pushed.size();
        controller.enqueue(List.of(UUID.randomUUID()), "sha", false, clock.get());
        assertEquals(0, controller.stats().getQueued());
        scheduler.tick();
        assertEquals(before, delivery.pushed.size(), "关闭后不再发包");
    }

    @Test
    void joinTaskDoesNotBlockBroadcast() {
        FanoutController controller = controller(64, 1_000, 1, false);
        UUID joining = UUID.randomUUID();
        UUID broadcast = UUID.randomUUID();
        controller.enqueue(List.of(joining), "sha", true, clock.get());
        controller.enqueue(List.of(broadcast), "sha", false, clock.get());

        scheduler.tick();
        assertTrue(delivery.pushed.contains(broadcast), "未到期的进服任务不能挡住广播");
        assertTrue(!delivery.pushed.contains(joining));
        assertEquals(1, controller.stats().getQueued());
        controller.close();
    }

    @Test
    void rampDownOnFailures() {
        FanoutController controller = controller(64, 0, 0, true);
        int window = 0;
        for (int round = 0; round < 4; round++) {
            window = grow(controller);
        }
        assertTrue(window > 8, "连续成功后窗口应增长，实际 " + window);

        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < window; i++) {
            players.add(UUID.randomUUID());
        }
        controller.enqueue(players, "sha", false, clock.get());
        scheduler.tick();
        int before = controller.stats().getWindow();
        for (UUID player : players) {
            controller.onStatus(player, PackStatus.FAILED, "sha", clock.get());
        }
        clock.addAndGet(5_001L);
        scheduler.tick();
        assertTrue(controller.stats().getWindow() < before, "失败率超过 2% 时窗口必须减半");
        assertTrue(controller.stats().getWindow() >= 8);
        controller.close();
    }

    private int grow(FanoutController controller) {
        clock.addAndGet(5_001L);
        List<UUID> players = new ArrayList<>();
        int window = controller.stats().getWindow();
        for (int i = 0; i < window; i++) {
            players.add(UUID.randomUUID());
        }
        controller.enqueue(players, "sha", false, clock.get());
        scheduler.tick();
        for (UUID player : players) {
            controller.onStatus(player, PackStatus.LOADED, "sha", clock.get());
        }
        return controller.stats().getWindow();
    }

    @Test
    void unconditionalPushIgnoresLoadedState() {
        FanoutController controller = controller(64, 0, 1, false);
        UUID player = UUID.randomUUID();
        delivery.loaded.put(player, "sha");

        controller.enqueue(List.of(player), "sha", false, clock.get());
        scheduler.tick();
        assertEquals(0, delivery.pushed.size(), "普通推送跳过已加载同一版本的玩家");
        assertEquals(1L, controller.stats().getDropped());

        controller.enqueue(List.of(player), "sha", true, clock.get(), true);
        scheduler.tick();
        assertEquals(1, delivery.pushed.size(), "强制推送跳过已加载判断");
        assertEquals("sha", delivery.sentSha1, "强制推送仍记录真实摘要");
        assertTrue(delivery.sentForce, "下发器应看到 force");
        assertEquals(1, controller.stats().getInflight());

        // 迟到的旧代回执不清掉当前代的槽位，也不算完成。
        controller.onStatus(player, PackStatus.LOADED, "older", clock.get());
        assertEquals(0L, controller.stats().getCompleted(), "旧代回执不应算完成");
        assertEquals(1, controller.stats().getInflight(), "旧代回执不平白释放当前代的槽位");
        controller.onStatus(player, PackStatus.LOADED, "sha", clock.get());
        assertEquals(1L, controller.stats().getCompleted());
        assertEquals(0, controller.stats().getInflight());
        controller.close();
    }

    @Test
    void newerGenerationReplacesPendingTaskInsteadOfDropping() {
        FanoutController controller = controller(1, 0, 1, false);
        UUID player = UUID.randomUUID();
        controller.enqueue(List.of(player), "v1", false, clock.get());
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight());

        // 旧代还在途中时发布新包：新任务应留在队列里，不能被去重逻辑丢掉。
        controller.enqueue(List.of(player), "v2", false, clock.get());
        controller.onStatus(player, PackStatus.LOADED, "v1", clock.get());
        assertEquals(0, controller.stats().getInflight(), "旧代回执释放窗口");
        scheduler.tick();
        assertEquals(1, controller.stats().getInflight(), "旧代回执后新代应被派发");
        assertEquals("v2", delivery.sentSha1s.get(delivery.sentSha1s.size() - 1));
        assertEquals(2, delivery.pushed.size());
        controller.close();
    }

    @Test
    void forcedGenerationReplacesPendingTaskToo() {
        FanoutController controller = controller(1, 0, 1, false);
        UUID player = UUID.randomUUID();
        controller.enqueue(List.of(player), "v1", false, clock.get());
        scheduler.tick();

        controller.enqueue(List.of(player), "v2", false, clock.get(), true);
        controller.onStatus(player, PackStatus.LOADED, "v1", clock.get());
        scheduler.tick();
        assertEquals(2, delivery.pushed.size(), "强制推送同样不能被在途任务吃掉");
        assertEquals("v2", delivery.sentSha1s.get(delivery.sentSha1s.size() - 1));
        assertTrue(delivery.sentForce, "第二批应带 force");
        controller.close();
    }

    private static final class FakeScheduler implements Scheduler {
        private Runnable ticker;

        @Override
        public Cancellable runAsyncNow(Runnable task) {
            task.run();
            return () -> { };
        }

        @Override
        public Cancellable runAsyncTimer(long delayMillis, long periodMillis, Runnable task) {
            ticker = task;
            return () -> ticker = null;
        }

        void tick() {
            Runnable task = ticker;
            if (task != null) {
                task.run();
            }
        }
    }

    private static final class FakeDelivery implements PackDelivery {
        final List<UUID> pushed = new ArrayList<>();
        final Map<UUID, String> loaded = new HashMap<>();
        int accepted = 1;
        String sentSha1 = "";
        final List<String> sentSha1s = new ArrayList<>();
        boolean sentForce;

        @Override
        public int push(UUID player, boolean force) {
            return push(player, "", force);
        }

        @Override
        public int push(UUID player, String sha1, boolean force) {
            if (accepted <= 0) {
                return 0;
            }
            pushed.add(player);
            sentSha1 = sha1;
            sentSha1s.add(sha1);
            sentForce = force;
            return accepted;
        }

        @Override
        public String loadedSha1(UUID player) {
            return loaded.getOrDefault(player, "");
        }

        @Override
        public String baseUrl() {
            return "http://127.0.0.1/pack.zip";
        }

        @Override
        public void publish(String url, String sha1) {
        }
    }
}
