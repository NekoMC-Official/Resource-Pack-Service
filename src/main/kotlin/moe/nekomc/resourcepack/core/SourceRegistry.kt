package moe.nekomc.resourcepack.core

import java.util.LinkedHashMap
import moe.nekomc.resourcepack.api.PackSource

/**
 * 来源注册表：按 id 覆盖、按 layer 排序，构建时取一份稳定快照。
 *
 * 同步的目的不是并发性能，而是让"注册正在发生"与"构建正在读"不会互相看到半个表。
 */
class SourceRegistry {

    private val entries = LinkedHashMap<String, PackSource>()
    private var revisionCounter = 0

    @Synchronized
    fun put(source: PackSource?) {
        if (source == null) {
            return
        }
        val id = source.id()
        if (id.isNullOrBlank()) {
            return
        }
        entries[id] = source
        revisionCounter++
    }

    @Synchronized
    fun remove(id: String?): Boolean {
        if (id == null) {
            return false
        }
        val removed = entries.remove(id) != null
        if (removed) {
            revisionCounter++
        }
        return removed
    }

    @Synchronized
    fun get(id: String?): PackSource? = if (id == null) null else entries[id]

    @Synchronized
    fun all(): List<PackSource> = ArrayList(entries.values)

    /** 层序：priority 小的先叠，大的后叠；同 priority 保持登记顺序。 */
    @Synchronized
    fun ordered(): List<PackSource> = entries.values.sortedBy { it.priority() }

    /** 每次增删都会加一，用于判断"注册表是否真的变了"。 */
    @Synchronized
    fun revision(): Int = revisionCounter

    @Synchronized
    fun clear() {
        if (entries.isEmpty()) {
            return
        }
        entries.clear()
        revisionCounter++
    }
}
