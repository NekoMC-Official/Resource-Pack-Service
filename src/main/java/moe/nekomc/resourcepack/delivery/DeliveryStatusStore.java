package moe.nekomc.resourcepack.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import moe.nekomc.resourcepack.api.PackStatus;

/**
 * 玩家资源包状态的内存索引：当前状态 + 最近若干条历史。
 *
 * <p>不依赖 Bukkit，方便单测；线程安全，供实体调度器与命令线程同时读取。</p>
 */
public final class DeliveryStatusStore {

    /** 最近状态最多保留多少条。 */
    static final int MAX_RECENT = 200;

    private final ConcurrentHashMap<UUID, Entry> current = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Entry> recent = new CopyOnWriteArrayList<>();

    /** 记录一次状态；同时写入当前状态与历史。 */
    void record(UUID playerId, String playerName, PackStatus status, String sha1) {
        if (playerId == null || status == null) {
            return;
        }
        Entry entry = new Entry(
                playerId,
                playerName == null ? "" : playerName,
                status,
                sha1 == null ? "" : sha1,
                System.currentTimeMillis());
        current.put(playerId, entry);
        recent.add(entry);
        while (recent.size() > MAX_RECENT) {
            recent.remove(0);
        }
    }

    /** 当前状态；未知玩家返回 {@link PackStatus#UNKNOWN}。 */
    PackStatus status(UUID playerId) {
        if (playerId == null) {
            return PackStatus.UNKNOWN;
        }
        Entry entry = current.get(playerId);
        return entry == null ? PackStatus.UNKNOWN : entry.status();
    }

    /** 加载成功时使用的 SHA-1；其它状态返回 null。 */
    String hash(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        Entry entry = current.get(playerId);
        if (entry == null || entry.status() != PackStatus.LOADED) {
            return null;
        }
        return entry.sha1().isBlank() ? null : entry.sha1();
    }

    /** 最近的状态，新的在前。 */
    List<Entry> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RECENT));
        List<Entry> snapshot = new ArrayList<>(recent);
        snapshot.sort((left, right) -> Long.compare(right.at(), left.at()));
        return snapshot.size() <= capped ? snapshot : snapshot.subList(0, capped);
    }

    /** 玩家下线时清理当前状态；历史保留，供 /rps rejected 查看。 */
    void remove(UUID playerId) {
        if (playerId != null) {
            current.remove(playerId);
        }
    }

    /** 一条状态快照。 */
    public record Entry(UUID playerId, String playerName, PackStatus status, String sha1, long at) {
    }
}
