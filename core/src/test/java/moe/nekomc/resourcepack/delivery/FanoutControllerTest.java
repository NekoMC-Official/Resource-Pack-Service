package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import moe.nekomc.resourcepack.api.PackStatus;
import moe.nekomc.resourcepack.platform.Scheduler;
import org.junit.jupiter.api.Test;

class FanoutControllerTest {

    private static final Logger LOGGER = Logger.getLogger("fanout-test");

    private static FanoutController controller(FakeDelivery delivery, FakeScheduler scheduler, int window, int retry) {
        return new FanoutController(delivery, scheduler, LOGGER, window, 0, retry, false, 0L, 60_000L);
    }

    @Test
    void skipsPlayersThatAlreadyLoadedTheSameHash() {
        FakeDelivery delivery = new FakeDelivery();
        FakeScheduler scheduler = new FakeScheduler();
        UUID loaded = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        delivery.loaded.put(loaded, "abc");
        FanoutController controller = controller(delivery, scheduler, 8, 0);

        controller.enqueue(List.of(loaded, other), "abc", false);
        scheduler.tick();

        assertEquals(List.of(other), delivery.pushed);
        assertEquals(1, controller.stats().getInflight());
        assertEquals(1, controller.stats().getPushed());
        assertEquals(1, controller.stats().getDropped());
    }

    @Test
    void dispatchesWithinWindowAndDrainsInFollowingTicks() {
        FakeDelivery delivery = new FakeDelivery();
        FakeScheduler scheduler = new FakeScheduler();
        FanoutController controller = controller(delivery, scheduler, 2, 0);
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        controller.enqueue(players, "h", false);
        scheduler.tick();
        assertEquals(2, delivery.pushed.size());
        assertEquals(2, controller.stats().getInflight());
        assertEquals(1, controller.stats().getQueued());

        controller.onStatus(players.get(0), PackStatus.LOADED, "h");
        scheduler.tick();
        assertEquals(3, delivery.pushed.size());
        assertEquals(0, controller.stats().getQueued());
    }

    @Test
    void retriesFailedDownloadOnceThenGivesUp() {
        FakeDelivery delivery = new FakeDelivery();
        FakeScheduler scheduler = new FakeScheduler();
        FanoutController controller = controller(delivery, scheduler, 4, 1);
        UUID player = UUID.randomUUID();

        controller.enqueue(List.of(player), "h", false);
        scheduler.tick();
        controller.onStatus(player, PackStatus.FAILED, "h");
        scheduler.tick();
        assertEquals(2, delivery.pushed.size());
        assertEquals(1, controller.stats().getRetried());

        controller.onStatus(player, PackStatus.FAILED, "h");
        scheduler.tick();
        assertEquals(2, delivery.pushed.size());
        assertEquals(1, controller.stats().getFailed());
    }

    @Test
    void failedRetryDoesNotDisplaceQueuedGeneration() {
        FakeDelivery delivery = new FakeDelivery();
        FakeScheduler scheduler = new FakeScheduler();
        FanoutController controller = controller(delivery, scheduler, 1, 1);
        UUID player = UUID.randomUUID();

        controller.enqueue(List.of(player), "h1", false);
        scheduler.tick();
        assertEquals(1, delivery.pushed.size());

        controller.enqueue(List.of(player), "h2", false);
        controller.onStatus(player, PackStatus.FAILED, "h1");
        scheduler.tick();

        assertEquals(2, delivery.pushed.size(), "旧代的失败不能挤掉队列里的新代");
        assertEquals(0, controller.stats().getRetried());
        assertEquals(List.of("h1", "h2"), delivery.sentSha);
        controller.close();
    }

    @Test
    void duplicateEnqueueForSamePlayerIsDropped() {
        FakeDelivery delivery = new FakeDelivery();
        FakeScheduler scheduler = new FakeScheduler();
        FanoutController controller = controller(delivery, scheduler, 4, 0);
        UUID player = UUID.randomUUID();

        controller.enqueue(List.of(player), "h", false);
        controller.enqueue(List.of(player), "h", false);
        scheduler.tick();

        assertEquals(1, delivery.pushed.size());
        assertEquals(1, controller.stats().getDropped());
    }

    private static final class FakeScheduler implements Scheduler {
        private Runnable timer;

        @Override
        public Cancellable runAsyncNow(Runnable task) {
            task.run();
            return () -> { };
        }

        @Override
        public Cancellable runAsyncTimer(long delayMillis, long periodMillis, Runnable task) {
            timer = task;
            return () -> { };
        }

        void tick() {
            if (timer != null) {
                timer.run();
            }
        }
    }

    private static final class FakeDelivery implements PackDelivery {
        private final List<UUID> pushed = new ArrayList<>();
        private final List<String> sentSha = new ArrayList<>();
        private final Map<UUID, String> loaded = new HashMap<>();

        @Override
        public int push(UUID player, boolean force) {
            return push(player, "", force);
        }

        @Override
        public int push(UUID player, String sha1, boolean force) {
            pushed.add(player);
            sentSha.add(sha1);
            return 1;
        }

        @Override
        public String baseUrl() {
            return "http://127.0.0.1/";
        }

        @Override
        public void publish(String url, String sha1) {
        }

        @Override
        public String loadedSha1(UUID player) {
            return loaded.getOrDefault(player, "");
        }
    }
}