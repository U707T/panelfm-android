package com.u707t.panelfm.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.OfficeFormats
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
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
 *  - 预览页从 `https://office.panelfm/office/index.html?kind=xxx` 加载（同源 + 全拦截）：
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
    val context = LocalContext.current
    var bytes by remember(item.uri) { mutableStateOf<ByteArray?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var pageError by remember(item.uri) { mutableStateOf<String?>(null) }
    // 诊断（v2.0.3 加）：页面控制台日志 + 「强制纯文本预览」开关。
    // 之前「docx 空白」这类问题只能靠 logcat，用户拿不到；现在右上角 ⓘ 直接看/复制全部线索。
    var showDiag by remember(item.uri) { mutableStateOf(false) }
    var forceText by remember(item.uri) { mutableStateOf(false) }
    val logs = remember(item.uri) { mutableStateListOf<String>() }

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
            actions = {
                if (kind.isNotEmpty()) {
                    MtIconButton(
                        icon = MtIcon.INFO,
                        contentDescription = "预览诊断",
                        onClick = { showDiag = true },
                    )
                }
            },
        )
        when {
            kind.isEmpty() -> UnsupportedDocument(item)
            error != null -> ErrorState(error!!)
            pageError != null -> ErrorState(pageError!!)
            bytes == null -> LoadingState("正在读取文档…")
            // key(forceText)：切换「纯文本 / 排版」预览时重建 WebView（重新加载带 mode 参数的页面）
            else -> key(forceText) {
                OfficeWebView(
                    kind = kind,
                    bytes = bytes!!,
                    forceText = forceText,
                    logs = logs,
                    onPageError = { pageError = it },
                )
            }
        }
    }

    if (showDiag && kind.isNotEmpty()) {
        PreviewDiagnosticsDialog(
            report = buildDiagnosticsReport(
                context = context,
                item = item,
                kind = kind,
                forceText = forceText,
                pageError = pageError,
                logs = logs.toList(),
            ),
            forceText = forceText,
            onToggleTextMode = {
                forceText = !forceText
                showDiag = false
            },
            onDismiss = { showDiag = false },
        )
    }
}

/** 诊断报告（纯文本，可复制）：版本 / 引擎 / 渲染模式 / 页面控制台最近若干条 */
private fun buildDiagnosticsReport(
    context: Context,
    item: FileMetadata,
    kind: String,
    forceText: Boolean,
    pageError: String?,
    logs: List<String>,
): String = buildString {
    appendLine("PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME}")
    appendLine("文件：${item.name}（${Fmt.size(item.size)}，kind=$kind）")
    appendLine("渲染模式：${if (forceText) "纯文本（手动）" else "自动（标准→兼容→纯文本）"}")
    appendLine(
        "WebView：" + runCatching {
            val p = WebView.getCurrentWebViewPackage()
            "${p?.packageName} ${p?.versionName}"
        }.getOrDefault("未知")
    )
    appendLine(
        "系统 UA：" + runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("未知")
    )
    pageError?.let { appendLine("页面错误：$it") }
    append("---- 页面控制台（最近 ${logs.size} 条）----")
    logs.forEach { append('\n').append(it) }
}

/** 预览诊断弹窗：把现场信息摊开 + 一键复制 + 一键切「纯文本预览」（老引擎渲染不可用时先保证能读）。 */
@Composable
private fun PreviewDiagnosticsDialog(
    report: String,
    forceText: Boolean,
    onToggleTextMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预览诊断") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text("复制诊断信息")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onToggleTextMode) {
                    Text(if (forceText) "切回排版预览" else "纯文本预览")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
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
private fun OfficeWebView(
    kind: String,
    bytes: ByteArray,
    forceText: Boolean,
    logs: MutableList<String>,
    onPageError: (String) -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                // 纯离线只读预览：不要文件/内容访问、不要 JS 桥；DOM storage 开着
                // （个别渲染库会摸 sessionStorage，且页面只加载我们自己的资产、不联网）
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 复审 F20：显式关掉两条 file:// URL 的同源放宽（纵深防御；页面全部走
                // 拦截器提供的同一 https 源，不依赖 file:// 加载）
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = OfficeWebViewClient(ctx, bytes, kind, onPageError)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        Logx.d("OfficePreview", "${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                        // 同时留一份给「预览诊断」（用户可复制回传；见 PreviewDiagnosticsDialog）
                        logs.add("${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}".take(500))
                        if (logs.size > 200) logs.removeAt(0)
                        return true
                    }
                }
                // 主页面走同源 https + 拦截（不要用 loadDataWithBaseURL：主文档是 data: URL，
                // 一旦被拦截返回非 2xx 就整页 ERR_HTTP_RESPONSE_CODE_FAILURE）。
                // `&v=<版本号>` 破缓存（v2.0.3）：改版后页面/脚本可能被 WebView 缓存成旧版，
                // 导致「新版逻辑根本没执行」（拦截器侧同时补了 Cache-Control: no-store）。
                val url = OFFICE_BASE_URL + "index.html?kind=" + kind +
                    "&v=" + com.u707t.panelfm.BuildConfig.VERSION_NAME +
                    (if (forceText) "&mode=text" else "")
                loadUrl(url)
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
    private val onPageError: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase()
        // ⚠️ 非 http(s)（data: / blob: / about: …）必须返回 null 交回 WebView：
        // 早期实现一律回 404，主文档直接 ERR_HTTP_RESPONSE_CODE_FAILURE（整页打不开）。
        if (scheme != "http" && scheme != "https") return null
        if (url.host != OFFICE_HOST) return notFound()
        val path = url.path.orEmpty()
        return when {
            path == "/doc/current" -> WebResourceResponse(
                when (kind) {
                    OfficeFormats.KIND_DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    OfficeFormats.KIND_XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                },
                null,
                200,
                "OK",
                NO_STORE,
                ByteArrayInputStream(bytes),
            )
            path == "/office" || path == "/office/" || path == "/office/index.html" -> serveAsset("index.html")
            path.startsWith("/office/") -> serveAsset(path.removePrefix("/office/"))
            else -> notFound()
        }
    }

    /** 预览页只读：同源与非 http 协议放行，外链一律拦下。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url ?: return true
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return url.host != OFFICE_HOST
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onPageError("预览页加载失败：${error.description}（${error.errorCode}）")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            onPageError("预览页加载失败：HTTP ${response.statusCode}")
        }
    }

    private fun serveAsset(assetPath: String): WebResourceResponse {
        // 归一化：去掉空段与 "."，遇到 ".." 直接拒绝（防目录穿越；AssetManager 不认 "./" 这类路径）
        val segments = assetPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return notFound()
        val normalized = segments.joinToString("/")
        if (normalized.isEmpty()) return notFound()
        return runCatching {
            val mime = when (assetPath.substringAfterLast('.', "").lowercase()) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json") "utf-8" else null
            WebResourceResponse(mime, encoding, 200, "OK", NO_STORE, context.assets.open("office/$normalized"))
        }.getOrElse { notFound() }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))

    private companion object {
        /**
         * 拦截响应必须显式禁缓存（v2.0.3）：页面 / 脚本 URL 恒定，WebView 的 HTTP 缓存
         * 可能把旧版 `viewer.js` 一直喂给页面 —— 表现是「升级后行为完全没变、新版逻辑没执行」。
         * 页面 URL 侧还有一个 `&v=<版本号>` 双保险（见 OfficeWebView 的 loadUrl）。
         */
        val NO_STORE = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Pragma" to "no-cache",
        )
    }
}

/** 读取文档字节（带大小上限；超限直接给可执行的提示，而不是渲染到一半崩掉）。 */
private suspend fun readCapped(container: AppContainer, item: FileMetadata, max: Long): ByteArray {
    if (item.size > max) {
        throw IllegalStateException("文件超过 ${Fmt.size(max)}，暂不支持内置预览。\n\n请用「打开方式…」交给外部应用。")
    }
    // resolveSession：会话被回收 / URI 丢过连接号时自动重连（与预览页一致，
    // 旧写法只用 locator.find，网络盘放一会儿再进来会直接报「会话不可用」）
    val vfs = container.resolveSession(item.uri) ?: throw IllegalStateException("会话不可用")
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
