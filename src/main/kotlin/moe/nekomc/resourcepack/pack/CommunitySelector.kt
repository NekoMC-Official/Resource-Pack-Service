package moe.nekomc.resourcepack.pack

/**
 * 社区资源包的服务端绑定选择器。
 *
 * <p>仓库侧的 packs.py 只负责“这个包属于谁、推荐哪些包进 bundle”；服务端这里再按
 * 实际安装的插件、Minecraft 版本、资源包格式与插件版本过滤一次。纯函数，不读文件、
 * 不碰 Bukkit，方便单测。</p>
 */
object CommunitySelector {

    private val RELATIONS = setOf("official", "addon", "patch", "integration", "standalone")

    /**
     * @param packs       清单里的全部包
     * @param selectedIds 仓库侧选中的包 id；空集合表示一个都不选
     * @param context     服务端环境
     */
    @JvmStatic
    fun select(
        packs: List<CommunityPack>,
        selectedIds: Set<String>,
        context: SelectionContext
    ): CommunitySelection {
        val byId = LinkedHashMap<String, CommunityPack>()
        for (pack in packs) {
            val id = pack.id()
            if (id.isBlank()) {
                continue
            }
            byId.putIfAbsent(id, pack)
        }

        val candidates = ArrayList<CommunityPack>()
        val skipped = ArrayList<CommunitySkip>()
        for (id in selectedIds) {
            val pack = byId[id]
            if (pack == null) {
                skipped.add(CommunitySkip(id, "清单里没有这个包"))
            } else {
                candidates.add(pack)
            }
        }

        val compatible = ArrayList<CommunityPack>()
        for (pack in candidates) {
            val reason = reject(pack, context)
            if (reason == null) {
                compatible.add(pack)
            } else {
                skipped.add(CommunitySkip(pack.id(), reason))
            }
        }

        val groups = LinkedHashMap<String, MutableList<CommunityPack>>()
        for (pack in compatible) {
            groups.getOrPut(groupKey(pack)) { ArrayList() }.add(pack)
        }

        val winners = ArrayList<CommunityPack>()
        for (group in groups.values) {
            if (group.size == 1) {
                winners.add(group[0])
                continue
            }
            val defaults = group.filter { it.isDefault() }
            if (defaults.size > 1) {
                if (group[0].relation() != "official") {
                    for (pack in group) {
                        skipped.add(CommunitySkip(pack.id(), "同组存在多个 default，无法决定用哪个"))
                    }
                    continue
                }
                // official 组允许多个 default：按 priority 从高到低选一个，
                // priority 相同时按 id 稳定排序，其余跳过并说明原因。
                val winner = defaults
                    .sortedWith(compareByDescending<CommunityPack> { it.priority() }.thenBy { it.id() })
                    .first()
                winners.add(winner)
                for (pack in group) {
                    if (pack.id() != winner.id()) {
                        skipped.add(
                            CommunitySkip(
                                pack.id(),
                                if (pack.isDefault()) {
                                    "同组多个 default，按 priority 选中 " + winner.id() +
                                        "（priority " + winner.priority() + "）"
                                } else {
                                    "同组已由 default 选中：" + winner.id()
                                }
                            )
                        )
                    }
                }
                continue
            }
            val winner = defaults.firstOrNull() ?: group[0]
            winners.add(winner)
            for (pack in group) {
                if (pack !== winner) {
                    skipped.add(
                        CommunitySkip(
                            pack.id(),
                            if (defaults.isEmpty()) "同组保留第一个：" + winner.id() else "同组已由 default 选中：" + winner.id()
                        )
                    )
                }
            }
        }

        val order = HashMap<String, Int>()
        for ((index, pack) in compatible.withIndex()) {
            order[pack.id()] = index
        }
        winners.sortBy { order[it.id()] ?: Int.MAX_VALUE }

        val officialPlugins = LinkedHashSet<String>()
        for (pack in winners) {
            if (pack.relation() == "official") {
                officialPlugins.add(pack.plugin())
            }
        }

        val selected = ArrayList<CommunityPack>()
        for (pack in winners) {
            when (pack.relation()) {
                "standalone", "official" -> selected.add(pack)
                "addon", "patch" -> {
                    if (officialPlugins.contains(pack.plugin())) {
                        selected.add(pack)
                    } else {
                        skipped.add(CommunitySkip(pack.id(), "缺少同插件 " + pack.plugin() + " 的 official 包"))
                    }
                }
                "integration" -> {
                    val missing = ArrayList<String>()
                    if (!officialPlugins.contains(pack.plugin())) {
                        missing.add(pack.plugin() + "（缺少 official）")
                    }
                    for (other in pack.also()) {
                        if (!context.installed().test(other)) {
                            missing.add(other + "（未安装）")
                        } else if (!officialPlugins.contains(other)) {
                            missing.add(other + "（缺少 official）")
                        }
                    }
                    if (missing.isEmpty()) {
                        selected.add(pack)
                    } else {
                        skipped.add(CommunitySkip(pack.id(), "integration 需要的绑定不完整：" + missing.joinToString("、")))
                    }
                }
                else -> skipped.add(CommunitySkip(pack.id(), "未知绑定关系：" + pack.relation()))
            }
        }

        return CommunitySelection(selected, skipped)
    }

    private fun reject(pack: CommunityPack, context: SelectionContext): String? {
        val relation = pack.relation()
        if (relation !in RELATIONS) {
            return "未知绑定关系：" + relation
        }
        val plugin = pack.plugin()
        if (plugin.isBlank()) {
            return "没有声明绑定插件"
        }
        val standalone = relation == "standalone"
        if (standalone) {
            // standalone 不需要任何插件，但 minecraft / format / requires 仍要检查。
            if (plugin != "universal") {
                return "standalone 必须绑定 universal，实际为 " + plugin
            }
        } else {
            if (plugin == "universal") {
                return "只有 standalone 才能绑定 universal"
            }
            if (!context.installed().test(plugin)) {
                return "插件 " + plugin + " 未安装或未启用"
            }
        }

        val minecraft = pack.minecraft()
        if (minecraft.isNotBlank() && context.minecraftVersion().isNotBlank()) {
            val range = VersionRange.parse(minecraft) ?: return "minecraft 范围不合法：" + minecraft
            if (!range.accepts(context.minecraftVersion())) {
                return "不适用于 Minecraft " + context.minecraftVersion() + "（要求 " + minecraft + "）"
            }
        }

        if (context.packFormat() > 0 && pack.formatMin() > 0) {
            val max = if (pack.formatMax() > 0) pack.formatMax() else pack.formatMin()
            if (context.packFormat() < pack.formatMin() || context.packFormat() > max) {
                return "资源包格式 " + context.packFormat() + " 不在 " + pack.formatMin() + "–" + max + " 内"
            }
        }

        val requires = pack.requires()
        if (requires.isNotBlank()) {
            val version = context.pluginVersion().apply(plugin)
            if (version.isNullOrBlank()) {
                return "无法确认插件 " + plugin + " 的版本（requires " + requires + "）"
            }
            val range = VersionRange.parse(requires) ?: return "requires 表达式不合法：" + requires
            if (!range.accepts(version)) {
                return "插件 " + plugin + " 的版本 " + version + " 不满足 requires " + requires
            }
        }
        return null
    }

    /**
     * official 按插件聚成一组（一个插件只能有一个默认 official）；带 variant 的其它
     * 关系按 plugin + relation + variant 聚组；不带 variant 的包各自独立。
     */
    private fun groupKey(pack: CommunityPack): String {
        if (pack.relation() == "official") {
            return "official|" + pack.plugin()
        }
        if (pack.variant().isBlank()) {
            return "single|" + pack.id()
        }
        return "group|" + pack.plugin() + "|" + pack.relation() + "|" + pack.variant()
    }
}
