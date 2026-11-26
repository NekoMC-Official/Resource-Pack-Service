package moe.nekomc.resourcepack.pack

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 索引里的一条社区包记录：绑定信息 + 内容寻址信息。 */
data class IndexedPack(
    val pack: CommunityPack,
    val sha1: String,
    val file: String,
    val bytes: Long,
)

/**
 * GitHub Pages 上的 community-index.json。
 *
 * <p>只解析、不下载；下载与落盘由 [CommunityUpdater] 负责。索引由
 * tools/packs.py index 生成，schema 固定为 1。</p>
 */
class CommunityIndex private constructor(
    val generatedAt: String,
    val commit: String,
    val packs: List<IndexedPack>,
) {

    fun byId(): Map<String, IndexedPack> = packs.associateBy { it.pack.id() }

    companion object {

        private val PACK_ID = Regex("^[a-z0-9][a-z0-9_-]*$")
        private val SHA1 = Regex("^[0-9a-f]{40}$")
        private const val MAX_PACKS = 500

        /** 解析失败返回 null；调用方保留旧清单。 */
        @JvmStatic
        fun parse(text: String): CommunityIndex? {
            val root = try {
                JsonParser.parseString(text)
            } catch (exception: Exception) {
                return null
            }
            if (!root.isJsonObject) {
                return null
            }
            val document = root.asJsonObject
            if (integer(document, "schema") != 1) {
                return null
            }
            val array = document.getAsJsonArray("packs") ?: return null
            if (array.size() > MAX_PACKS) {
                return null
            }

            val packs = ArrayList<IndexedPack>()
            val seen = HashSet<String>()
            for (element in array) {
                if (!element.isJsonObject) {
                    return null
                }
                val item = element.asJsonObject
                val id = string(item, "id")
                if (!PACK_ID.matches(id) || !seen.add(id)) {
                    return null
                }
                val sha1 = string(item, "sha1").lowercase()
                if (!SHA1.matches(sha1)) {
                    return null
                }
                val file = string(item, "file")
                if (!safeFile(file)) {
                    return null
                }
                val plugin = string(item, "plugin")
                val relation = string(item, "relation")
                if (plugin.isBlank() || relation.isBlank()) {
                    return null
                }
                val pack = CommunityPack(
                    id,
                    plugin,
                    relation,
                    integer(item, "priority"),
                    strings(item, "also"),
                    string(item, "release").ifBlank { "auto" },
                    string(item, "version"),
                    string(item, "minecraft"),
                    string(item, "requires"),
                    string(item, "variant"),
                    boolean(item, "default"),
                    integer(item, "formatMin").takeIf { it > 0 } ?: integer(item, "format_min"),
                    integer(item, "formatMax").takeIf { it > 0 } ?: integer(item, "format_max"),
                )
                packs.add(IndexedPack(pack, sha1, file, long(item, "bytes")))
            }

            return CommunityIndex(
                string(document, "generatedAt"),
                string(document, "commit"),
                packs.toList(),
            )
        }

        /**
         * 生成服务端可消费的 manifest.json；目录布局仍是每个包一个 [id].zip，
         * 与 tools/packs.py bundle 的输出保持一致。
         */
        @JvmStatic
        fun manifest(packs: List<IndexedPack>): String {
            val root = JsonObject()
            root.addProperty("version", 1)
            val array = JsonArray()
            for (entry in packs) {
                val pack = entry.pack
                val item = JsonObject()
                item.addProperty("id", pack.id())
                item.addProperty("plugin", pack.plugin())
                item.addProperty("relation", pack.relation())
                item.addProperty("priority", pack.priority())
                val also = JsonArray()
                for (other in pack.also()) {
                    also.add(other)
                }
                item.add("also", also)
                item.addProperty("release", pack.release())
                item.addProperty("version", pack.version())
                item.addProperty("minecraft", pack.minecraft())
                item.addProperty("requires", pack.requires())
                item.addProperty("variant", pack.variant())
                item.addProperty("default", pack.isDefault())
                item.addProperty("format_min", pack.formatMin())
                item.addProperty("format_max", pack.formatMax())
                array.add(item)
            }
            root.add("packs", array)
            val bundle = JsonObject()
            val selected = JsonArray()
            for (entry in packs) {
                selected.add(entry.pack.id())
            }
            bundle.add("selected", selected)
            root.add("bundle", bundle)
            return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
        }

        private fun safeFile(file: String): Boolean {
            return file.startsWith("objects/") &&
                file.endsWith(".zip") &&
                !file.startsWith("/") &&
                !file.contains("..") &&
                !file.contains('\\')
        }

        private fun string(objectValue: JsonObject, key: String): String {
            val element = objectValue.get(key) ?: return ""
            return if (element.isJsonPrimitive) element.asString.trim() else ""
        }

        private fun strings(objectValue: JsonObject, key: String): List<String> {
            val array = objectValue.getAsJsonArray(key) ?: return emptyList()
            val values = ArrayList<String>()
            for (element in array) {
                if (element.isJsonPrimitive) {
                    val value = element.asString.trim()
                    if (value.isNotEmpty()) {
                        values.add(value)
                    }
                }
            }
            return values
        }

        private fun integer(objectValue: JsonObject, key: String): Int {
            val element = objectValue.get(key) ?: return 0
            if (!element.isJsonPrimitive) {
                return 0
            }
            return try {
                element.asInt
            } catch (exception: NumberFormatException) {
                0
            }
        }

        private fun long(objectValue: JsonObject, key: String): Long {
            val element = objectValue.get(key) ?: return 0L
            if (!element.isJsonPrimitive) {
                return 0L
            }
            return try {
                element.asLong
            } catch (exception: NumberFormatException) {
                0L
            }
        }

        private fun boolean(objectValue: JsonObject, key: String): Boolean {
            val element = objectValue.get(key) ?: return false
            return element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
        }
    }
}
