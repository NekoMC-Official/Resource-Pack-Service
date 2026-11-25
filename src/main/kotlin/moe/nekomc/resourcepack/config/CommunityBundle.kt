package moe.nekomc.resourcepack.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import moe.nekomc.resourcepack.core.ZipSource
import moe.nekomc.resourcepack.pack.CommunityPack
import moe.nekomc.resourcepack.pack.CommunitySelector
import moe.nekomc.resourcepack.pack.SelectionContext

/**
 * 读取社区资源包目录里的 manifest.json，并按服务器实际安装的插件筛选来源。
 *
 * <p>目录布局由 tools/packs.py bundle 生成：manifest.json 加每个包的 zip。
 * 这里只做读取与转换；绑定规则在 CommunitySelector 里，便于单测。</p>
 */
class CommunityBundle(
    private val directory: Path,
    private val priorityOffset: Int,
    private val installed: Predicate<String>,
    private val minecraftVersion: String,
    private val packFormat: Int,
    private val pluginVersion: Function<String, String>,
    private val log: Consumer<String>
) {

    /** 兼容旧调用：不传运行环境时按“不限制”处理。 */
    constructor(
        directory: Path,
        priorityOffset: Int,
        installed: Predicate<String>,
        log: Consumer<String>
    ) : this(directory, priorityOffset, installed, "", 0, Function<String, String> { "" }, log)

    fun load(): List<ZipSource> {
        val root = directory.toAbsolutePath().normalize()
        val manifest = root.resolve(MANIFEST)
        if (!Files.isRegularFile(manifest)) {
            log.accept("社区资源包目录缺少 manifest.json：" + manifest)
            return emptyList()
        }
        val manifestBytes = try {
            Files.size(manifest)
        } catch (exception: Exception) {
            log.accept("社区资源包 manifest.json 读取失败：" + exception.message)
            return emptyList()
        }
        if (manifestBytes > MAX_MANIFEST_BYTES) {
            log.accept("社区资源包 manifest.json 超过 " + MAX_MANIFEST_BYTES + " 字节，已忽略：" + manifest)
            return emptyList()
        }

        val document = try {
            JsonParser.parseString(Files.readString(manifest)).asJsonObject
        } catch (exception: Exception) {
            log.accept("社区资源包 manifest.json 解析失败：" + exception.message)
            return emptyList()
        }

        val packs = readPacks(document)
        if (packs.isEmpty()) {
            log.accept("社区资源包 manifest.json 没有 packs 条目：" + manifest)
            return emptyList()
        }

        val selectedIds = readSelected(document, packs)
        val context = SelectionContext(installed, pluginVersion, minecraftVersion, packFormat)
        val selection = CommunitySelector.select(packs, selectedIds, context)
        for (skipped in selection.skipped()) {
            log.accept("社区资源包跳过 " + skipped.id() + "：" + skipped.reason())
        }

        val sources = ArrayList<ZipSource>()
        for (pack in selection.selected()) {
            val id = pack.id()
            if (!PACK_ID.matches(id)) {
                log.accept("社区资源包 id 不合法，已跳过：" + id)
                continue
            }
            val file = root.resolve(id + ".zip").normalize()
            if (!file.startsWith(root)) {
                log.accept("社区资源包路径越界，已跳过：" + id)
                continue
            }
            if (!Files.isRegularFile(file)) {
                log.accept("社区资源包缺少 zip，已跳过：" + file)
                continue
            }
            sources.add(
                ZipSource(
                    "community/" + id,
                    true,
                    file,
                    null,
                    priorityOffset + pack.priority(),
                    emptyList(),
                    pack.version()
                )
            )
        }

        val skippedCount = selection.skipped().size + selection.selected().size - sources.size
        log.accept("社区资源包绑定检测：纳入 " + sources.size + " 个，跳过 " + skippedCount + " 个")
        return sources
    }

    private fun readPacks(document: JsonObject): List<CommunityPack> {
        val array = document.get("packs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val packs = ArrayList<CommunityPack>()
        for (element in array) {
            if (!element.isJsonObject) {
                continue
            }
            val objectValue = element.asJsonObject
            val id = string(objectValue, "id")
            if (id.isEmpty()) {
                continue
            }
            val also = ArrayList<String>()
            val alsoArray = objectValue.get("also")?.takeIf { it.isJsonArray }?.asJsonArray
            if (alsoArray != null) {
                for (item in alsoArray) {
                    if (item.isJsonPrimitive) {
                        val value = item.asString.trim()
                        if (value.isNotEmpty()) {
                            also.add(value)
                        }
                    }
                }
            }
            packs.add(
                CommunityPack(
                    id,
                    string(objectValue, "plugin"),
                    string(objectValue, "relation"),
                    integer(objectValue, "priority"),
                    also,
                    string(objectValue, "release"),
                    string(objectValue, "version"),
                    string(objectValue, "minecraft"),
                    string(objectValue, "requires"),
                    string(objectValue, "variant"),
                    boolean(objectValue, "default"),
                    integer(objectValue, "format_min"),
                    integer(objectValue, "format_max")
                )
            )
        }
        return packs
    }

    private fun readSelected(document: JsonObject, packs: List<CommunityPack>): Set<String> {
        val bundle = document.get("bundle")?.takeIf { it.isJsonObject }?.asJsonObject
        val array = bundle?.get("selected")?.takeIf { it.isJsonArray }?.asJsonArray
        if (array == null) {
            return packs.map { it.id() }.toSet()
        }
        val selected = LinkedHashSet<String>()
        for (element in array) {
            if (element.isJsonPrimitive) {
                val value = element.asString.trim()
                if (value.isNotEmpty()) {
                    selected.add(value)
                }
            }
        }
        return selected
    }

    private fun string(objectValue: JsonObject, key: String): String {
        val element = objectValue.get(key) ?: return ""
        return if (element.isJsonPrimitive) {
            try {
                element.asString.trim()
            } catch (exception: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    private fun integer(objectValue: JsonObject, key: String): Int {
        val element = objectValue.get(key) ?: return 0
        if (!element.isJsonPrimitive) {
            return 0
        }
        return try {
            element.asInt
        } catch (exception: Exception) {
            0
        }
    }

    private fun boolean(objectValue: JsonObject, key: String): Boolean {
        val element = objectValue.get(key) ?: return false
        return element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
    }

    private companion object {
        private const val MANIFEST = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        private val PACK_ID = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
