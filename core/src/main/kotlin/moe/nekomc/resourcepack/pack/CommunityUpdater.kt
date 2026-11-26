package moe.nekomc.resourcepack.pack

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer

/** 一次远程检查的结果；Java 侧直接读这些访问器。 */
data class RemoteUpdateResult(
    val changed: Boolean,
    val downloaded: Int,
    val skipped: Int,
    val selected: Int,
    val message: String,
)

/**
 * 从 GitHub Pages 拉取 community-index.json，按已安装插件筛选后只下载需要的包。
 *
 * <p>目录布局与服务端离线目录一致：manifest.json + &lt;id&gt;.zip。更新是事务性的：
 * 所有包先落到 community 目录下的唯一 staging 目录，并在 staging 内校验 sha1 与
 * 清单声明的大小；全部成功后才在锁内把旧 zip 移到备份目录、把 staging 文件移入
 * 目标，最后原子写 manifest.json。任一步失败都会恢复旧 zip，旧 manifest 保持
 * 不变；无论成功还是失败都会清理 staging 与备份目录。</p>
 */
class CommunityUpdater(
    directory: Path,
    private val indexUrl: URI,
    private val requireHttps: Boolean,
    private val timeoutMs: Long,
    private val maxPackBytes: Long,
    private val pin: String,
    private val context: SelectionContext,
    private val log: Consumer<String>,
) {

    private val directory: Path = directory.toAbsolutePath().normalize()
    private val manifest: Path = this.directory.resolve(MANIFEST)
    private val lock = ReentrantLock()
    private val etag = AtomicReference("")

    @Volatile
    private var closed = false

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** 关闭更新器：之后 [update] 不再发起请求，也不会替换任何本地文件。 */
    fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            client.shutdownNow()
        } catch (exception: Exception) {
            log.accept("关闭社区更新器连接池失败：" + exception.message)
        }
    }

    /**
     * 检查一次索引。
     *
     * @param force true 时忽略 ETag 并重新拉取索引
     */
    fun update(force: Boolean): RemoteUpdateResult {
        if (closed) {
            return closedResult()
        }
        // HTTPS 预检必须发生在任何 HTTP 请求之前，避免先发一次明文请求。
        if (requireHttps && !isHttps(indexUrl)) {
            return RemoteUpdateResult(false, 0, 0, 0, "索引地址不是 HTTPS：" + indexUrl)
        }
        if (!lock.tryLock()) {
            return RemoteUpdateResult(false, 0, 0, 0, "已有社区资源包更新在进行")
        }
        try {
            if (closed) {
                return closedResult()
            }
            val payload = try {
                fetchIndex(force)
            } catch (exception: NotModifiedException) {
                return RemoteUpdateResult(false, 0, 0, 0, "索引没有变化")
            }
            val index = CommunityIndex.parse(String(payload.bytes, StandardCharsets.UTF_8))
                ?: return RemoteUpdateResult(false, 0, 0, 0, "索引格式不合法，已保留旧清单")

            val ids = index.packs.map { it.pack.id() }.toSet()
            val selection = CommunitySelector.select(index.packs.map { it.pack }, ids, context)
            val selected = ArrayList<IndexedPack>()
            val byId = index.byId()
            for (pack in selection.selected()) {
                val entry = byId[pack.id()]
                if (entry == null) {
                    return RemoteUpdateResult(false, 0, 0, 0, "索引里找不到已选包 " + pack.id())
                }
                selected.add(entry)
            }
            for (skip in selection.skipped().take(5)) {
                log.accept("社区资源包跳过 " + skip.id() + "：" + skip.reason())
            }

            val manifestText = CommunityIndex.manifest(selected)
            Files.createDirectories(directory)
            val staging = Files.createTempDirectory(directory, STAGING_PREFIX)
            try {
                val stagedPacks = ArrayList<StagedPack>()
                var downloaded = 0
                var skipped = 0
                for (entry in selected) {
                    if (closed) {
                        return closedResult()
                    }
                    val staged = download(entry, staging)
                    if (staged == null) {
                        skipped++
                    } else {
                        stagedPacks.add(StagedPack(entry, staged))
                        downloaded++
                    }
                }

                if (closed) {
                    return closedResult()
                }
                val oldManifest = readText(manifest)
                val manifestChanged = manifestText != oldManifest
                if (stagedPacks.isNotEmpty() || manifestChanged) {
                    commit(stagedPacks, manifestText, manifestChanged)
                }
                etag.set(payload.etag)
                val changed = downloaded > 0 || manifestChanged
                val message = if (changed) {
                    "已更新：选中 " + selected.size + " 个，下载 " + downloaded + " 个，跳过 " + skipped + " 个"
                } else {
                    "索引与本地一致，选中 " + selected.size + " 个"
                }
                log.accept("社区资源包远程检查：" + message)
                return RemoteUpdateResult(changed, downloaded, skipped, selected.size, message)
            } finally {
                deleteRecursively(staging)
            }
        } catch (exception: Exception) {
            val message = summarize(exception)
            log.accept("社区资源包远程检查失败，保留旧清单：" + message)
            return RemoteUpdateResult(false, 0, 0, 0, "失败：" + message)
        } finally {
            lock.unlock()
        }
    }

    private fun closedResult(): RemoteUpdateResult {
        return RemoteUpdateResult(false, 0, 0, 0, "更新器已关闭，已放弃本次更新")
    }

    private fun fetchIndex(force: Boolean): IndexPayload {
        val builder = HttpRequest.newBuilder(indexUrl)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
            .header("User-Agent", "resource-pack-service/0.1")
        if (!force && etag.get().isNotBlank()) {
            builder.header("If-None-Match", etag.get())
        }
        val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 304) {
            throw NotModifiedException()
        }
        if (response.statusCode() != 200) {
            throw IOException("索引返回 HTTP " + response.statusCode())
        }
        if (requireHttps && !isHttps(response.uri())) {
            throw IOException("索引被重定向到非 HTTPS 地址")
        }
        val bytes = response.body().use { readLimited(it, MAX_INDEX_BYTES, "索引") }
        if (!pinMatches(bytes)) {
            throw IOException("索引 pin 校验失败")
        }
        val tag = response.headers().firstValue("ETag").orElse("")
        return IndexPayload(bytes, tag)
    }

    /**
     * 把单个包下载到 staging 目录并校验 sha1 与声明大小。
     *
     * @return staging 里的新文件；本地 zip 已一致时返回 null
     */
    private fun download(entry: IndexedPack, staging: Path): Path? {
        val pack = entry.pack
        if (entry.bytes > maxPackBytes) {
            throw IOException("包 " + pack.id() + " 声明大小超过 community.remote.max-pack-bytes")
        }
        val target = directory.resolve(pack.id() + ".zip").normalize()
        if (!target.startsWith(directory)) {
            throw IOException("包 " + pack.id() + " 的落盘路径越界")
        }
        if (Files.isRegularFile(target) && sha1Hex(target).equals(entry.sha1, ignoreCase = true)) {
            return null
        }

        val uri = objectUri(entry.file)
        val response = client.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", "resource-pack-service/0.1")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() != 200) {
            throw IOException("包 " + pack.id() + " 返回 HTTP " + response.statusCode())
        }
        if (requireHttps && !isHttps(response.uri())) {
            throw IOException("包 " + pack.id() + " 被重定向到非 HTTPS 地址")
        }
        val bytes = response.body().use { readLimited(it, maxPackBytes, "包 " + pack.id()) }
        val actual = sha1Hex(bytes)
        if (!actual.equals(entry.sha1, ignoreCase = true)) {
            throw IOException("包 " + pack.id() + " 的 sha1 不匹配（期望 " + entry.sha1 + "，实际 " + actual + "）")
        }
        if (entry.bytes > 0 && bytes.size.toLong() != entry.bytes) {
            throw IOException(
                "包 " + pack.id() + " 的大小不匹配（期望 " + entry.bytes + "，实际 " + bytes.size + "）"
            )
        }
        val staged = staging.resolve(pack.id() + ".zip").normalize()
        if (!staged.startsWith(staging)) {
            throw IOException("包 " + pack.id() + " 的 staging 路径越界")
        }
        Files.write(staged, bytes)
        log.accept("社区资源包已下载 " + pack.id() + "（" + bytes.size + " 字节）")
        return staged
    }

    /**
     * 在锁内提交一次更新：旧目标先移到备份目录，再把 staging 文件移入目标，
     * 最后原子写 manifest。任一步抛异常都会回滚已替换的 zip。
     */
    private fun commit(stagedPacks: List<StagedPack>, manifestText: String, writeManifest: Boolean) {
        val backupDirectory = Files.createTempDirectory(directory, BACKUP_PREFIX)
        val installed = ArrayList<Path>()
        val backups = HashMap<Path, Path>()
        var rollbackClean = true
        try {
            for (item in stagedPacks) {
                val id = item.entry.pack.id()
                val target = directory.resolve(id + ".zip").normalize()
                if (!target.startsWith(directory)) {
                    throw IOException("包 " + id + " 的落盘路径越界")
                }
                if (Files.exists(target)) {
                    val backup = backupDirectory.resolve(id + ".zip")
                    move(target, backup)
                    backups[target] = backup
                }
                // 备份完成后立即登记，staged 文件移入失败时也能由回滚恢复旧 zip。
                installed.add(target)
                move(item.staged, target)
            }
            if (writeManifest) {
                writeAtomic(manifest, manifestText.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (exception: Exception) {
            rollbackClean = rollback(installed, backups)
            throw exception
        } finally {
            // 回滚不完整时保留备份目录，避免把用户仅剩的旧包删掉。
            if (rollbackClean) {
                deleteRecursively(backupDirectory)
            }
        }
    }

    private fun rollback(installed: List<Path>, backups: Map<Path, Path>): Boolean {
        var clean = true
        for (target in installed.asReversed()) {
            val backup = backups[target]
            try {
                if (backup != null && Files.exists(backup)) {
                    move(backup, target)
                } else {
                    Files.deleteIfExists(target)
                }
            } catch (exception: Exception) {
                clean = false
                log.accept("社区资源包回滚失败 " + target.fileName + "：" + summarize(exception))
            }
        }
        return clean
    }

    private fun move(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isHttps(uri: URI): Boolean {
        return "https".equals(uri.scheme, ignoreCase = true)
    }

    private fun objectUri(file: String): URI {
        val resolved = indexUrl.resolve(file)
        if (requireHttps && !isHttps(resolved)) {
            throw IOException("资源包地址不是 HTTPS：" + resolved)
        }
        if (resolved.scheme != "http" && resolved.scheme != "https") {
            throw IOException("资源包地址协议不支持：" + resolved)
        }
        return resolved
    }

    private fun pinMatches(bytes: ByteArray): Boolean {
        if (pin.isBlank()) {
            return true
        }
        val actual = when (pin.length) {
            40 -> digestHex("SHA-1", bytes)
            64 -> digestHex("SHA-256", bytes)
            else -> return false
        }
        return actual.equals(pin, ignoreCase = true)
    }

    private fun summarize(exception: Exception): String {
        val message = exception.message ?: exception.toString()
        return if (message.length <= 300) message else message.substring(0, 300) + "…"
    }

    private fun readText(path: Path): String {
        return try {
            if (Files.isRegularFile(path)) Files.readString(path) else ""
        } catch (exception: Exception) {
            ""
        }
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (exception: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        var incomplete = false
        try {
            val stream = Files.walk(path)
            try {
                stream.sorted(Comparator.reverseOrder()).forEach { item ->
                    try {
                        Files.deleteIfExists(item)
                    } catch (exception: Exception) {
                        incomplete = true
                    }
                }
            } finally {
                try {
                    stream.close()
                } catch (exception: Exception) {
                    incomplete = true
                }
            }
        } catch (exception: Exception) {
            incomplete = true
        }
        if (incomplete) {
            log.accept("社区资源包临时目录未完全清理：" + path.fileName)
        }
    }

    private fun readLimited(input: InputStream, limit: Long, label: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            total += read
            if (total > limit) {
                throw IOException(label + " 超过允许的 " + limit + " 字节")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha1Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun sha1Hex(bytes: ByteArray): String = digestHex("SHA-1", bytes)

    private fun digestHex(algorithm: String, bytes: ByteArray): String {
        return hex(MessageDigest.getInstance(algorithm).digest(bytes))
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return builder.toString()
    }

    class IndexPayload(val bytes: ByteArray, val etag: String)

    private class NotModifiedException : Exception()

    private class StagedPack(val entry: IndexedPack, val staged: Path)

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val STAGING_PREFIX = ".community-staging-"
        private const val BACKUP_PREFIX = ".community-backup-"
        private const val MAX_INDEX_BYTES = 4L * 1024L * 1024L
        private const val HEX = "0123456789abcdef"
    }
}
