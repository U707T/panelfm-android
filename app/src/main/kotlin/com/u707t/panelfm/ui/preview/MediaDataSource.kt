package com.u707t.panelfm.ui.preview

// ================================================================================================
// MediaScreen 拆分（2026-10-08 重审 §2）：播放地址 / Media3 数据源 / 错误文案
//  - panelfm:// 播放地址的构造与解析（纯函数，语义由 MediaUriTest 锁定）
//  - MediaItem 构造（本地 file:// 直读 / 其余走 VfsDataSource）
//  - VfsDataSource（统一 VFS → Media3 数据源，事件严格成对）
//  - localPathOf / preflightMedia：本地路径判定与播放前可读性预检
// ================================================================================================

import androidx.core.net.toUri
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.*
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 把 Media3 的 [PlaybackException] 翻译成**可执行的中文提示**。
 *
 * Media3 原始 message 是英文技术细节（`Source error` / `Decoder init failed` …），
 * 对用户没有指导意义。这里按 [PlaybackException.errorCode] 给出下一步动作，
 * 并在末尾附上简短原因，便于用户截图反馈。
 */
fun describePlaybackError(e: Throwable): String {
    if (e !is PlaybackException) {
        return "无法开始播放：${e.message ?: e::class.java.simpleName}"
    }
    val code = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在（可能已被移动或删除）"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取权限（可能需要「所有文件访问」授权）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败（检查存储是否在线）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络超时（存储响应过慢或已离线）"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误状态（存储端可能拒绝访问）"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "读取失败（存储可能已断开）"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "文件已损坏（容器格式不完整）"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的封装格式（可试试「其他应用打开」）"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "清单文件已损坏"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "不支持的流媒体清单"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败（可能是编码格式不支持）"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "设备缺少可用的解码器"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败（文件可能损坏或编码异常）"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "不支持的编码格式（如部分 HEVC / AV1）"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频轨初始化失败"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频输出失败"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "设备解码能力不足（如 4K / 高码率）"
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "系统回收了解码器（可重试）"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "服务器返回的内容类型不合法"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许明文 HTTP（请用 https）"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "读取位置越界（文件可能被截断）"
        PlaybackException.ERROR_CODE_TIMEOUT -> "操作超时"
        PlaybackException.ERROR_CODE_PERMISSION_DENIED -> "没有权限"
        PlaybackException.ERROR_CODE_NOT_SUPPORTED -> "当前播放器不支持该内容"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        -> "DRM 保护内容无法播放"
        else -> "播放失败"
    }
    val reason = e.cause?.message ?: e.message
    return if (reason.isNullOrBlank()) code else "$code\n（$reason）"
}

/**
 * 构造 Media3 的 [MediaItem]：**同时给出真实文件名后缀与 mimeType**。
 *
 * 为什么两者都要：
 *  - path 里的后缀 → `Util.inferContentType` 判容器（m3u8/mpd/其它）
 *  - `setMimeType` → `DefaultMediaSourceFactory` 用它选 Extractor / 渲染器，
 *    并在 `inferContentTypeForUriAndMimeType` 里优先于后缀
 *
 * 只给其中任一都可能让 ExoPlayer 选错（或选不到）提取器 → 黑屏 / 播放失败。
 */
fun mediaItemFor(vfsUri: VfsUri, localPath: String? = null): MediaItem {
    // 本地文件优先走 **file://**：由 Media3 自带的 FileDataSource 直接读，
    // 完全绕开自定义 scheme + VfsDataSource + runBlocking 那一整条链路
    // （本地是最常见的场景，少一层就少一类失败可能）。
    // 网络 / 压缩包内 / 无本地路径时仍走 panelfm://。
    val uri = if (localPath != null) Uri.fromFile(java.io.File(localPath)) else mediaUriFor(vfsUri)
    val builder = MediaItem.Builder().setUri(uri)
    mimeTypeForName(vfsUri.name)?.let { builder.setMimeType(it) }
    return builder.build()
}

/**
 * 按扩展名给 MIME（只覆盖常见容器；未命中返回 null 交给 Media3 自己按后缀推断）。
 *
 * 注意 mp4 家族要区分：`.mp4` 是 `video/mp4`，`.m4a` 是 `audio/mp4`，
 * 混用会让音频轨的渲染器选择偏掉。
 */
fun mimeTypeForName(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "m4a", "m4b", "m4r" -> "audio/mp4"
        "mkv" -> "video/x-matroska"
        "mka" -> "audio/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "3gp", "3gpp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "f4v" -> "video/x-f4v"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "amr" -> "audio/amr"
        "awb" -> "audio/amr-wb"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> null
    }

/** 播放地址的 scheme / authority（`panelfm://vfs/...`） */
const val MEDIA_SCHEME = "panelfm"
const val MEDIA_AUTHORITY = "vfs"

/** query 里放完整 VFS URI 的参数名 */
const val MEDIA_PARAM_VFS = "u"

/**
 * 构造播放地址字符串（**纯函数，便于单测**；[mediaUriFor] 只是把它包成 `Uri`）。
 *
 * ## ⚠️ 路径里必须保留真实文件名（不能只放 query）
 *
 * Media3 用 `Uri.getLastPathSegment()` 的后缀来推断容器类型
 * （`Util.inferContentType` → m3u8/mpd/其它）。
 * 如果写成 `panelfm://vfs?u=...`，`getLastPathSegment()` 拿到的是 authority `vfs`
 * （没有点号）→ 类型恒为 `CONTENT_TYPE_OTHER`，
 * 且 `MediaItem.fromUri` 的 mimeType 为 null，**部分容器的 Extractor 选不出来 → 播放失败**。
 *
 * 所以把**真实文件名**放在 path 末尾，query 里再放编码后的完整 VFS URI：
 *   `panelfm://vfs/movie.mp4?u=local%3A%2F%2Femulated%2FDownload%2Fmovie.mp4`
 * 这样 `inferContentType` 能按 `.mp4` / `.mkv` / `.m3u8` 正确判型。
 */
fun mediaUriString(vfsUri: VfsUri): String =
    "$MEDIA_SCHEME://$MEDIA_AUTHORITY/${percentEncode(vfsUri.name.ifBlank { "media" })}" +
        "?$MEDIA_PARAM_VFS=${percentEncode(vfsUri.toString())}"

/**
 * 从播放地址解回 VFS URI（**纯函数**；[VfsDataSource] 用它）。
 *
 * 只认 `panelfm://vfs/...?u=...`；`u` 缺失或解析失败返回 null。
 */
fun vfsUriFromMediaUri(mediaUri: String): VfsUri? {
    val marker = "?$MEDIA_PARAM_VFS="
    val idx = mediaUri.indexOf(marker)
    if (idx < 0) return null
    val raw = mediaUri.substring(idx + marker.length).substringBefore('&')
    if (raw.isEmpty()) return null
    return runCatching { VfsUri.parse(percentDecode(raw)) }.getOrNull()
}

/**
 * 百分号编码（与 Android `Uri.getQueryParameter` / `getLastPathSegment` 的解码规则对齐）。
 *
 * `URLEncoder` 会把空格编成 `+`，而 Android 的 `Uri.decode` **不会**把 `+` 当空格，
 * 所以必须再把 `+` 换成 `%20`，否则「我的 视频.mp4」这类文件名会带出 `+`。
 */
internal fun percentEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** 百分号解码（`+` 不当空格，与上面成对） */
internal fun percentDecode(value: String): String =
    java.net.URLDecoder.decode(value, "UTF-8")

/** 把 VFS URI 编码成 Media3 可用的 Uri（自定义 `panelfm://` 方案由 [VfsDataSource] 解回）。 */
fun mediaUriFor(vfsUri: VfsUri): Uri = mediaUriString(vfsUri).toUri()

/**
 * Media3 数据源工厂：**通用**（`panelfm://` 走统一 VFS，标准 scheme 交给 Media3 自带数据源）。
 *
 * ## 为什么必须同时支持标准 scheme
 *
 * `DefaultMediaSourceFactory(DataSource.Factory)` 会把**唯一**这个工厂用于所有请求。
 * 如果只认 `panelfm://`，那么本地文件走 `file://`（`Uri.fromFile`）时
 * 就会在 `open()` 里抛「非法媒体地址」—— 看起来像「视频无法播放」。
 * 所以这里对非 `panelfm://` 的请求**委托**给 `DefaultDataSource`
 * （它内部按 scheme 分派：file / content / asset / http(s) / data …）。
 */
@androidx.media3.common.util.UnstableApi
class VfsDataSourceFactory(
    private val locator: VfsLocator,
    context: android.content.Context,
    /**
     * 会话解析器：找不到会话时**允许重连**。
     * 默认实现只做只读查找（便于单测 / 兼容旧调用点）。
     */
    private val resolver: suspend (VfsUri) -> com.u707t.panelfm.core.vfs.VirtualFileSystem? = { locator.find(it) },
) : DataSource.Factory {

    /** Media3 自带的分派数据源（file:// / content:// / http(s):// …） */
    private val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource =
        VfsDataSource(locator, defaultFactory.createDataSource(), resolver)
}

/**
 * Media3 数据源：把播放器的读取接到统一 VFS（本地 / SFTP / WebDAV / SMB / S3 通吃）。
 *
 * [BaseDataSource] 的 `isNetwork` 参数影响 Media3 的**加载线程与重试策略**：
 * 网络数据源会走更宽松的超时与重试。这里传 `true`（保守取值）：
 * 同一套代码既要读本地也要读网络，标成网络只是让 Media3 用更宽容的策略，本地读取不受影响。
 */
@androidx.media3.common.util.UnstableApi
class VfsDataSource(
    private val locator: VfsLocator,
    /**
     * 标准 scheme（file / content / http(s) …）的委托数据源。
     * 见 [VfsDataSourceFactory] 的说明：工厂是唯一的，必须能处理所有 scheme。
     */
    private val delegate: DataSource? = null,
    /** 会话解析器（可重连）；为 null 时退回只读的 [locator.find] */
    private val resolver: (suspend (VfsUri) -> com.u707t.panelfm.core.vfs.VirtualFileSystem?)? = null,
) : BaseDataSource(true) {

    private var reader: VfsReader? = null
    private var target: VfsUri? = null
    private var remaining: Long = -1L

    /** 本次 open 是否交给了 [delegate]（close / read 也要跟着走） */
    private var delegated = false

    /**
     * 是否已经 `transferStarted`。
     *
     * [BaseDataSource] 的事件必须**严格成对**：`transferInitializing` → `transferStarted`
     * → `bytesTransferred` → `transferEnded`。
     * 早期实现在 `open()` 里先 `transferInitializing` 再 `openRead`，
     * 如果 `openRead` 抛异常（文件被删 / 权限不足 / 会话断开），`transferStarted` 就不会执行，
     * 但 `close()` 仍会调 `transferEnded` —— 事件不成对，
     * Media3 的 `TransferListener`（含我们注册的统计）会记出负数/错乱。
     */
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        // 用纯函数解回（与 mediaUriString 成对，规则写在一处，避免编码/解码不对称）
        val vfsUri = vfsUriFromMediaUri(dataSpec.uri.toString())
        if (vfsUri == null) {
            // 不是 panelfm:// → 标准 scheme，交给 Media3 自带数据源
            val d = delegate ?: throw IOException("不支持的数据源：${dataSpec.uri}")
            delegated = true
            return d.open(dataSpec)
        }
        // 数据源跑在 Media3 的加载线程上，这里允许阻塞重连一次
        val vfs = runBlocking { resolver?.invoke(vfsUri) ?: locator.find(vfsUri) }
            ?: throw IOException("会话不可用（存储已断开）")

        transferInitializing(dataSpec)
        // 打开失败时不要把 started 置位：没有 transferStarted 就不能发送 transferEnded。
        // 否则监听器会收到一个没有开始事件的结束事件，进度统计可能变成负数。
        val r = try {
            vfs.openRead(vfsUri, offset = dataSpec.position)
        } catch (e: Exception) {
            reader = null
            target = null
            remaining = -1L
            throw e
        }
        reader = r
        target = vfsUri
        remaining = computeRemaining(dataSpec.length, r.size, dataSpec.position)
        transferStarted(dataSpec)
        started = true
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegated) return delegate!!.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val r = reader ?: return -1
        val want = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        // runBlocking：InputStream 的契约是阻塞式读，调用方（Media3 加载线程）已在非主线程
        val n = runBlocking { r.read(buffer, offset, want) }
        if (n > 0) {
            if (remaining > 0) remaining -= n
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = target?.let { mediaUriFor(it) }

    override fun close() {
        if (delegated) {
            delegated = false
            runCatching { delegate?.close() }
            return
        }
        runBlocking { runCatching { reader?.close() } }
        reader = null
        target = null
        remaining = -1L
        // 只有真正开始过才结束（保证与 transferStarted 成对）
        if (started) {
            started = false
            transferEnded()
        }
    }
}

// --------------------------------------------------------------------------- 页面辅助（原 MediaScreen 主函数内联，2026-10-08 重审 §2 移入）

/**
 * 该 VFS URI 对应的**本地绝对路径**（仅当文件真的在本机磁盘上时返回）。
 *
 * 本地文件走 file:// 交给 Media3 原生读取；网络 / 压缩包内返回 null（走 panelfm://）。
 */
internal fun localPathOf(container: AppContainer, vfsUri: VfsUri): String? {
    if (vfsUri.scheme != "local") return null
    val path = runCatching { container.localVfs.absolutePath(vfsUri) }.getOrNull() ?: return null
    return if (java.io.File(path).isFile) path else null
}

/**
 * 播放前的**可读性预检**：能 stat 到、能读出第一个字节。
 *
 * 为什么需要：播放器失败时用户只看到黑屏，没有任何线索。
 * 预检能在 prepare 之前把「文件不存在 / 没权限 / 会话断开」直接变成可执行文案。
 *
 * 会话不在时由 [AppContainer.resolveSession] 自动重连一次；取消异常原样抛出（由调用方处理）。
 */
internal suspend fun preflightMedia(container: AppContainer, vfsUri: VfsUri): String? = withContext(Dispatchers.IO) {
    try {
        val vfs = container.resolveSession(vfsUri)
            ?: return@withContext "存储会话不可用（可能已断开，请重新打开该存储）"
        val meta = vfs.stat(vfsUri)
        if (meta.isDirectory) return@withContext "这是一个文件夹，不是媒体文件"
        if (meta.size == 0L) return@withContext "文件为空（0 字节）"
        vfs.openRead(vfsUri, offset = 0, length = 1).use { reader ->
            val buf = ByteArray(1)
            val n = reader.read(buf, 0, 1)
            if (n <= 0) return@withContext "无法读取文件内容（权限不足或文件已损坏）"
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (e as? VfsException)?.userMessage ?: "无法访问文件：${e.message ?: "未知错误"}"
    }
}

/**
 * 本次 open 后「还能读多少字节」的计算（纯函数，便于单测）：
 *  - 显式给了 length → 用 length；
 *  - 已知总长（>0）→ 总长 − 起始偏移（不低于 0）；
 *  - 否则 -1（读到 EOF）。
 */
internal fun computeRemaining(dataSpecLength: Long, total: Long?, position: Long): Long = when {
    dataSpecLength != -1L -> dataSpecLength
    total != null && total > 0 -> (total - position).coerceAtLeast(0)
    else -> -1L
}
