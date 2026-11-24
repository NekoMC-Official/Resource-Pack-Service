package moe.nekomc.resourcepack.pack

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import moe.nekomc.resourcepack.ResourcePackServicePlugin
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
class PackPublisher(private val plugin: ResourcePackServicePlugin) {

    private val current = AtomicReference(PackSnapshot.empty())
    private val running = AtomicBoolean(false)

    fun snapshot(): PackSnapshot = current.get()

    fun building(): Boolean = running.get()

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
        if (!running.compareAndSet(false, true)) {
            plugin.logger.info("已有一次合成在进行，跳过本次请求")
            return
        }
        val task = Runnable {
            try {
                build(announce)
            } catch (error: Throwable) {
                recordFailure(error)
            } finally {
                running.set(false)
            }
        }
        plugin.server.asyncScheduler.runNow(plugin, Consumer { task.run() })
    }

    private fun build(announce: Boolean) {
        val config = plugin.config()
        val sources = plugin.registry().ordered()
        if (sources.isEmpty()) {
            recordFailure(IllegalStateException("还没有登记任何来源"))
            return
        }
        val outputDir = config.outputDir()
        Files.createDirectories(outputDir)
        val staging = outputDir.resolve("merged.staging.zip")
        val target = outputDir.resolve("merged.zip")

        val zips = sources.map { source ->
            ZipSource(source.id(), source.enabled(), source.file(), source.sha1(), source.priority(), source.namespaces())
        }
        val result: MergeResult = merger().merge(zips, config.packFormat(), config.description(), staging)

        val maxBytes = config.maxBytes()
        if (result.bytes() > maxBytes) {
            throw IllegalStateException("合成包 " + result.bytes() + " 字节，超过 merge.max-bytes=" + maxBytes)
        }
        val sha1 = Hashing.sha1Hex(staging)
        val builtAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val changed = plugin.stateStore().commit(sha1, builtAt)
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        EntryManifest.rotateAndWrite(outputDir, target)

        val version = plugin.stateStore().generation()
        val delivery = plugin.delivery()
        val base = delivery?.baseUrl().orEmpty()
        val url = if (base.isBlank()) {
            plugin.logger.warning("没有可用的下载地址：delivery.http.enabled 关闭时需要手工设置 delivery.http.public-url")
            ""
        } else {
            withVersion(base, version)
        }
        current.set(PackSnapshot(url, sha1, version, result.bytes(), builtAt, result.summary(), ""))
        if (delivery == null) {
            plugin.logger.warning("下发器还没有就绪，本次合成结果不会自动推送")
        } else {
            delivery.publish(url, sha1)
        }
        plugin.logger.info(
            "合成完成：第 " + version + " 版 / " + sources.size + " 个来源 / " + result.summary()
                    + (if (url.isBlank()) " / 无下载地址" else " / " + url)
        )
        if (!changed) {
            plugin.logger.info("内容与上一版一致，跳过重推")
            return
        }
        announce(result)
    }

    private fun announce(result: MergeResult) {
        val config = plugin.config()
        val delivery = plugin.delivery() ?: return
        when (config.pushMode().lowercase(Locale.ROOT)) {
            "none" -> return
            "force" -> {
                var pushed = 0
                for (player in plugin.server.onlinePlayers) {
                    pushed += delivery.push(player.uniqueId, true)
                }
                plugin.logger.info("按 force 重推给 " + pushed + " 名玩家")
            }
            else -> {
                val message = MiniMessage.miniMessage().deserialize(config.notifyMessage())
                for (player in plugin.server.onlinePlayers) {
                    player.sendMessage(message)
                }
            }
        }
    }

    private fun recordFailure(error: Throwable) {
        val previous = current.get()
        val detail = error.message ?: error.javaClass.simpleName
        plugin.logger.severe("合成失败：" + detail)
        current.set(
            PackSnapshot(
                previous.url(), previous.sha1(), previous.version(),
                previous.bytes(), previous.builtAt(), previous.summary(), detail
            )
        )
    }

    private fun merger(): Merger =
        Mergers.rust(plugin.config(), plugin.engineConfig(), plugin.dataFolder.toPath(), plugin.logger)

    companion object {

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
