package com.u707t.panelfm.ui.preview

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * 预览调度：按 MIME / 扩展名分派到图片 / 文本 / Hex；
 * 未知类型给出「用其他应用打开」。大文件按窗口读取（本轮先支持首段窗口，分块编辑器在 M7）。
 */
@Composable
fun PreviewScreen(container: AppContainer, request: PreviewRequest, onBack: () -> Unit) {
    val uri = request.uri
    val context = LocalContext.current
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var effective by remember { mutableStateOf(request.mode) }
    var editing by remember { mutableStateOf(request.mode == PreviewMode.EDITOR) }

    LaunchedEffect(uri) {
        try {
            val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
            meta = vfs.stat(uri)
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                meta?.name ?: uri.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { effective = PreviewMode.TEXT; editing = false }) { Text("文本") }
            TextButton(onClick = { editing = true }) { Text("编辑") }
            TextButton(onClick = { effective = PreviewMode.HEX; editing = false }) { Text("Hex") }
            TextButton(onClick = { effective = PreviewMode.FONT; editing = false }) { Text("字体") }
            TextButton(onClick = {
                val file = File(container.localVfs.absolutePath(uri))
                if (uri.scheme == "local" && file.exists()) {
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
            }) { Text("外部应用") }
        }

        when {
            error != null -> ErrorState(error!!)
            meta == null -> LoadingState()
            meta!!.isDirectory -> Text("这是一个文件夹：${uri.displayPath}", Modifier.padding(16.dp))
            else -> {
                val item = meta!!
                val kind = MimeTypes.kindOf(item.extension)
                var resolved = effective
                if (editing) resolved = PreviewMode.EDITOR
                if (resolved == PreviewMode.AUTO) {
                    resolved = when (kind) {
                        MimeTypes.Kind.IMAGE -> PreviewMode.IMAGE
                        MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO -> PreviewMode.MEDIA
                        MimeTypes.Kind.FONT -> PreviewMode.FONT
                        MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE -> PreviewMode.TEXT
                        else -> if (item.size in 1..MAX_TEXT_SIZE) PreviewMode.TEXT else PreviewMode.HEX
                    }
                }
                when (resolved) {
                    PreviewMode.MEDIA -> MediaScreen(container, item.uri, item.name, onBack = onBack)
                    PreviewMode.IMAGE -> ImagePreview(container, item)
                    PreviewMode.EDITOR -> com.u707t.panelfm.ui.editor.EditorScreen(container, item.uri, onBack = onBack)
                    PreviewMode.FONT -> FontScreen(container, item.uri, onBack = onBack)
                    PreviewMode.ARCHIVE -> Text("压缩包：请返回列表后点击它进入内部浏览", Modifier.padding(16.dp))
                    PreviewMode.SYSTEM -> Text("已交给系统应用打开（若未弹出，请检查是否有可用应用）", Modifier.padding(16.dp))
                    else -> if (kind == MimeTypes.Kind.AUDIO || kind == MimeTypes.Kind.VIDEO) {
                        MediaScreen(container, item.uri, item.name, onBack = onBack)
                    } else {
                        HexPreview(container, item)
                    }
                }
            }
        }
    }
}

private const val MAX_TEXT_SIZE = 1L * 1024 * 1024
private const val MAX_IMAGE_SIZE = 32L * 1024 * 1024
private const val HEX_WINDOW = 8 * 1024

@Composable
private fun ImagePreview(container: AppContainer, item: FileMetadata) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri) {
        try {
            val bytes = readBytes(container, item.uri, MAX_IMAGE_SIZE)
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("无法解码图片")
            bitmap = bm
        } catch (e: Exception) {
            error = e.message
        }
    }
    when {
        error != null -> ErrorState("图片预览失败：$error\n（可点右上角「Hex」查看原始数据）")
        bitmap == null -> LoadingState("解码中…")
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

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
                LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
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
