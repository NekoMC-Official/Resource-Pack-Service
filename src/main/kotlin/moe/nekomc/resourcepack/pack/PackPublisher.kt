package moe.nekomc.resourcepack.pack

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import moe.nekomc.resourcepack.core.Hashing
import moe.nekomc.resourcepack.core.ZipSource
import moe.nekomc.resourcepack.merge.MergeResult
import moe.nekomc.resourcepack.merge.Merger
import moe.nekomc.resourcepack.merge.MergeUnavailableException
import moe.nekomc.resourcepack.merge.Mergers
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * 合成 + 落盘 + 下发。
 *
 * 构建放在异步线程（rps-pack 是外部进程，不能占主线程），结果以不可变快照对外，
 * 所以 /rps status 和推送永远读到一份自洽的 url/sha1/version。
 */
class PackPublisher(private val plugin: PackHost) {

    private val current = AtomicReference(PackSnapshot.empty())
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val pendingAnnounce = AtomicBoolean(false)
    private val community = java.util.concurrent.atomic.AtomicReference<List<ZipSource>>(emptyList())

    /** 本实例是否仍然有效；reload / onDisable 会把旧实例作废，在途构建据此放弃结果。 */
    private val active = AtomicBoolean(true)

    fun snapshot(): PackSnapshot = current.get()

    /** 社区包按插件绑定筛选后的来源；由插件入口在启动/重载/插件启停时刷新。 */
    fun setCommunitySources(sources: List<ZipSource>): Boolean {
        val next = sources.toList()
        if (sameSources(community.get(), next)) {
            return false
        }
        community.set(next)
        return true
    }

    private fun sameSources(left: List<ZipSource>, right: List<ZipSource>): Boolean {
        if (left.size != right.size) {
            return false
        }
        return left.indices.all { index ->
            val a = left[index]
            val b = right[index]
            a.id() == b.id() && a.enabled() == b.enabled() && a.path() == b.path() &&
                a.sha1() == b.sha1() && a.priority() == b.priority() &&
                a.namespaces() == b.namespaces() && a.version() == b.version()
        }
    }

    fun communitySources(): List<ZipSource> = community.get()

    fun building(): Boolean = running.get()

    /** 作废本实例：在途构建发现 active 为 false 后不再落盘，也不再覆盖对外快照。 */
    fun supersede() {
        active.set(false)
    }

    /** 合并实现的名字，用于状态展示。 */
    fun engineLabel(): String = try {
        Mergers.label(merger())
    } catch (exception: MergeUnavailableException) {
        "未就绪"
    }

    /** 两版条目差异，第一条是汇总。 */
    @JvmOverloads
    fun diffReport(limit: Int = 20): List<String> = EntryManifest.report(plugin.config().outputDir(), limit)

    /** 异步重建；同一时刻只允许一次。 */
    fun rebuild(announce: Boolean) {
        if (!active.get()) {
            plugin.logger().info("发布器已被替换，忽略本次合成请求")
            return
        }
        if (!running.compareAndSet(false, true)) {
            pending.set(true)
            if (announce) {
                pendingAnnounce.set(true)
            }
            plugin.logger().info("已有一次合成在进行，本次请求排队等待")
            return
        }
        val task = Runnable {
            try {
                build(announce)
            } catch (error: Throwable) {
                recordFailure(error)
            } finally {
                running.set(false)
                if (pending.compareAndSet(true, false) && active.get()) {
                    rebuild(pendingAnnounce.getAndSet(false))
                }
            }
        }
        plugin.server().asyncScheduler.runNow(plugin.pluginInstance(), Consumer { task.run() })
    }

    private fun build(announce: Boolean) {
        val config = plugin.config()
        val sources = plugin.registry().ordered().toMutableList()
        sources.addAll(community.get())
        if (sources.isEmpty()) {
            recordFailure(IllegalStateException("还没有登记任何来源"))
            return
        }
        val outputDir = config.outputDir()
        Files.createDirectories(outputDir)
        val target = outputDir.resolve("merged.zip")
        // 跨实例串行化：reload 会换发布器，但输出目录只有一份，旧实例的在途构建不能与新实例同时写。
        buildLock.lock()
        var staging: Path? = null
        try {
            if (!active.get()) {
                plugin.logger().info("发布器已被替换，本次合成在开始前放弃")
                return
            }
            // 每次构建独占一个临时文件，避免两次构建互相覆盖同一份 staging。
            val created = Files.createTempFile(outputDir, "merged-", ".staging.zip")
            staging = created

            val zips = sources.map { source ->
                ZipSource(source.id(), source.enabled(), source.file(), source.sha1(), source.priority(), source.namespaces(), source.version())
            }
            val result: MergeResult = merger().merge(zips, config.packFormat(), config.description(), created)

            val maxBytes = config.maxBytes()
            if (result.bytes() > maxBytes) {
                throw IllegalStateException("合成包 " + result.bytes() + " 字节，超过 merge.max-bytes=" + maxBytes)
            }
            // 写目标前再确认一次：构建期间被 reload/onDisable 作废时，结果直接丢弃。
            if (!active.get()) {
                plugin.logger().info("发布器已被替换，丢弃本次合成结果")
                return
            }
            val sha1 = Hashing.sha1Hex(created)
            val builtAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            if (!active.get()) {
                plugin.logger().info("发布器在写盘前被替换，丢弃本次合成结果")
                return
            }
            Files.move(created, target, StandardCopyOption.REPLACE_EXISTING)
            val changed = plugin.stateStore().commit(sha1, builtAt)
            staging = null
            EntryManifest.rotateAndWrite(outputDir, target)

            val version = plugin.stateStore().generation()
            val delivery = plugin.delivery()
            val base = delivery?.baseUrl().orEmpty()
            val url = if (base.isBlank()) {
                plugin.logger().warning("没有可用的下载地址：delivery.http.enabled 关闭时需要手工设置 delivery.http.public-url")
                ""
            } else {
                withVersion(base, version)
            }
            current.set(PackSnapshot(url, sha1, version, result.bytes(), builtAt, result.summary(), ""))
            try {
                ConflictReport.write(outputDir, current.get(), result)
            } catch (exception: Exception) {
                plugin.logger().warning("写入冲突报告失败：" + (exception.message ?: exception.javaClass.simpleName))
            }
            if (delivery == null) {
                plugin.logger().warning("下发器还没有就绪，本次合成结果不会自动推送")
            } else {
                delivery.publish(url, sha1)
            }
            plugin.logger().info(
                "合成完成：第 " + version + " 版 / " + sources.size + " 个来源 / " + result.summary()
                        + (if (url.isBlank()) " / 无下载地址" else " / " + url)
            )
            if (!changed) {
                plugin.logger().info("内容与上一版一致，跳过重推")
                return
            }
            announce(result)
        } finally {
            val leftover = staging
            if (leftover != null) {
                try {
                    Files.deleteIfExists(leftover)
                } catch (ignored: IOException) {
                    plugin.logger().warning("清理临时合成文件失败：" + leftover)
                }
            }
            buildLock.unlock()
        }
    }

    private fun announce(result: MergeResult) {
        val config = plugin.config()
        val delivery = plugin.delivery() ?: return
        when (config.pushMode().lowercase(Locale.ROOT)) {
            "none" -> return
            "force" -> {
                var pushed = 0
                for (player in plugin.server().onlinePlayers) {
                    pushed += delivery.push(player.uniqueId, true)
                }
                plugin.logger().info("按 force 重推给 " + pushed + " 名玩家")
            }
            else -> {
                val message = MiniMessage.miniMessage().deserialize(config.notifyMessage())
                for (player in plugin.server().onlinePlayers) {
                    player.sendMessage(message)
                }
            }
        }
    }

    private fun recordFailure(error: Throwable) {
        val previous = current.get()
        val detail = error.message ?: error.javaClass.simpleName
        plugin.logger().severe("合成失败：" + detail)
        current.set(
            PackSnapshot(
                previous.url(), previous.sha1(), previous.version(),
                previous.bytes(), previous.builtAt(), previous.summary(), detail
            )
        )
    }

    private fun merger(): Merger =
        Mergers.rust(plugin.config(), plugin.engineConfig(), plugin.dataFolder(), plugin.logger())

    companion object {

        /** 跨实例串行化构建：reload 会换发布器，但输出目录只有一份。 */
        private val buildLock = ReentrantLock()

        /** 给地址拼上版本参数；已有查询串时用 &。 */
        @JvmStatic
        fun withVersion(base: String?, version: Int): String {
            if (base.isNullOrBlank()) {
                return ""
            }
            val value = maxOf(version, 1)
            val separator = if (base.contains('?')) '&' else '?'
            return base + separator + "v=" + value
        }
    }
}
