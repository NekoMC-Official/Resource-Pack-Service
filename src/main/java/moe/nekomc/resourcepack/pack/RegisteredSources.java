package moe.nekomc.resourcepack.pack;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import moe.nekomc.resourcepack.api.PackSource;
import moe.nekomc.resourcepack.api.ResourcePackService;
import moe.nekomc.resourcepack.core.SourceRegistry;
import moe.nekomc.resourcepack.core.ZipSource;

/**
 * {@link ResourcePackService} 的实现：维护插件注册的来源，把它们投进合并注册表。
 *
 * <p>与配置文件里的来源共用同一张 {@link SourceRegistry}，同 id 时后写入者生效；
 * reload 重建注册表后由 {@link #reapply(SourceRegistry)} 把已登记来源补回去。</p>
 */
public final class RegisteredSources implements ResourcePackService {

    /** 对外状态快照，避免实现直接依赖发布器类型。 */
    public record Status(int version, String url, String sha1) {

        public static Status empty() {
            return new Status(0, "", "");
        }
    }

    /** 由插件提供的动作：重建、读状态、推送。 */
    public interface Host {

        void rebuild(boolean announce);

        Status status();

        int push(UUID player, boolean force);
    }

    private final Supplier<SourceRegistry> registry;
    private final Host host;
    private final Map<String, PackSource> sources = new ConcurrentHashMap<>();

    public RegisteredSources(Supplier<SourceRegistry> registry, Host host) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public boolean register(PackSource source) {
        if (source == null || source.id() == null || source.id().isBlank() || source.file() == null) {
            return false;
        }
        sources.put(source.id(), source);
        registry.get().put(toZip(source));
        host.rebuild(false);
        return true;
    }

    @Override
    public boolean unregister(String id) {
        if (id == null || sources.remove(id) == null) {
            return false;
        }
        registry.get().remove(id);
        host.rebuild(false);
        return true;
    }

    @Override
    public Set<String> sourceIds() {
        return Set.copyOf(sources.keySet());
    }

    @Override
    public int version() {
        return host.status().version();
    }

    @Override
    public String url() {
        return host.status().url();
    }

    @Override
    public String sha1() {
        return host.status().sha1();
    }

    @Override
    public void rebuild(boolean announce) {
        host.rebuild(announce);
    }

    @Override
    public int push(UUID player, boolean force) {
        return player == null ? 0 : host.push(player, force);
    }

    /** reload 之后调用：把已登记来源重新投进新建的注册表。 */
    public int reapply(SourceRegistry fresh) {
        int count = 0;
        for (PackSource source : sources.values()) {
            fresh.put(toZip(source));
            count++;
        }
        return count;
    }

    private static ZipSource toZip(PackSource source) {
        return new ZipSource(source.id(), source.enabled(), source.file(), source.sha1(),
                source.priority(), source.namespaces());
    }
}
