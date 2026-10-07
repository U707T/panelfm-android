package com.u707t.panelfm.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.OfficeFormats
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Office 文档只读预览（WebView + 前端渲染库，见 `docs/OFFICE-PREVIEW.md`）。
 *
 *  - 渲染：`assets/office/`（docx-preview / SheetJS / @aiden0z/pptx-renderer，均为宽松许可）；
 *  - 页面用 `loadDataWithBaseURL` 注入，baseUrl 固定 `https://office.panelfm/office/`：
 *    页面里的相对路径、动态 `import()` 与 `fetch('/doc/current')` 全部由
 *    [OfficeWebViewClient.shouldInterceptRequest] 从 assets / 内存提供，**不联网**；
 *  - 只读：不开 DOM storage、不要 JS 桥、拦截一切跳转；文件超过 [OFFICE_MAX_BYTES] 直接给提示；
 *  - 旧二进制格式（.doc / .ppt）前端生态没有渲染器：这里不进 WebView，直接给说明 + 引导「打开方式…」。
 */
private const val OFFICE_MAX_BYTES = 16L * 1024 * 1024
private const val OFFICE_HOST = "office.panelfm"
private const val OFFICE_BASE_URL = "https://office.panelfm/office/"

@Composable
fun OfficeScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    val kind = OfficeFormats.viewerKindOf(item.extension)
    var bytes by remember(item.uri) { mutableStateOf<ByteArray?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        if (kind.isEmpty()) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) { readCapped(container, item, OFFICE_MAX_BYTES) }
        }.onSuccess { bytes = it }
            .onFailure { error = it.message ?: "读取文档失败" }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = item.name,
            subtitle = if (kind.isEmpty()) null else "${Fmt.size(item.size)} · 只读预览",
            onBack = onBack,
        )
        when {
            kind.isEmpty() -> UnsupportedDocument(item)
            error != null -> ErrorState(error!!)
            bytes == null -> LoadingState("正在读取文档…")
            else -> OfficeWebView(kind = kind, bytes = bytes!!)
        }
    }
}

/** 旧二进制格式（.doc / .ppt）的说明页（不是报错，是「没有渲染器」的明确告知）。 */
@Composable
private fun UnsupportedDocument(item: FileMetadata) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            OfficeFormats.unsupportedMessage(item.extension),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficeWebView(kind: String, bytes: ByteArray) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                // 纯离线只读预览：不要文件/内容访问，不要 DOM storage，也不要 JS 桥
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = OfficeWebViewClient(ctx, bytes, kind)
                val html = runCatching {
                    ctx.assets.open("office/index.html").bufferedReader().use { it.readText() }
                }.getOrNull() ?: "<html><body style='font-family:sans-serif;padding:24px'>预览页资源缺失</body></html>"
                loadDataWithBaseURL(OFFICE_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView -> runCatching { webView.destroy() } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** 只服务两种请求：`/doc/current`（文件字节）与 office 前缀下的 assets，其余一律 404。 */
private class OfficeWebViewClient(
    private val context: Context,
    private val bytes: ByteArray,
    private val kind: String,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return notFound()
        if (url.host != OFFICE_HOST) return notFound()
        val path = url.path ?: return notFound()
        return when {
            path == "/doc/current" -> WebResourceResponse(
                when (kind) {
                    OfficeFormats.KIND_DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    OfficeFormats.KIND_XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                },
                null,
                ByteArrayInputStream(bytes),
            )
            path.startsWith("/office/") -> serveAsset(path.removePrefix("/office/"))
            else -> notFound()
        }
    }

    /** 预览页里的链接 / 表单跳转一律拦住（只读预览，没有可去的地方）。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    private fun serveAsset(assetPath: String): WebResourceResponse {
        // 防目录穿越
        if (assetPath.contains("..") || assetPath.startsWith("/")) return notFound()
        return runCatching {
            val mime = when (assetPath.substringAfterLast('.', "").lowercase()) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json") "utf-8" else null
            WebResourceResponse(mime, encoding, context.assets.open("office/$assetPath"))
        }.getOrElse { notFound() }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/** 读取文档字节（带大小上限；超限直接给可执行的提示，而不是渲染到一半崩掉）。 */
private suspend fun readCapped(container: AppContainer, item: FileMetadata, max: Long): ByteArray {
    if (item.size > max) {
        throw IllegalStateException("文件超过 ${Fmt.size(max)}，暂不支持内置预览。\n\n请用「打开方式…」交给外部应用。")
    }
    val vfs = container.locator.find(item.uri) ?: throw IllegalStateException("会话不可用")
    val reader = vfs.openRead(item.uri)
    try {
        val out = ByteArrayOutputStream(minOf(item.size, max).toInt().coerceAtLeast(0))
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = reader.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}
