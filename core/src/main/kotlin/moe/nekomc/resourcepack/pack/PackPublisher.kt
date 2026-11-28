package moe.nekomc.resourcepack.pack

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import moe.nekomc.resourcepack.api.BuildResult
import moe.nekomc.resourcepack.config.RpsConfig
import moe.nekomc.resourcepack.core.ZipSource
import moe.nekomc.resourcepack.delivery.FanoutController
import moe.nekomc.resourcepack.merge.MergeResult
import moe.nekomc.resourcepack.merge.Merger
import moe.nekomc.resourcepack.merge.MergeUnavailableException
import moe.nekomc.resourcepack.merge.Mergers

/**
 * 合成 + 落盘 + 下发。
 *
 * 构建放在异步线程（rps-pack 是外部进程，不能占主线程），结果以不可变快照对外，
 * 所以 /rps status 和推送永远读到一份自洽的 url/sha1/version。
 */
class PackPublisher(private val plugin: PackHost) {

    /** 等待者绑定到"第几轮构建"：本轮开始后才注册的等待者由下一轮完成，不会被旧结果提前唤醒。 */
    private data class Waiter(val round: Int, val future: CompletableFuture<BuildResult>)

    private val current = AtomicReference(PackSnapshot.empty())
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val pendingAnnounce = AtomicBoolean(false)
    private val roundSeq = AtomicInteger(0)
    private val waiters = ConcurrentLinkedQueue<Waiter>()
    private val community = AtomicReference<List<ZipSource>>(emptyList())
    private val fanoutRef = AtomicReference<FanoutController?>(null)

    /** 本实例是否仍然有效；reload / onDisable 会把旧实例作废，在途构建据此放弃结果。 */
    private val active = AtomicBoolean(true)

    /** 上一轮成功构建的输入指纹；输入未变时直接复用产物，不再起外部进程。 */
    private val lastFingerprint = AtomicReference<String?>(null)

    fun snapshot(): PackSnapshot = current.get()

    /** 只读探针：reload 之后旧实例会变成 false，测试与诊断用。 */
    fun isActive(): Boolean = active.get()

    /** 当前构建结果；失败时保留上一版字段并带 error。 */
    fun buildResult(): BuildResult {
        val value = current.get()
        val ok = value.version() > 0 && value.url().isNotBlank() && value.error().isBlank()
        return BuildResult(ok, value.sha1(), value.version().toLong(), value.url(), value.entries(), value.bytes(), value.error().ifBlank { null })
    }

    /** 社区包按插件绑定筛选后的来源；由插件入口在启动/重载/插件启停时刷新。 */
    fun setCommunitySources(sources: List<ZipSource>): Boolean {
        val next = sources.toList()
        if (sameSources(community.get(), next)) {
            return false
        }
        community.set(next)
        return true
    }

    fun sameSources(left: List<ZipSource>, right: List<ZipSource>): Boolean {
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

    /** 发起构建并等待本轮（或合并后的下一轮）结果。 */
    fun rebuildAsync(announce: Boolean): CompletableFuture<BuildResult> {
        if (!active.get()) {
            return CompletableFuture.completedFuture(BuildResult.unavailable("发布器已被替换"))
        }
        val waiter = Waiter(roundSeq.get() + 1, CompletableFuture())
        waiters.add(waiter)
        rebuild(announce)
        if (!active.get()) {
            completeAllWaiters(BuildResult.unavailable("发布器已被替换"))
        }
        return waiter.future
    }

    /** 作废本实例：在途构建发现 active 为 false 后不再落盘，也不再覆盖对外快照。 */
    fun supersede() {
        active.set(false)
        fanoutRef.getAndSet(null)?.close()
        completeAllWaiters(BuildResult.unavailable("发布器已被替换"))
    }

    /**
     * 统一推送调度器；下发器未就绪时返回 null，调用方退回逐个推送。
     */
    fun fanout(): FanoutController? {
        val delivery = plugin.delivery() ?: return null
        fanoutRef.get()?.let { return it }
        synchronized(this) {
            fanoutRef.get()?.let { return it }
            val config = plugin.config()
            val created = FanoutController(
                delivery,
                plugin.scheduler(),
                plugin.logger(),
                config.deliveryFanoutMaxConcurrent(),
                config.deliveryFanoutJitterMs(),
                config.deliveryFanoutRetry(),
                config.deliveryFanoutRamp(),
            )
            fanoutRef.set(created)
            return created
        }
    }

    /** 已存在的推送调度器；不创建。供退服等生命周期回调使用。 */
    fun fanoutOrNull(): FanoutController? = fanoutRef.get()

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
            val round = roundSeq.incrementAndGet()
            try {
                build(announce, round)
            } catch (error: Throwable) {
                recordFailure(error, round)
            } finally {
                running.set(false)
                if (pending.compareAndSet(true, false) && active.get()) {
                    rebuild(pendingAnnounce.getAndSet(false))
                }
            }
        }
        try {
            plugin.scheduler().runAsyncNow(task)
        } catch (error: Throwable) {
            // 调度不出去（插件已在关闭中）就不能让 running 卡死，也不能让等待者永远悬挂。
            running.set(false)
            val message = "无法排程合成：" + (error.message ?: error.javaClass.simpleName)
            plugin.logger().warning(message)
            completeAllWaiters(BuildResult.unavailable(message))
        }
    }

    private fun build(announce: Boolean, round: Int) {
        val config = plugin.config()
        // enabled: false 的来源保留在配置里用于展示，但不参与合成。
        val sources = plugin.registry().ordered().filter { it.enabled() }.toMutableList()
        sources.addAll(community.get())
        if (sources.isEmpty()) {
            recordFailure(IllegalStateException("还没有可用的来源（检查 sources 是否都是 enabled: false）"), round)
            return
        }
        val outputDir = config.outputDir()
        Files.createDirectories(outputDir)
        val target = outputDir.resolve("merged.zip")
        val fingerprint = fingerprintOf(sources, config)
        if (fingerprint == lastFingerprint.get() && Files.isRegularFile(target) && Files.size(target) > 0L) {
            plugin.logger().fine("输入未变，跳过重建（保持当前版本）")
            completeWaiters(buildResult(), round)
            return
        }
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
            // 合并器在写出的同时算好了 SHA-1，这里不再整包回读一遍。
            val sha1 = result.sha1()
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
            // 包已经落盘：清单写失败只能告警，不能把这一版判成失败（否则 current 回滚、ETag 僵死）。
            try {
                EntryManifest.rotateAndWrite(outputDir, target)
            } catch (exception: Exception) {
                plugin.logger().warning("写入条目清单失败：" + (exception.message ?: exception.javaClass.simpleName))
            }

            val version = plugin.stateStore().generation()
            val delivery = plugin.delivery()
            val base = delivery?.baseUrl().orEmpty()
            val url = if (base.isBlank()) {
                plugin.logger().warning("没有可用的下载地址：delivery.http.enabled 关闭时需要手工设置 delivery.http.public-url")
                ""
            } else {
                withVersion(base, version)
            }
            current.set(PackSnapshot(url, sha1, version, result.bytes(), result.entries(), builtAt, result.summary(), ""))
            try {
                ConflictReport.write(outputDir, current.get(), result)
            } catch (exception: Exception) {
                plugin.logger().warning("写入冲突报告失败：" + (exception.message ?: exception.javaClass.simpleName))
            }
            completeWaiters(buildResult(), round)
            if (delivery == null) {
                plugin.logger().warning("下发器还没有就绪，本次合成结果不会自动推送")
            } else {
                delivery.publish(url, sha1)
            }
            lastFingerprint.set(fingerprint)
            plugin.logger().info(
                "合成完成：第 " + version + " 版 / " + sources.size + " 个来源 / " + result.summary()
                        + (if (url.isBlank()) " / 无下载地址" else " / " + url)
            )
            if (!changed) {
                plugin.logger().info("内容与上一版一致，跳过重推")
                return
            }
            try {
                announce(result)
            } catch (exception: Exception) {
                plugin.logger().warning("推送通知失败：" + (exception.message ?: exception.javaClass.simpleName))
            }
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
                val targets = plugin.onlinePlayers().map { it.uniqueId() }
                val controller = fanout()
                if (controller != null) {
                    // force 不看客户端已加载状态，空 sha1 表示无条件重推
                    controller.enqueue(targets, "", false)
                    plugin.logger().info("按 force 排队重推 " + targets.size + " 名玩家")
                } else {
                    var pushed = 0
                    for (player in plugin.onlinePlayers()) {
                        pushed += delivery.push(player.uniqueId(), true)
                    }
                    plugin.logger().info("按 force 重推给 " + pushed + " 名玩家")
                }
            }
            else -> {
                val message = config.notifyMessage()
                for (player in plugin.onlinePlayers()) {
                    player.sendMessage(message)
                }
            }
        }
    }

    /** 输入指纹：影响产物内容的配置 + 每个来源的元信息与文件大小/修改时间。 */
    private fun fingerprintOf(sources: List<moe.nekomc.resourcepack.api.PackSource>, config: RpsConfig): String {
        val fingerprint = SourceFingerprint.create()
        fingerprint.add(config.packFormat().toString())
        fingerprint.add(config.description())
        sources.forEach { source ->
            fingerprint.add(source.id())
            fingerprint.add(source.enabled().toString())
            fingerprint.add(source.priority().toString())
            fingerprint.add(source.sha1())
            fingerprint.add(source.version())
            val file = source.file()
            fingerprint.add(
                if (Files.isRegularFile(file)) {
                    Files.size(file).toString() + ":" + Files.getLastModifiedTime(file).toMillis()
                } else {
                    "missing"
                }
            )
        }
        return fingerprint.hex()
    }

    private fun recordFailure(error: Throwable, round: Int) {
        val previous = current.get()
        val detail = error.message ?: error.javaClass.simpleName
        plugin.logger().severe("合成失败：" + detail)
        current.set(previous.failed(detail))
        completeWaiters(buildResult(), round)
    }

    /** 只完成本轮及更早的等待者；本轮之后登记的留给下一次构建。 */
    private fun completeWaiters(result: BuildResult, round: Int) {
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.round <= round) {
                iterator.remove()
                waiter.future.complete(result)
            }
        }
    }

    private fun completeAllWaiters(result: BuildResult) {
        while (true) {
            val waiter = waiters.poll() ?: return
            waiter.future.complete(result)
        }
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
