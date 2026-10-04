package com.u707t.panelfm.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 缩略图管线（M9）：
 *  - 内存 LRU + 磁盘缓存（cache/thumbs/<sha1>.png）
 *  - 本地图片直接采样解码；网络图片按设置（默认仅 Wi-Fi / 小于 3 MB）才加载
 *  - 快速滚动时由调用方传入 skip，跳过加载（MT 同款手感）
 */
object ThumbCache {

    private val memory = android.util.LruCache<String, Bitmap>(120)
    private var cacheDir: File? = null

    fun init(dir: String) {
        cacheDir = File(dir).apply { mkdirs() }
    }

    private fun keyOf(item: FileMetadata): String =
        item.uri.toString() + "#" + item.lastModified + "#" + item.size

    private fun fileOf(key: String): File? {
        val dir = cacheDir ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    suspend fun load(container: AppContainer, item: FileMetadata, targetPx: Int, allowRemote: Boolean): Bitmap? {
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
        if (item.uri.scheme != "local" && (!allowRemote || item.size > MAX_REMOTE_SIZE || item.size < 0)) return null

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
                        while (total < MAX_REMOTE_SIZE) {
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
        runCatching {
            fileOf(key)?.let { f -> withContext(Dispatchers.IO) { f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) } } }
        }
        return bitmap
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

/** 列表行里的缩略图（不可用/未加载时返回 null，由调用方回退到类型图标） */
@Composable
fun rememberThumb(
    container: AppContainer,
    item: FileMetadata,
    targetPx: Int,
    skip: Boolean,
): ImageBitmap? {
    val isImage = !item.isDirectory && MimeTypes.kindOf(item.extension) == MimeTypes.Kind.IMAGE
    if (!isImage || skip) return null
    // 连接级开关：编辑连接 → 缩略图选项 → 关闭后该连接不加载缩略图
    if (item.uri.scheme != "local") {
        val cfg = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(item.uri))
            ?: container.connectionByAuthority(item.uri.scheme, item.uri.authority)
        if (cfg?.option(com.u707t.panelfm.core.model.ConnectionConfig.OPT_LOAD_THUMBS) == "false") return null
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.collectAsState()
    val state = produceState<ImageBitmap?>(initialValue = null, item.uri.toString(), skip, settings.thumbnailsOnMobile) {
        // Wi-Fi 默认加载；移动数据下按「移动数据下加载缩略图」设置（切换设置会刷新加载行为）
        val allowRemote = com.u707t.panelfm.LocalNetwork.isOnWifi(context) || settings.thumbnailsOnMobile
        value = ThumbCache.load(container, item, targetPx, allowRemote)?.asImageBitmap()
    }
    return state.value
}
