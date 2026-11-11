package com.u707t.panelfm.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsMediaDataSource
import com.u707t.panelfm.core.vfs.VfsReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 缩略图管线（M9）：
 *  - 内存 LRU + 磁盘缓存（cache/thumbs/<sha1>.png|jpg）
 *  - 本地图片直接采样解码；网络图片按设置（默认仅 Wi-Fi / 小于 3 MB）才加载
 *  - 视频取首帧：本地走文件路径，**网络走 `VfsMediaDataSource`**（Range 随机读，不下载整片）
 *  - 快速滚动时由调用方传入 skip，跳过加载（MT 同款手感）
 *
 * 视频的成本控制写死在 [ThumbCache] 里而不是设置项里：窗口 512 KB、单张预算 8 MB、
 * 单次读 4 秒、整张 2 秒（本地）/ 8 秒（网络）。压缩包内仍然不取帧 —— 随机读要先解包，
 * `readFullyAt` 在压缩包实现上是「从头解到该位置」，比顺序读完还贵。
 */
object ThumbCache {

    private val memory = android.util.LruCache<String, Bitmap>(120)
    private var cacheDir: File? = null

    /**
     * 视频取帧专用调度器：**2 并发**。
     *
     * 不能像图片那样直接 `withContext(Dispatchers.IO)`（那是 64 并发）：
     * `MediaMetadataRetriever` 每取一帧就占住一个 `MediaCodec` 实例，而 codec 是**全局
     * 有限资源**（常见机型 16–32 个）。一屏十几个视频同时解码，轻则抛异常、重则把
     * 正在播的视频的解码器抢掉。图片解码不占这类资源，所以两条路分开。
     *
     * 已知残余风险（可接受，先记在这里）：原生取帧不可中断，超时只是让协程先返回，
     * 卡死的那个线程仍占着槽位。真遇到两个都卡死的坏文件，视频缩略图会停到重启为止
     * —— 图片那条 64 并发不受影响。出现这种投诉再上「独立线程 + 看门狗」。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val videoDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * 近失败过的视频（key → 失败时刻）。
     *
     * 为什么需要：坏文件 / 半写的文件 / 服务器不支持 Range 的源，取帧会稳定失败；
     * 没有这层记忆，每次滚动停下都把同一批文件重试一遍（远端还要付网络）。
     * key 里带 mtime + size，所以「传输刚写完」会自然变成新 key，不会被误拉黑。
     */
    private val videoFailures = android.util.LruCache<String, Long>(64)
    private const val VIDEO_FAILURE_TTL_MS = 5L * 60_000L

    /**
     * 磁盘缓存上限（默认 128 MB）。
     *
     * 旧实现**没有任何淘汰**：`cache/thumbs/<sha1>.png` 只增不减，用户浏览过的每张图都会
     * 永久留一份缩略图。重度使用几个月后就是几百 MB 的隐形占用（且在 cache 目录里，
     * 系统只在「存储告急」时才会动它）。现在每次启动 + 每写 64 张图检查一次总量，
     * 超限按「最久未修改」淘汰到上限的 80%。
     */
    private const val MAX_DISK_BYTES = 128L * 1024 * 1024
    private val writesSincePrune = java.util.concurrent.atomic.AtomicInteger(0)

    fun init(dir: String) {
        cacheDir = File(dir).apply { mkdirs() }
        // 启动时在后台清一次（不阻塞首帧）
        Thread({
            runCatching { pruneDiskCache() }
        }, "panelfm-thumb-prune").apply { isDaemon = true }.start()
    }

    /** 按「最久未修改」把磁盘缓存淘汰到上限的 80%；返回是否做过删除。 */
    private fun pruneDiskCache(): Boolean {
        val dir = cacheDir ?: return false
        val files = dir.listFiles()?.filter { it.isFile } ?: return false
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return false
        val target = (MAX_DISK_BYTES * 8 / 10)
        // 最老的先删；同一次写入的 .tmp 之类也一并参与统计
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= target) return@forEach
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
        return true
    }

    private fun keyOf(item: FileMetadata): String =
        item.uri.toString() + "#" + item.lastModified + "#" + item.size

    /**
     * 缓存文件名。视频帧写 `.jpg`：96px 的帧 PNG 要 3–15 KB、JPEG 只要 0.4–0.7 KB（实测），
     * 而 JPEG 在这么小的尺寸上看不出差别 —— 用 PNG 会让视频帧吃掉磁盘缓存的大头。
     */
    private fun fileOf(key: String, jpeg: Boolean = false): File? {
        val dir = cacheDir ?: return null
        // Locale.ROOT：哈希文件名必须是固定 ASCII（本地化数字会让不同语言下缓存 key 不一致）
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return File(dir, "$hash${if (jpeg) ".jpg" else ".png"}")
    }

    suspend fun load(
        container: AppContainer,
        item: FileMetadata,
        targetPx: Int,
        allowRemote: Boolean,
        maxBytes: Long = MAX_REMOTE_SIZE,
    ): Bitmap? {
        val key = keyOf(item)
        memory.get(key)?.let { return it }
        val cached = fileOf(key)?.takeIf { it.exists() }
        if (cached != null) {
            val bm = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(cached.absolutePath) }
            if (bm != null) {
                memory.put(key, bm)
                return bm
            }
        }
        // MT「超过 X 大小的图片文件不加载缩略图」（maxBytes = 0 表示不限制）
        if (item.uri.scheme != "local" && (!allowRemote || item.size < 0 || (maxBytes > 0 && item.size > maxBytes))) return null

        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (item.uri.scheme == "local") {
                    // 本地：先读边界再采样，避免大图 OOM
                    val path = container.localVfs.absolutePath(item.uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(path, opts)
                } else {
                    val vfs = container.locator.find(item.uri) ?: return@runCatching null
                    val reader = vfs.openRead(item.uri)
                    val out = ByteArrayOutputStream()
                    try {
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (maxBytes <= 0 || total < maxBytes) {
                            val n = reader.read(buf, 0, buf.size)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    } finally {
                        runCatching { reader.close() }
                    }
                    val bytes = out.toByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
                bytes
            }.getOrNull()
        } ?: return null

        memory.put(key, bitmap)
        writeDisk(key, bitmap, jpeg = false)
        return bitmap
    }

    /**
     * 视频首帧。不可用（格式不支持 / 文件损坏 / 服务器不给 Range / 超预算）返回 null，
     * 由调用方回退类型图标；失败会记进 [videoFailures]，5 分钟内不再重试同一版本的文件。
     *
     * 只取 **t=0 的同步帧**：`OPTION_CLOSEST_SYNC` 配中间时间点会从上一个大关键帧开始追帧，
     * 实测 1080p / GOP 250 时贵 20 倍（2.2 s CPU vs 0.09 s），而换来「更好看的封面」在
     * 28dp 的行图标上根本看不出来。API 27 起用 `getScaledFrameAtTime` 让解码器直接出小图，
     * 避免 4K 整帧（33 MB）进内存。
     */
    suspend fun loadVideo(container: AppContainer, item: FileMetadata, targetPx: Int): Bitmap? {
        val key = keyOf(item)
        if (recentlyFailed(key)) return null
        memory.get(key)?.let { return it }
        fileOf(key, jpeg = true)?.takeIf { it.exists() }?.let { cached ->
            val bm = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(cached.absolutePath) }
            if (bm != null) {
                memory.put(key, bm)
                return bm
            }
        }
        val bitmap = withContext(videoDispatcher) {
            // 取消（滚走 / 超时）不是「坏文件」：必须抛出去，别记进失败表
            runCatching { firstFrame(container, item, targetPx) }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
        }
        if (bitmap == null) {
            markFailed(key)
            return null
        }
        videoFailures.remove(key)
        memory.put(key, bitmap)
        writeDisk(key, bitmap, jpeg = true)
        return bitmap
    }

    /** 取首帧。[firstFrame] 里所有资源都在 finally 释放 —— 漏一处就泄漏一个 codec 或一条连接。 */
    private fun firstFrame(container: AppContainer, item: FileMetadata, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var source: VfsMediaDataSource? = null
        var reader: VfsReader? = null
        try {
            if (item.uri.scheme == "local") {
                retriever.setDataSource(container.localVfs.absolutePath(item.uri))
            } else {
                val vfs = container.locator.find(item.uri) ?: return null
                val r = vfs.openRead(item.uri)
                reader = r
                // 目录列表里的 size 才是现成的：WebDAV 的 reader.size 要读过一次才有值
                val size = r.size?.takeIf { it > 0 } ?: item.size
                if (size <= 0) return null
                val src = VfsMediaDataSource(r, size)
                source = src
                retriever.setDataSource(src)
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetPx, targetPx,
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { fitInto(it, targetPx) }
            }
        } finally {
            runCatching { retriever.release() }
            runCatching { source?.close() }
            runCatching { reader?.close() }
        }
    }

    private fun recentlyFailed(key: String): Boolean {
        val at = videoFailures.get(key) ?: return false
        return System.currentTimeMillis() - at < VIDEO_FAILURE_TTL_MS
    }

    private fun markFailed(key: String) {
        videoFailures.put(key, System.currentTimeMillis())
    }

    /** 写磁盘缓存，并每 64 张检查一次上限（都在 IO 上做，不阻塞缩略图返回） */
    private suspend fun writeDisk(key: String, bitmap: Bitmap, jpeg: Boolean) {
        runCatching {
            fileOf(key, jpeg)?.let { f ->
                withContext(Dispatchers.IO) {
                    f.outputStream().use {
                        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        bitmap.compress(format, if (jpeg) 82 else 80, it)
                    }
                }
            }
        }
        if (writesSincePrune.incrementAndGet() % 64 == 0) {
            withContext(Dispatchers.IO) { runCatching { pruneDiskCache() } }
        }
    }

    /** 把整帧缩到最长边 = [targetPx]（API 26 没有 `getScaledFrameAtTime` 时的兜底） */
    private fun fitInto(frame: Bitmap, targetPx: Int): Bitmap {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= targetPx) return frame
        val ratio = targetPx.toFloat() / longest
        val w = (frame.width * ratio).toInt().coerceAtLeast(1)
        val h = (frame.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    fun clear() {
        memory.evictAll()
        cacheDir?.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private const val MAX_REMOTE_SIZE = 3L * 1024 * 1024
}

/**
 * 缩略图策略参数。
 *
 * 为什么不在这里 `collectAsState`：`rememberThumb` 是**每一行**都会调用的，
 * 每行各自订阅 settings 时，列表滚动期间会产生 N 份订阅与 N 次重组；
 * 由 PaneView 顶层取一次、按参数下传即可。
 */
data class ThumbPolicy(
    val onMobileData: Boolean,
    val maxBytes: Long,
)

/**
 * 单张**图片**缩略图的加载上限（v2.1.4 起固定 5 秒，不再暴露成设置项）。
 *
 * 为什么必须有上限：远端（SFTP / WebDAV / SMB）读一张坏图可能一直不返回，
 * 没有超时就是一条协程永久挂起；5 秒足够覆盖慢速网络的首包，超时就回退类型图标。
 */
private const val IMAGE_LOAD_TIMEOUT_MS = 5_000L

/**
 * 单张**本地视频**首帧的加载上限（2 秒）。
 *
 * 本机取帧正常是 30–100 ms，2 秒已经是 20 倍冗余；而 [ThumbCache] 的视频取帧只有
 * **2 个并发槽**，沿用图片的 5 秒意味着一个坏文件能把半条流水线按住 5 秒 ——
 * 一屏里两三个坏视频，用户看到的就是「缩略图半天不冒出来」。
 */
private const val VIDEO_LOCAL_TIMEOUT_MS = 2_000L

/**
 * 单张**网络视频**首帧的加载上限（8 秒）。
 *
 * 网络比本地多两件事：解 moov 要几发 Range 请求（WebDAV 正常 100–300 ms 一发），
 * 慢速 NAS / 跨公网 WebDAV 会再慢一个量级。8 秒覆盖「3–4 发请求 + 一次解码」的余量，
 * 又短到不会让一屏 NAS 视频把流水线按几分钟 —— 配合 [ThumbCache] 的 5 分钟失败记忆，
 * 坏源只会付一次代价。
 */
private const val REMOTE_VIDEO_TIMEOUT_MS = 8_000L

/** 列表行缩略图的来源：不加载 / 图片 / 本地视频首帧 / 网络视频首帧 */
internal enum class ThumbSource { NONE, IMAGE, VIDEO_LOCAL, VIDEO_REMOTE }

/**
 * 允许给视频取首帧的协议。
 *
 * 不含 `ftp` / `ftps`：FTP 在这套 VFS 里是**顺序流** —— `seek` 只接受原位，
 * `readFullyAt` 只允许「从 0 起、流未被消费、≤64 KB」的预读。解复用器要的随机读它给不了，
 * 放进白名单只会让每个 FTP 视频白失败一次再进 5 分钟失败表。
 *
 * 不含 `archive`：压缩包内的随机读要先解包，`readFullyAt` 在压缩包实现上是
 * 「从头解到该位置」，比顺序读完整段还贵 —— 一屏 zip 里的视频会把 CPU 打满。
 */
private val VIDEO_SCHEMES = setOf("local", "dav", "sftp", "smb", "s3")

/**
 * 某一行该不该取缩略图（纯函数，便于单测）。
 *
 * 图片：本地 + 所有远端协议（远端另有「连接开关 / 仅 Wi-Fi / 大小上限」三道闸）。
 * 视频：本地 + 上面 [VIDEO_SCHEMES] 里的网络协议；远端视频走 `VfsMediaDataSource`
 * 按 Range 随机读首帧，窗口 512 KB、预算 8 MB，不下载整片。
 */
internal fun thumbSourceOf(extension: String, isDirectory: Boolean, scheme: String): ThumbSource {
    if (isDirectory) return ThumbSource.NONE
    val kind = MimeTypes.kindOf(extension)
    if (kind == MimeTypes.Kind.IMAGE) return ThumbSource.IMAGE
    if (kind == MimeTypes.Kind.VIDEO && scheme in VIDEO_SCHEMES) {
        return if (scheme == "local") ThumbSource.VIDEO_LOCAL else ThumbSource.VIDEO_REMOTE
    }
    return ThumbSource.NONE
}

/** 列表行里的缩略图（不可用/未加载时返回 null，由调用方回退到类型图标） */
@Composable
fun rememberThumb(
    container: AppContainer,
    item: FileMetadata,
    targetPx: Int,
    skip: Boolean,
    policy: ThumbPolicy,
): ImageBitmap? {
    val source = thumbSourceOf(item.extension, item.isDirectory, item.uri.scheme)
    if (source == ThumbSource.NONE || skip) return null
    // 连接级开关：编辑连接 → 缩略图选项 → 关闭后该连接不加载缩略图（本地视频恒为本地，不走这里）
    if (source != ThumbSource.VIDEO_LOCAL && item.uri.scheme != "local") {
        val cfg = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(item.uri))
            ?: container.connectionByAuthority(item.uri.scheme, item.uri.authority)
        if (cfg?.option(com.u707t.panelfm.core.model.ConnectionConfig.OPT_LOAD_THUMBS) == "false") return null
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = produceState<ImageBitmap?>(
        initialValue = null,
        item.uri.toString(),
        skip,
        policy,
    ) {
        // Wi-Fi 默认加载；移动数据下按「移动数据下加载缩略图」设置（切换设置会刷新加载行为）
        val allowRemote = com.u707t.panelfm.LocalNetwork.isOnWifi(context) || policy.onMobileData
        val timeoutMs = when (source) {
            ThumbSource.VIDEO_LOCAL -> VIDEO_LOCAL_TIMEOUT_MS
            ThumbSource.VIDEO_REMOTE -> REMOTE_VIDEO_TIMEOUT_MS
            else -> IMAGE_LOAD_TIMEOUT_MS
        }
        val bitmap = if (source == ThumbSource.VIDEO_REMOTE && !allowRemote) {
            null
        } else {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                if (source == ThumbSource.VIDEO_LOCAL || source == ThumbSource.VIDEO_REMOTE) {
                    ThumbCache.loadVideo(container, item, targetPx)
                } else {
                    ThumbCache.load(container, item, targetPx, allowRemote, policy.maxBytes)
                }
            }
        }
        value = bitmap?.asImageBitmap()
    }
    return state.value
}
