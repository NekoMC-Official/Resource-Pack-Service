package moe.nekomc.resourcepack.pack

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import moe.nekomc.resourcepack.api.BuildResult
import moe.nekomc.resourcepack.api.PackSource
import moe.nekomc.resourcepack.api.PackStatus
import moe.nekomc.resourcepack.api.ResourcePackService
import moe.nekomc.resourcepack.core.SourceRegistry
import moe.nekomc.resourcepack.core.ZipSource

/**
 * 对外的 {@link ResourcePackService} 实现：登记插件递过来的来源，其余全部转给插件。
 *
 * 这里刻意不缓存构建结果——版本、地址、摘要都以发布器为准，避免两边各存一份对不上。
 */
class RegisteredSources(
    private val registry: Supplier<SourceRegistry>,
    private val host: Host
) : ResourcePackService {

    private val registered = ConcurrentHashMap<String, PackSource>()

    override fun register(source: PackSource?): Boolean {
        if (source == null) {
            return false
        }
        val id = source.id()
        if (id.isNullOrBlank() || source.file() == null) {
            return false
        }
        registered[id] = source
        registry.get().put(source)
        host.rebuild(false)
        return true
    }

    override fun unregister(id: String): Boolean {
        if (id.isBlank()) {
            return false
        }
        val removed = registered.remove(id) != null
        registry.get().remove(id)
        if (removed) {
            host.rebuild(false)
        }
        return removed
    }

    override fun invalidate(id: String) {
        if (id.isBlank() || !registered.containsKey(id)) {
            return
        }
        host.rebuild(false)
    }

    override fun sourceIds(): Set<String> = HashSet(registered.keys)

    override fun version(): Int = host.status().version()

    override fun url(): String = host.status().url()

    override fun sha1(): String = host.status().sha1()

    override fun rebuild(announce: Boolean) {
        host.rebuild(announce)
    }

    override fun rebuildAsync(announce: Boolean): CompletableFuture<BuildResult> = host.rebuildAsync(announce)

    override fun snapshot(): BuildResult = host.snapshot()

    override fun status(player: UUID?): PackStatus = if (player == null) PackStatus.UNKNOWN else host.status(player)

    override fun hashOf(player: UUID?): String? = if (player == null) null else host.hashOf(player)

    override fun lastError(): String? = host.lastError()

    override fun push(player: UUID?, force: Boolean): Int {
        if (player == null) {
            return 0
        }
        return host.push(player, force)
    }

    /** 重载配置后把注册过的来源重投进新注册表，返回重投数量。 */
    fun reapply(fresh: SourceRegistry): Int {
        var restored = 0
        for (source in registered.values) {
            if (source.file() == null) {
                continue
            }
            fresh.put(source)
            restored++
        }
        return restored
    }

    /** 把通过的来源转成合并器认的 DTO。 */
    fun toZip(source: PackSource): ZipSource = ZipSource(
        source.id(),
        source.enabled(),
        source.file(),
        source.sha1(),
        source.priority(),
        source.namespaces(),
        source.version()
    )
}
