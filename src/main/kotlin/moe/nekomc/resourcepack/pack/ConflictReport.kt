package moe.nekomc.resourcepack.pack

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import moe.nekomc.resourcepack.merge.MergeResult

/** 冲突报告中的一条记录，字段名与 Rust 报告保持一致。 */
data class StoredConflict(
    val path: String,
    val winner: String,
    val losers: List<String>,
    val kind: String,
    val severity: String,
    val mode: String,
    val winnerPriority: Int,
    val loserPriorities: List<Int>,
    val winnerSha1: String,
    val loserSha1s: List<String>,
    val samePriority: Boolean,
    val fields: List<String>
)

data class StoredConflictSummary(
    val total: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val byKind: Map<String, Int>,
    val truncated: Boolean
)

data class StoredConflictReport(
    val generatedAt: String,
    val sha1: String,
    val version: Int,
    val threshold: String,
    val summary: StoredConflictSummary,
    val conflicts: List<StoredConflict>
)

/**
 * 把合并期的冲突明细落到 `conflicts.json`，供 /rps conflicts 查看。
 *
 * 报告只是展示层数据：写失败不能影响合成包落盘；读取时任何损坏都当作没有报告。
 */
object ConflictReport {

    const val FILE_NAME = "conflicts.json"
    const val MAX_DETAILS = 5000
    const val MAX_FIELDS = 50
    const val MAX_FIELD_LENGTH = 120
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @JvmStatic
    fun write(directory: Path, snapshot: PackSnapshot, result: MergeResult) {
        val summary = result.conflictSummary() ?: summarize(result.conflicts())
        val root = JsonObject()
        root.addProperty("generatedAt", snapshot.builtAt())
        root.addProperty("sha1", snapshot.sha1())
        root.addProperty("version", snapshot.version())
        root.addProperty("threshold", "low")

        val summaryJson = JsonObject()
        summaryJson.addProperty("total", summary.total())
        summaryJson.addProperty("high", summary.high())
        summaryJson.addProperty("medium", summary.medium())
        summaryJson.addProperty("low", summary.low())
        val byKind = JsonObject()
        for ((kind, count) in summary.byKind().toSortedMap()) {
            byKind.addProperty(kind, count)
        }
        summaryJson.add("byKind", byKind)
        summaryJson.addProperty("truncated", summary.truncated())
        root.add("summary", summaryJson)

        val conflicts = JsonArray()
        for (conflict in result.conflicts().take(MAX_DETAILS)) {
            val item = JsonObject()
            item.addProperty("path", conflict.path())
            item.addProperty("winner", conflict.winner())
            item.add("losers", strings(conflict.losers()))
            item.addProperty("kind", conflict.kind())
            item.addProperty("severity", conflict.severity())
            item.addProperty("mode", conflict.mode())
            item.addProperty("winnerPriority", conflict.winnerPriority())
            val loserPriorities = JsonArray()
            for (priority in conflict.loserPriorities()) {
                loserPriorities.add(priority)
            }
            item.add("loserPriorities", loserPriorities)
            item.addProperty("winnerSha1", conflict.winnerSha1())
            item.add("loserSha1s", strings(conflict.loserSha1s()))
            item.addProperty("samePriority", conflict.samePriority())
            val fields = JsonArray()
            for (field in conflict.fields().take(MAX_FIELDS)) {
                fields.add(clip(field))
            }
            item.add("fields", fields)
            conflicts.add(item)
        }
        root.add("conflicts", conflicts)

        val target = directory.resolve(FILE_NAME)
        writeAtomic(target, gson.toJson(root))
    }

    @JvmStatic
    fun read(directory: Path): StoredConflictReport? {
        val target = directory.resolve(FILE_NAME)
        if (!Files.isRegularFile(target)) {
            return null
        }
        return try {
            if (Files.size(target) > MAX_FILE_BYTES) {
                return null
            }
            val parsed = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8))
            if (!parsed.isJsonObject) {
                return null
            }
            readRoot(parsed.asJsonObject)
        } catch (exception: Exception) {
            null
        }
    }

    /** 默认只保留高严重度；all=true 时不过滤。 */
    @JvmStatic
    fun visible(report: StoredConflictReport, all: Boolean): List<StoredConflict> =
        if (all) report.conflicts else report.conflicts.filter { it.severity == "high" }

    private fun readRoot(root: JsonObject): StoredConflictReport {
        val conflicts = mutableListOf<StoredConflict>()
        val array = array(root.get("conflicts"))
        if (array != null) {
            for (element in array) {
                val item = obj(element) ?: continue
                val path = text(item.get("path")) ?: continue
                conflicts.add(
                    StoredConflict(
                        path = path,
                        winner = text(item.get("winner")) ?: "",
                        losers = strings(array(item.get("losers"))),
                        kind = text(item.get("kind")) ?: "other",
                        severity = text(item.get("severity")) ?: "low",
                        mode = text(item.get("mode")) ?: "replace",
                        winnerPriority = integer(item.get("winnerPriority"), 0),
                        loserPriorities = integers(array(item.get("loserPriorities"))),
                        winnerSha1 = text(item.get("winnerSha1")) ?: "",
                        loserSha1s = strings(array(item.get("loserSha1s"))),
                        samePriority = flag(item.get("samePriority"), false),
                        fields = strings(array(item.get("fields")))
                    )
                )
            }
        }
        val summary = obj(root.get("summary"))?.let { readSummary(it, conflicts) }
            ?: summarizeStored(conflicts)
        return StoredConflictReport(
            generatedAt = text(root.get("generatedAt")) ?: "",
            sha1 = text(root.get("sha1")) ?: "",
            version = integer(root.get("version"), 0),
            threshold = text(root.get("threshold")) ?: "low",
            summary = summary,
            conflicts = conflicts
        )
    }

    private fun readSummary(json: JsonObject, conflicts: List<StoredConflict>): StoredConflictSummary {
        val byKind = mutableMapOf<String, Int>()
        obj(json.get("byKind"))?.entrySet()?.forEach { (kind, value) ->
            byKind[kind] = integer(value, 0)
        }
        return StoredConflictSummary(
            total = integer(json.get("total"), conflicts.size),
            high = integer(json.get("high"), conflicts.count { it.severity == "high" }),
            medium = integer(json.get("medium"), conflicts.count { it.severity == "medium" }),
            low = integer(json.get("low"), conflicts.count { it.severity == "low" }),
            byKind = byKind,
            truncated = flag(json.get("truncated"), false)
        )
    }

    private fun summarize(conflicts: List<MergeResult.Conflict>): MergeResult.ConflictSummary {
        val byKind = mutableMapOf<String, Int>()
        var high = 0
        var medium = 0
        var low = 0
        for (conflict in conflicts) {
            when (conflict.severity()) {
                "high" -> high++
                "medium" -> medium++
                else -> low++
            }
            byKind[conflict.kind()] = (byKind[conflict.kind()] ?: 0) + 1
        }
        return MergeResult.ConflictSummary(conflicts.size, high, medium, low, byKind, conflicts.size > MAX_DETAILS)
    }

    private fun summarizeStored(conflicts: List<StoredConflict>): StoredConflictSummary {
        val byKind = mutableMapOf<String, Int>()
        var high = 0
        var medium = 0
        var low = 0
        for (conflict in conflicts) {
            when (conflict.severity) {
                "high" -> high++
                "medium" -> medium++
                else -> low++
            }
            byKind[conflict.kind] = (byKind[conflict.kind] ?: 0) + 1
        }
        return StoredConflictSummary(conflicts.size, high, medium, low, byKind, conflicts.size > MAX_DETAILS)
    }

    private fun writeAtomic(target: Path, content: String) {
        val parent = target.parent ?: return
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "conflicts-", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun strings(values: List<String>): JsonArray {
        val array = JsonArray()
        for (value in values) {
            array.add(clip(value))
        }
        return array
    }

    private fun strings(array: JsonArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        val values = mutableListOf<String>()
        for (element in array) {
            text(element)?.let { values.add(it) }
        }
        return values
    }

    private fun integers(array: JsonArray?): List<Int> {
        if (array == null) {
            return emptyList()
        }
        val values = mutableListOf<Int>()
        for (element in array) {
            values.add(integer(element, 0))
        }
        return values
    }

    private fun clip(value: String?): String {
        if (value == null) {
            return ""
        }
        return if (value.codePointCount(0, value.length) <= MAX_FIELD_LENGTH) value else value.substring(0, value.offsetByCodePoints(0, MAX_FIELD_LENGTH - 1)) + "…"
    }

    private fun obj(element: JsonElement?): JsonObject? =
        if (element != null && element.isJsonObject) element.asJsonObject else null

    private fun array(element: JsonElement?): JsonArray? =
        if (element != null && element.isJsonArray) element.asJsonArray else null

    private fun text(element: JsonElement?): String? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return try {
            element.asString
        } catch (exception: Exception) {
            null
        }
    }

    private fun integer(element: JsonElement?, fallback: Int): Int {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return fallback
        }
        return try {
            element.asInt
        } catch (exception: Exception) {
            fallback
        }
    }

    private fun flag(element: JsonElement?, fallback: Boolean): Boolean {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return fallback
        }
        return try {
            element.asBoolean
        } catch (exception: Exception) {
            fallback
        }
    }
}
