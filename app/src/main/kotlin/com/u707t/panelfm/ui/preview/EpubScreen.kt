package com.u707t.panelfm.ui.preview

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * EPUB 只读阅读器（EPUB2 / EPUB3 基础版）：
 *  - 复用压缩包挂载（`.epub` 就是 zip）：`META-INF/container.xml` → OPF → spine（见 [EpubParser]）；
 *  - 页面由 WebView 渲染，**关掉 JS**（书本内容不可信，脚本不执行）；外链全部拦下、不联网；
 *  - 文本类资源（xhtml/css/xml/svg）先按 [TextEncodings] 识别编码再转 UTF-8 —— 中文 GBK 的电子书不乱码；
 *  - 目录（NCX / EPUB3 nav）给标题；上一章 / 下一章 / 目录三个入口。
 *
 * 不做：DRM、书签进度、字体设置（先保证能读）。
 */
private const val EPUB_HOST = "epub.panelfm"
private const val EPUB_BASE_URL = "https://epub.panelfm/"
private const val EPUB_MAX_ENTRY_BYTES = 8L * 1024 * 1024

@Composable
fun EpubScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var pageError by remember(item.uri) { mutableStateOf<String?>(null) }
    var archive by remember(item.uri) { mutableStateOf<ArchiveVfs?>(null) }
    var book by remember(item.uri) { mutableStateOf<EpubBook?>(null) }
    var index by remember(item.uri) { mutableIntStateOf(0) }
    var tocOpen by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        try {
            val (vfs, parsed) = withContext(Dispatchers.IO) {
                val mounted = container.openArchive(item.uri)
                val host = item.uri
                fun readText(path: String): String? = readArchiveEntry(mounted, host, path, EPUB_MAX_ENTRY_BYTES)
                    ?.let { TextEncodings.decode(it).text }

                val containerXml = readText("META-INF/container.xml")
                    ?: throw IllegalStateException("不是有效的 EPUB（缺少 META-INF/container.xml）")
                val opfPath = EpubParser.parseContainerXml(containerXml)
                    ?: throw IllegalStateException("EPUB 结构异常：container.xml 里没有 rootfile")
                val opfXml = readText(opfPath)
                    ?: throw IllegalStateException("EPUB 结构异常：读不到 $opfPath")
                mounted to EpubParser.parseBook(opfPath, opfXml, ::readText)
            }
            archive = vfs
            book = parsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = (e as? VfsException)?.userMessage ?: e.message ?: "打开 EPUB 失败"
        }
    }

    val current = book?.chapters?.getOrNull(index)

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = book?.title ?: item.name,
            subtitle = book?.let { "第 ${index + 1}/${it.chapters.size} 章 · 只读" } ?: "EPUB · 只读",
            onBack = onBack,
            actions = {
                if (book != null) {
                    MtIconButton(
                        icon = MtIcon.CHEVRON_L,
                        contentDescription = "上一章",
                        onClick = { if (index > 0) index-- },
                    )
                    MtIconButton(
                        icon = MtIcon.MENU,
                        contentDescription = "目录",
                        onClick = { tocOpen = true },
                    )
                    MtIconButton(
                        icon = MtIcon.CHEVRON_R,
                        contentDescription = "下一章",
                        onClick = { if (index < (book!!.chapters.size - 1)) index++ },
                    )
                }
            },
        )
        when {
            error != null -> ErrorState(error!!)
            book == null || archive == null -> LoadingState("正在解析 EPUB…")
            pageError != null -> ErrorState(pageError!!)
            current != null -> key(index) {
                EpubWebView(
                    archive = archive!!,
                    host = item.uri,
                    chapter = current,
                    onPageError = { pageError = it },
                )
            }
        }
    }

    if (tocOpen && book != null) {
        val chapters = book!!.chapters
        AlertDialog(
            onDismissRequest = { tocOpen = false },
            title = { Text("目录（${chapters.size} 章）", style = MaterialTheme.typography.titleSmall) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(chapters.size) { i ->
                        val chapter = chapters[i]
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    index = i
                                    tocOpen = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { tocOpen = false }) { Text("关闭") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubWebView(
    archive: ArchiveVfs,
    host: VfsUri,
    chapter: EpubChapter,
    onPageError: (String) -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // ⚠️ 书本内容不可信：**不开** JS（script / on* 处理器不会执行）
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = EpubWebViewClient(archive, host, onPageError)
                loadUrl(EPUB_BASE_URL + android.net.Uri.encode(chapter.path, "/"))
            }
        },
        onRelease = { webView -> runCatching { webView.destroy() } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** 只服务一本书：把 `https://epub.panelfm/<压缩包内路径>` 映射到挂载好的 [ArchiveVfs]，其余 404。 */
private class EpubWebViewClient(
    private val archive: ArchiveVfs,
    private val host: VfsUri,
    private val onPageError: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase()
        // 非 http(s)（data: / about: …）交回 WebView
        if (scheme != "http" && scheme != "https") return null
        if (url.host != EPUB_HOST) return notFound()
        val path = url.path.orEmpty().trimStart('/')
        if (path.isEmpty()) return notFound()
        return runCatching {
            val bytes = readArchiveEntry(archive, host, path, EPUB_MAX_ENTRY_BYTES) ?: return notFound()
            val mime = mimeOfEntry(path)
            val textLike = mime.startsWith("text/") || mime == "application/xml" || mime == "image/svg+xml"
            val body = if (textLike) {
                // 编码识别 → 统一 UTF-8（中文 GBK 电子书不乱码）；页面里没有 JS，转码只做一次
                TextEncodings.decode(bytes).text.toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
            WebResourceResponse(mime, if (textLike) "utf-8" else null, 200, "OK", NO_STORE, ByteArrayInputStream(body))
        }.getOrElse { notFound() }
    }

    /** 书内锚点可跳；外链一律拦下（预览页不联网）。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url ?: return true
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return url.host != EPUB_HOST
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onPageError("页面加载失败：${error.description}（${error.errorCode}）")
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val NO_STORE = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Pragma" to "no-cache",
        )
    }
}

/** 读压缩包内条目（顺序读，带上限与目录判断）；不存在 / 超限 / 是目录 → null。 */
internal fun readArchiveEntry(archive: ArchiveVfs, host: VfsUri, path: String, max: Long): ByteArray? = runBlocking {
    val uri = ArchiveVfs.uriFor(host, archive.kind, path)
    val meta = runCatching { archive.stat(uri) }.getOrNull() ?: return@runBlocking null
    if (meta.isDirectory || meta.size > max) return@runBlocking null
    val reader = archive.openRead(uri)
    try {
        val out = ByteArrayOutputStream(if (meta.size > 0) minOf(meta.size, max).toInt() else 64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return@runBlocking null
        }
        out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}

/** 书本内文件 → MIME（够用即可；渲染主要靠 WebView 自己按扩展名/内容判定） */
internal fun mimeOfEntry(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "xhtml", "html", "htm" -> "text/html"
    "css" -> "text/css"
    "xml", "ncx", "opf" -> "application/xml"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}
