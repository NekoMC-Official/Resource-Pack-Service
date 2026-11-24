package moe.nekomc.resourcepack.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 来源登记表：有序、线程安全、变更时递增修订号。 */
public final class SourceRegistry {

    private final Map<String, ZipSource> sources = new LinkedHashMap<>();
    private long revision;

    public synchronized boolean put(ZipSource source) {
        if (source == null || source.id() == null || source.id().isBlank()) {
            return false;
        }
        ZipSource previous = sources.put(source.id(), source);
        if (source.equals(previous)) {
            return false;
        }
        revision++;
        return true;
    }

    public synchronized boolean remove(String id) {
        boolean removed = sources.remove(id) != null;
        if (removed) {
            revision++;
        }
        return removed;
    }

    public synchronized Optional<ZipSource> get(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    public synchronized List<ZipSource> all() {
        return List.copyOf(sources.values());
    }

    /** 参与合并的来源，priority 升序；同 priority 保持登记顺序。 */
    public synchronized List<ZipSource> ordered() {
        List<ZipSource> list = new ArrayList<>();
        for (ZipSource source : sources.values()) {
            if (source.enabled()) {
                list.add(source);
            }
        }
        list.sort(Comparator.comparingInt(ZipSource::priority));
        return list;
    }

    public synchronized long revision() {
        return revision;
    }
}
