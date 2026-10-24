package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ================================================================================================
// HTTP 请求
//
// 相对旧版的实质改动：
//  1. 响应体不再一律 UTF-8 硬解 —— 走 [NetToolboxKit.decodeBodySmart]（GBK / Big5 / UTF-16 自动识别，
//     二进制自动切 HEX），中文页面不再整屏乱码。
//  2. 受限请求头（Host / Content-Length / Connection…）不再让整次请求失败，而是**忽略并在状态行点名**；
//     旧实现 OkHttp 直接抛 IllegalArgumentException，用户只看到「请求失败：…」。
//  3. 状态行显示**实际发出的 URL**（normalizeUrl 之后），用户能看出「没写协议被补了 http」。
//  4. 响应体可「美化 JSON」/「存到当前窗格」，调试完的东西能直接落地。
//  5. HEAD 请求不读体（旧实现也会 peekBody，多等一次读超时）。
// ================================================================================================

/** 响应体读取上限（避免巨型响应把内存打爆；超出部分丢弃并在状态行标注）。 */
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

private enum class BodyView { TEXT, HEX }

private data class HttpOutcome(
    val status: String,
    val headers: String,
    val body: String,
    val bytes: ByteArray? = null,
    /** 检测到的编码名（仅用于显示） */
    val charset: String = "",
    /** 是否是二进制内容 —— 决定保存时的扩展名（不能拿 [charset] 的显示文案做判断） */
    val binary: Boolean = false,
    val elapsedMs: Long = 0,
    val code: Int = 0,
    val ignoredHeaders: List<String> = emptyList(),
)

@Composable
internal fun HttpPane(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by container.settings.collectAsState()
    val urlHistory = settings.inputHistory[PrefsStore.RecordKeys.NET_HTTP_URL].orEmpty()

    var method by remember { mutableStateOf("GET") }
    var methodMenu by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(NetToolboxKit.HttpPresets.first()) }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var showHeaders by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var respHeaders by remember { mutableStateOf("") }
    var respBody by remember { mutableStateOf("") }
    var respBytes by remember { mutableStateOf<ByteArray?>(null) }
    var charset by remember { mutableStateOf("") }
    var binaryBody by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var bodyView by remember { mutableStateOf(BodyView.TEXT) }
    var pretty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showRespHeaders by remember { mutableStateOf(true) }

    // HEX 视图只在字节或视图变化时算一次（编辑 URL 时不该重算）。
    // 美化同理：`prettyJson` 对 8MB 响应是几十毫秒的活，绝不能放在每次重组里跑。
    val prettyBody = remember(respBody) {
        // 超过 512KB 不提供「美化」：缩进要遍历全串，放在组合期会让滚动明显掉帧
        if (respBody.isEmpty() || respBody.length > 512 * 1024) null
        else NetToolboxKit.prettyJson(respBody)
    }
    val shownBody = remember(respBytes, bodyView, respBody, pretty, prettyBody) {
        val b = respBytes
        when {
            b == null -> respBody
            bodyView == BodyView.HEX -> NetToolboxKit.hexDump(b)
            pretty -> prettyBody ?: respBody
            else -> respBody
        }
    }
    val inFlight = remember { arrayOfNulls<okhttp3.Call>(1) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // 慢速滴流服务器可以每 19s 喂一个字节，readTimeout 永远不触发：给整次请求封顶
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    DisposableEffect(Unit) {
        onDispose {
            inFlight[0]?.cancel()
            // 客户端跟着页面走：连接池 / 线程不释放的话，反复进出工具箱会叠加多套
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    fun send() {
        val target = NetToolboxKit.normalizeUrl(url)
        if (target.isEmpty() || sending) return
        sending = true
        status = "请求中…"
        respHeaders = ""
        respBody = ""
        respBytes = null
        charset = ""
        binaryBody = false
        elapsedMs = 0
        bodyView = BodyView.TEXT
        pretty = false
        scope.launch {
            try {
                runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_HTTP_URL, target) }
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val parsed = NetToolboxKit.parseHeaderLines(headers)
                        val (accepted, rejected) = NetToolboxKit.splitHeaders(parsed)
                        val contentType = NetToolboxKit.contentTypeOf(parsed) ?: "text/plain; charset=utf-8"
                        val media = contentType.toMediaTypeOrNull()
                        val allowsBody = NetToolboxKit.methodAllowsBody(method)
                        val rb: RequestBody? = when {
                            !allowsBody -> null
                            body.isNotEmpty() -> body.toByteArray(Charsets.UTF_8).toRequestBody(media)
                            else -> ByteArray(0).toRequestBody(media)
                        }
                        val request = Request.Builder()
                            .url(target)
                            .apply { accepted.forEach { (k, v) -> addHeader(k, v) } }
                            .method(method, rb)
                            .build()
                        val call = client.newCall(request)
                        inFlight[0] = call
                        val t0 = System.currentTimeMillis()
                        try {
                            call.execute().use { resp ->
                                val isHead = method == "HEAD"
                                val peeked = if (isHead) ByteArray(0) else resp.peekBody(MAX_BODY_BYTES).bytes()
                                val declared = resp.body.contentLength()
                                val truncated = declared > MAX_BODY_BYTES ||
                                    (declared < 0 && peeked.size.toLong() >= MAX_BODY_BYTES)
                                val ms = System.currentTimeMillis() - t0
                                val smart = if (isHead) NetToolboxKit.BodyText("", "—", false)
                                else NetToolboxKit.decodeBodySmart(peeked)
                                HttpOutcome(
                                    status = "HTTP ${resp.code} ${resp.message}".trim(),
                                    headers = buildString {
                                        resp.headers.forEach { (n, v) -> append(n).append(": ").append(v).append('\n') }
                                    },
                                    body = smart.text,
                                    bytes = peeked,
                                    charset = smart.charset,
                                    binary = smart.binary,
                                    elapsedMs = ms,
                                    code = resp.code,
                                    ignoredHeaders = rejected,
                                ).let { o ->
                                    if (truncated) o.copy(body = o.body + "\n…（已截断显示前 8MB）") else o
                                }
                            }
                        } catch (e: Exception) {
                            HttpOutcome(
                                status = if (call.isCanceled()) "已取消" else "请求失败：${e.message ?: e.javaClass.simpleName}",
                                headers = "",
                                body = "",
                            )
                        } finally {
                            inFlight[0] = null
                        }
                    } catch (e: Exception) {
                        HttpOutcome(status = "请求失败：${e.message ?: e.javaClass.simpleName}", headers = "", body = "")
                    }
                }
                status = outcome.status
                respHeaders = outcome.headers
                respBody = outcome.body
                respBytes = outcome.bytes
                charset = outcome.charset
                binaryBody = outcome.binary
                elapsedMs = outcome.elapsedMs
                if (outcome.ignoredHeaders.isNotEmpty() && !outcome.status.startsWith("请求失败")) {
                    status += " · 已忽略受限头 ${outcome.ignoredHeaders.joinToString("/")}"
                }
            } finally {
                sending = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                TextButton(onClick = { methodMenu = true }) { Text("$method ▾") }
                DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                    NetToolboxKit.HttpMethods.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { method = m; methodMenu = false },
                        )
                    }
                }
            }
            HistoryTextField(
                value = url,
                onValueChange = { url = it },
                label = "URL",
                history = urlHistory,
                placeholder = "https://…（无协议按 http 处理）",
                modifier = Modifier.weight(1f),
                onClearHistory = {
                    scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_HTTP_URL) }
                },
            )
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NetToolboxKit.HttpPresets.forEach { p ->
                NetChip(p, selected = url == p) { url = p }
            }
        }
        // 请求头默认收起：多数请求用不到，展开后仍是「每行 key: value」整段粘贴。
        // 收起时把「有几行」显示出来 —— 否则用户会以为自己的头被丢了。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetChip(if (showHeaders) "请求头 ︿" else "请求头 ﹀") { showHeaders = !showHeaders }
            if (headers.isNotBlank() && !showHeaders) {
                Text(
                    "${NetToolboxKit.parseHeaderLines(headers).size} 行已设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (showHeaders) {
            OutlinedTextField(
                value = headers,
                onValueChange = { headers = it },
                label = { Text("每行 key: value，可整段粘贴", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("User-Agent: PanelFM/…\nAccept: application/json") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            if (headers.isNotBlank()) {
                val (_, rejected) = NetToolboxKit.splitHeaders(NetToolboxKit.parseHeaderLines(headers))
                if (rejected.isNotEmpty()) {
                    Text(
                        "这些头会被忽略（由网络栈接管）：${rejected.joinToString(" / ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NetAmber,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("请求体（GET / HEAD 不发送）", style = MaterialTheme.typography.labelSmall) },
            enabled = NetToolboxKit.methodAllowsBody(method),
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        // 复制 / 保存都放在结果工具行里（见下方），动作行只留「发请求」相关，
        // 否则六个按钮在窄屏上即使横向可滚也很容易误触。
        NetActionRow {
            TextButton(onClick = { send() }, enabled = !sending && url.isNotBlank()) {
                Text(if (sending) "请求中…" else "发送")
            }
            TextButton(onClick = { inFlight[0]?.cancel() }, enabled = sending) { Text("取消") }
            if (respBytes != null) {
                NetVLine()
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(shownBody))
                        status = "已复制响应体"
                    },
                    enabled = shownBody.isNotEmpty(),
                ) { Text("复制") }
                TextButton(
                    onClick = {
                        saving = true
                        scope.launch {
                            val name = httpFileName(url, binaryBody)
                            status = saveToPane(container, name, (respBytes ?: ByteArray(0)))
                            saving = false
                        }
                    },
                    enabled = !saving && respBytes!!.isNotEmpty(),
                ) { Text("存到窗格") }
            }
        }
        if (status.isNotEmpty()) {
            NetStatusLine(
                text = status,
                trailing = {
                    if (elapsedMs > 0) {
                        Text(
                            "${elapsedMs}ms",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        // 响应工具行：TEXT / HEX / 美化 + Size 徽标
        respBytes?.let { b ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetChip("TEXT", selected = bodyView == BodyView.TEXT) { bodyView = BodyView.TEXT }
                NetChip("HEX", selected = bodyView == BodyView.HEX) { bodyView = BodyView.HEX }
                if (bodyView == BodyView.TEXT && prettyBody != null) {
                    NetChip("美化", selected = pretty) { pretty = !pretty }
                }
                if (charset.isNotEmpty()) {
                    Text(
                        charset,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text("Size:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Fmt.size(b.size.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = NetGreen,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        // 响应头（可折叠，限高可滚）
        if (respHeaders.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NetChip(if (showRespHeaders) "响应头 ︿" else "响应头 ﹀") { showRespHeaders = !showRespHeaders }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(respHeaders))
                    status = "已复制响应头"
                }) { Text("复制头", style = MaterialTheme.typography.labelSmall) }
            }
            if (showRespHeaders) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .clip(RoundedCornerShape(MtSpec.CornerSmall))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                        .padding(8.dp),
                ) {
                    SelectionContainer {
                        Text(
                            respHeaders,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
        // 响应体：**固定高度**（不是 heightIn(min)）。
        // 整页在 verticalScroll 里，父级给的高度是无界的；用 min 约束会让内容把盒子撑到全文高度，
        // 内层 verticalScroll 变成空转，8MB 响应会把整页布局一次算完（掉帧）。固定高度让内层真正滚动。
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(MtSpec.CornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(
                    shownBody.ifEmpty {
                        when {
                            sending -> "请求中…"
                            respBytes != null -> "（空响应体）"
                            else -> "响应体会显示在这里\n\n自动识别 UTF-8 / GBK / Big5 / UTF-16；二进制自动切 HEX"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (shownBody.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

/**
 * 保存响应体时的文件名。
 *
 * 旧写法按「URL 里有点就不加扩展名」判断，`https://api.github.com` 会存成 `api_github.com` ——
 * 一个没有扩展名的文件，在文件管理器里点开也没法预览。现在只认**已知的尾缀**，
 * 其余一律补 `.txt`（二进制补 `.bin`），保证存下来的东西双击能打开。
 */
private fun httpFileName(url: String, binary: Boolean): String {
    val path = NetToolboxKit.normalizeUrl(url)
        .substringAfter("://", url)
        .substringBefore('?')
        .substringBefore('#')
    val rawName = path.substringAfterLast('/', "")
        .ifEmpty { path.substringBefore('/') }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifEmpty { "http-response" }
    val known = listOf(
        ".json", ".xml", ".html", ".htm", ".txt", ".css", ".js",
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".zip", ".gz", ".bin",
    )
    val hasKnownExt = known.any { rawName.endsWith(it, ignoreCase = true) }
    val ext = when {
        hasKnownExt -> ""
        binary -> ".bin"
        else -> ".txt"
    }
    return (rawName + ext).take(80)
}
