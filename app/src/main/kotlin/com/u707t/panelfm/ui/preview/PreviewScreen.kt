package com.u707t.panelfm.ui.preview

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 预览调度：按 MIME / 扩展名分派到 播放器 / 编辑器 / 字体 / 图片 / 文本 / Hex。
 *  - 播放器、编辑器、字体预览自带整屏界面（整页接管，不再叠加外壳）
 *  - 顶栏复刻 MT：← 返回 · 文件名（单行省略）· ⋮（文本 / 编辑 / Hex / 字体 / 外部应用）
 */
@Composable
fun PreviewScreen(container: AppContainer, request: PreviewRequest, onBack: () -> Unit) {
    val uri = request.uri
    val context = LocalContext.current
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var effective by remember { mutableStateOf(request.mode) }
    var editing by remember { mutableStateOf(request.mode == PreviewMode.EDITOR) }
    var modeMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
            meta = vfs.stat(uri)
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    val item = meta
    val resolved: PreviewMode? = if (item != null && !item.isDirectory) {
        var r = effective
        if (editing) r = PreviewMode.EDITOR
        if (r == PreviewMode.AUTO) {
            val kind = MimeTypes.kindOf(item.extension)
            r = when (kind) {
                MimeTypes.Kind.IMAGE -> PreviewMode.IMAGE
                MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO -> PreviewMode.MEDIA
                MimeTypes.Kind.FONT -> PreviewMode.FONT
                MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE -> PreviewMode.TEXT
                else -> if (item.size in 1..MAX_TEXT_SIZE) PreviewMode.TEXT else PreviewMode.HEX
            }
        }
        r
    } else null

    // 独立整屏界面（自带顶栏）：播放器 / 编辑器 / 字体预览
    if (item != null && resolved != null) {
        when (resolved) {
            PreviewMode.MEDIA -> {
                MediaScreen(container, item.uri, item.name, onBack = onBack)
                return
            }
            PreviewMode.EDITOR -> {
                com.u707t.panelfm.ui.editor.EditorScreen(container, item.uri, onBack = onBack)
                return
            }
            PreviewMode.FONT -> {
                FontScreen(container, item.uri, onBack = onBack)
                return
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：← 返回 · 文件名（单行省略，不会被按钮挤成竖排）· ⋮
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                meta?.name ?: uri.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            Box {
                TextButton(onClick = { modeMenu = true }) { Text("⋮") }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("文本", color = if (!editing && resolved == PreviewMode.TEXT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        onClick = { effective = PreviewMode.TEXT; editing = false; modeMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑", color = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        onClick = { editing = true; modeMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Hex", color = if (!editing && resolved == PreviewMode.HEX) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        onClick = { effective = PreviewMode.HEX; editing = false; modeMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("字体") },
                        onClick = { effective = PreviewMode.FONT; editing = false; modeMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("外部应用") },
                        onClick = {
                            modeMenu = false
                            val file = runCatching { File(container.localVfs.absolutePath(uri)) }.getOrNull()
                            if (uri.scheme == "local" && file != null && file.exists()) {
                                val shareUri = runCatching {
                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                }.getOrNull()
                                if (shareUri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(shareUri, meta?.mimeType ?: "*/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(intent) }
                                }
                            }
                        },
                    )
                }
            }
        }

        when {
            error != null -> ErrorState(error!!)
            item == null -> LoadingState()
            item.isDirectory -> Text("这是一个文件夹：${uri.displayPath}", Modifier.padding(16.dp))
            else -> when (resolved) {
                PreviewMode.IMAGE -> ImagePreview(container, item)
                PreviewMode.HEX -> HexPreview(container, item)
                PreviewMode.ARCHIVE -> Text("压缩包：请返回列表后点击它进入内部浏览", Modifier.padding(16.dp))
                PreviewMode.SYSTEM -> Text("已交给系统应用打开（若未弹出，请检查是否有可用应用）", Modifier.padding(16.dp))
                // 文本 / 其它未识别文本类内容 → 文本预览（修复此前误落到 Hex 的问题）
                else -> TextPreview(container, item)
            }
        }
    }
}

private const val MAX_TEXT_SIZE = 1L * 1024 * 1024
private const val MAX_IMAGE_SIZE = 32L * 1024 * 1024
private const val HEX_WINDOW = 8 * 1024

/**
 * 图片预览：先读边界再按最长边 ≤ 2048px 采样解码（大图不整包进内存）；
 * 双击切换 1x / 2.5x，捏合缩放（1–5x），双指拖动平移（MT 同款）。
 */
@Composable
private fun ImagePreview(container: AppContainer, item: FileMetadata) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(item.uri) {
        try {
            bitmap = decodeSampled(container, item.uri)
        } catch (e: Exception) {
            error = e.message ?: "解码失败"
        }
    }

    when {
        error != null -> ErrorState("图片预览失败：$error\n（可在 ⋮ 菜单点「Hex」查看原始数据）")
        bitmap == null -> LoadingState("解码中…")
        else -> {
            val bm = bitmap!!
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
                    // 双击：1x ↔ 2.5x
                    .pointerInput(bm) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = androidx.compose.ui.geometry.Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }
                    // 捏合缩放 + 双指平移
                    .pointerInput(bm) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = (scale - 1f) * size.width / 2f
                            val maxY = (scale - 1f) * size.height / 2f
                            offset = androidx.compose.ui.geometry.Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

/**
 * 图片解码：流式两遍读（边界 → 采样），不把整包读进 Java 堆 —— 超大图片 / 远程图片也不 OOM；
 * 最长边 ≤ [TARGET_MAX_EDGE] 采样。
 */
private suspend fun decodeSampled(container: AppContainer, uri: VfsUri): android.graphics.Bitmap =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val openStream: () -> java.io.InputStream = when {
            uri.scheme == "local" -> {
                val path = runCatching { container.localVfs.absolutePath(uri) }.getOrNull()
                if (path != null && File(path).exists()) {
                    { java.io.FileInputStream(path) }
                } else {
                    { openVfsStream(container, uri) }
                }
            }
            else -> ({ openVfsStream(container, uri) })
        }
        // 第一遍：读边界（BufferedInputStream 提供 mark/reset，BitmapFactory 需要）
        val (w, h) = openStream().use { s ->
            val b = java.io.BufferedInputStream(s, 64 * 1024)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(b, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                throw IllegalStateException("无法解码图片（不支持的格式或数据损坏）")
            }
            opts.outWidth to opts.outHeight
        }
        // 第二遍：按边界采样解码（流式读入，解码后位图最长边 ≤ 2048px）
        val sample = sampleToFit(w, h, TARGET_MAX_EDGE)
        openStream().use { s ->
            val b = java.io.BufferedInputStream(s, 64 * 1024)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeStream(b, null, opts) ?: throw IllegalStateException("无法解码图片")
        }
    }

/** 把统一 VFS 的顺序读适配成 InputStream（BitmapFactory.decodeStream 用） */
private fun openVfsStream(container: AppContainer, uri: VfsUri): java.io.InputStream {
    val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
    return object : java.io.InputStream() {
        private val reader = vfs.openRead(uri)
        private val buf = ByteArray(64 * 1024)

        override fun read(): Int {
            val n = kotlinx.coroutines.runBlocking { reader.read(buf, 0, 1) }
            return if (n < 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            kotlinx.coroutines.runBlocking { reader.read(b, off, len) }

        override fun skip(n: Long): Long {
            var left = n
            while (left > 0) {
                val got = kotlinx.coroutines.runBlocking { reader.read(buf, 0, minOf(buf.size.toLong(), left).toInt()) }
                if (got < 0) break
                left -= got
            }
            return n - left
        }

        override fun available(): Int = 0

        override fun close() = runCatching { reader.close() }.let { Unit }
    }
}

private fun sampleToFit(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

private const val TARGET_MAX_EDGE = 2048

@Composable
private fun TextPreview(container: AppContainer, item: FileMetadata) {
    var text by remember { mutableStateOf<String?>(null) }
    var encoding by remember { mutableStateOf("") }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        try {
            val read = readBytes(container, item.uri, MAX_TEXT_SIZE + 1)
            truncated = read.size > MAX_TEXT_SIZE
            val bytes = if (truncated) read.copyOf(MAX_TEXT_SIZE.toInt()) else read
            val decoded = decodeText(bytes)
            encoding = decoded.first
            text = decoded.second
        } catch (e: Exception) {
            error = e.message
        }
    }

    when {
        error != null -> ErrorState("文本预览失败：$error")
        text == null -> LoadingState()
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "${item.name} · ${Fmt.size(item.size)} · ${encoding}" + if (truncated) " · 已截断显示前 1 MB" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    items(text!!.split('\n')) { line ->
                        Text(
                            line.ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HexPreview(container: AppContainer, item: FileMetadata) {
    var lines by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri) {
        try {
            val bytes = readBytes(container, item.uri, HEX_WINDOW.toLong())
            lines = bytes.toHexLines()
        } catch (e: Exception) {
            error = e.message
        }
    }
    when {
        error != null -> ErrorState("Hex 预览失败：$error")
        lines == null -> LoadingState()
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "${item.name} · ${Fmt.size(item.size)} · 前 ${HEX_WINDOW / 1024} KB（只读）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines!!) { (offset, row) ->
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            "%08X".format(offset),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "  $row",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun ByteArray.toHexLines(): List<Pair<Long, String>> {
    val out = ArrayList<Pair<Long, String>>()
    var offset = 0L
    while (offset < size) {
        val end = minOf(offset + 16, size.toLong()).toInt()
        val chunk = copyOfRange(offset.toInt(), end)
        val hex = chunk.joinToString(" ") { "%02X".format(it) }.padEnd(16 * 3 - 1)
        val ascii = chunk.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
        out.add(offset to "$hex  |$ascii|")
        offset = end.toLong()
    }
    return out
}

private fun decodeText(bytes: ByteArray): Pair<String, String> {
    val decoded = com.u707t.panelfm.core.common.TextEncodings.decode(bytes)
    return decoded.charset to decoded.text
}

private suspend fun readBytes(container: AppContainer, uri: VfsUri, max: Long): ByteArray {
    val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
    val reader = vfs.openRead(uri)
    try {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val want = minOf(buffer.size.toLong(), max - total).toInt()
            val n = reader.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}
