package moe.nekomc.resourcepack.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import moe.nekomc.resourcepack.api.PackStatus;
import org.junit.jupiter.api.Test;

class DeliveryStatusStoreTest {

    @Test
    void tracksCurrentStatusAndHashLifecycle() {
        DeliveryStatusStore store = new DeliveryStatusStore();
        UUID player = UUID.randomUUID();

        assertEquals(PackStatus.UNKNOWN, store.status(player));
        assertNull(store.hash(player));

        store.record(player, "Alice", PackStatus.PENDING, "aaaa");
        assertEquals(PackStatus.PENDING, store.status(player));
        assertNull(store.hash(player));

        store.record(player, "Alice", PackStatus.LOADED, "bbbb");
        assertEquals(PackStatus.LOADED, store.status(player));
        assertEquals("bbbb", store.hash(player));

        store.remove(player);
        assertEquals(PackStatus.UNKNOWN, store.status(player));
        assertNull(store.hash(player));
        assertEquals(2, store.recent(10).size());
    }

    @Test
    void declinesDoNotExposeHash() {
        DeliveryStatusStore store = new DeliveryStatusStore();
        UUID player = UUID.randomUUID();

        store.record(player, "Bob", PackStatus.DECLINED, "cccc");

        assertEquals(PackStatus.DECLINED, store.status(player));
        assertNull(store.hash(player));
    }

    @Test
    void historyIsCappedAndNullInputIsIgnored() {
        DeliveryStatusStore store = new DeliveryStatusStore();
        for (int index = 0; index < 205; index++) {
            store.record(UUID.randomUUID(), "P" + index, PackStatus.PENDING, "sha" + index);
        }
        store.record(null, "Nobody", PackStatus.PENDING, "dddd");
        store.record(UUID.randomUUID(), "NullStatus", null, "eeee");

        assertEquals(DeliveryStatusStore.MAX_RECENT, store.recent(500).size());
        assertEquals(3, store.recent(3).size());
        assertEquals(PackStatus.UNKNOWN, store.status(null));
        assertNull(store.hash(null));
    }
}
