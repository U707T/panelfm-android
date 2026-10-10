package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.safeAreaPadding
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
// 网络工具箱（v2.0.12）：Ping + HTTP 请求。
//
// 借鉴 NP管理器 3.0.84「网络工具」（NPManActivity，解析见 /workspace/np-analysis/NP-UI-网络工具-解析.md）：
//  - 保留其最有价值的三件事：① Ping 与 HTTP 同屏（网络调试最小工具集）；
//    ② 请求头自由文本（每行 key: value，粘贴友好）；③ 响应直接文本预览（不弹外部浏览器）。
//  - v2.0.12 补丁（对齐 NP 的细节）：响应体 TEXT/HEX 双视图（HEX 为标准 dump 格式）、
//    独立 Size 徽标（绿色，NP_GREEN #5CBB7A）、Ping 目标历史（复用统一输入历史 `HistoryTextField`，
//    NP 用的是 Spinner，这里并入已有机制）。
//  - 暂缺（NP 有、需要时再按解析文档 §7 补）：抓包文件（request.hcy / .netnp）导入、
//    Key-Value / Binary 请求体、图片 / WebView 响应预览。
// ================================================================================================

/** 纯逻辑（可单测）：不依赖 Compose / Android。 */
object NetToolboxKit {

    /** HTTP 方法全集（对齐 NP「网络工具」的 net_method 数组）。 */
    val HttpMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /** 无协议自动补 http://（本地调试最常见是 http；与连接表单的输入习惯一致）。 */
    fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "http://$t"
    }

    /**
     * 请求头文本 → 头列表。每行 `key: value`；空行跳过；
     * 无冒号 / key 为空的行忽略（宽松解析，不打断输入过程）；值里的冒号保留（只切第一个冒号）。
     */
    fun parseHeaderLines(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) {
                    null
                } else {
                    val key = line.substring(0, i).trim()
                    if (key.isEmpty()) null else key to line.substring(i + 1).trim()
                }
            }
            .toList()

    /**
     * ping 命令行（toybox / iputils 通用子集）：`ping -c 次数 -s 包大小 -w 总超时 目标`。
     * 参数越界时夹取（次数 1–100 / 大小 8–65500 / 超时 1–120 秒），避免手滑输入产生无限输出。
     */
    fun buildPingArgs(host: String, count: Int, size: Int, timeoutSec: Int): List<String> =
        listOf(
            "ping",
            "-c", count.coerceIn(1, 100).toString(),
            "-s", size.coerceIn(8, 65500).toString(),
            "-w", timeoutSec.coerceIn(1, 120).toString(),
            host.trim(),
        )

    /**
     * 响应体解码：UTF-8（非法字节以 U+FFFD 替换）；超过 [limit] 字节截断并附说明。
     * 纯文本预览用，不追求二进制保真（二进制请看 [hexDump]）。
     */
    fun decodeBody(bytes: ByteArray, limit: Int = 256 * 1024): String {
        if (bytes.size <= limit) return String(bytes, Charsets.UTF_8)
        val head = String(bytes, 0, limit, Charsets.UTF_8)
        return head + "\n…（响应体过大，已截断显示前 ${Fmt.size(limit.toLong())}，实际 ${Fmt.size(bytes.size.toLong())}）"
    }

    /**
     * 标准 HEX dump（二进制响应用）：每行 16 字节 =
     * `偏移  4+4 组十六进制  |ASCII|`；超过 [limit] 字节截断并附说明。
     * 对齐 NP「网络工具」的响应 HEX 视图（格式取更通用的 dump 风格）。
     */
    fun hexDump(bytes: ByteArray, limit: Int = 16 * 1024): String {
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n / 16 * 80 + 64)
        var i = 0
        while (i < n) {
            val end = minOf(i + 16, n)
            sb.append("%08X  ".format(i))
            for (j in i until i + 16) {
                if (j < end) sb.append("%02X ".format(bytes[j])) else sb.append("   ")
                if (j == i + 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in i until end) {
                val b = bytes[j].toInt() and 0xFF
                sb.append(if (b in 32..126) b.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (bytes.size > limit) {
            sb.append("…（已截断显示前 ${Fmt.size(limit.toLong())}，实际 ${Fmt.size(bytes.size.toLong())}）")
        }
        return sb.toString()
    }
}

/**
 * 网络工具箱：Ping / HTTP 两个页签。
 *
 * @param container 提供 Ping 目标历史（统一输入历史：`PrefsStore.inputHistory` / `RecordKeys.NET_PING_HOST`）
 */
@Composable
fun NetToolboxScreen(container: AppContainer, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(NetToolTab.PING) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "网络工具箱", subtitle = "Ping · HTTP", onBack = onBack)
        NetToolTabSwitch(current = tab, onSelect = { tab = it })
        when (tab) {
            NetToolTab.PING -> PingPane(container)
            NetToolTab.HTTP -> HttpPane()
        }
    }
}

private enum class NetToolTab(val label: String) {
    PING("Ping"),
    HTTP("HTTP 请求"),
}

/** 两个页签的分段开关（MT 风格：选中态主色淡底 + 主色字，不做动画）。 */
@Composable
private fun NetToolTabSwitch(current: NetToolTab, onSelect: (NetToolTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NetToolTab.entries.forEach { t ->
            val selected = t == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MtSpec.CornerSmall))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .clickable { onSelect(t) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Ping
// ------------------------------------------------------------------------------------------------

@Composable
private fun PingPane(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsState()
    val pingHistory = settings.inputHistory[PrefsStore.RecordKeys.NET_PING_HOST].orEmpty()
    var host by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("4") }
    var size by remember { mutableStateOf("64") }
    var timeout by remember { mutableStateOf("4") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    // 在途进程（不参与重组：只被「停止 / 页面销毁」使用）
    val procHolder = remember { arrayOfNulls<Process>(1) }
    val scroll = rememberScrollState()

    // 输出跟进：新行进来后滚到底
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
    // 离开页面杀掉在途 ping（旧实现：进程挂后台继续输出）
    DisposableEffect(Unit) { onDispose { procHolder[0]?.destroy() } }

    fun start() {
        val target = host.trim()
        if (target.isEmpty() || running) return
        output = ""
        running = true
        scope.launch {
            // 记入历史（NP 的 Ping 历史是 Spinner，这里并入统一输入历史；失败静默）
            container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_PING_HOST, target)
            val args = NetToolboxKit.buildPingArgs(
                target,
                count.toIntOrNull() ?: 4,
                size.toIntOrNull() ?: 64,
                timeout.toIntOrNull() ?: 4,
            )
            val p = withContext(Dispatchers.IO) {
                runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            }
            if (p == null) {
                output = "无法启动系统 ping（/system/bin/ping 不可用）"
                running = false
                return@launch
            }
            procHolder[0] = p
            withContext(Dispatchers.IO) {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            withContext(Dispatchers.Main) { output += line + "\n" }
                        }
                    }
                    p.waitFor()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 页面离开：让协程正常收尾，不吞取消信号
                } catch (_: Exception) {
                    // 用户点了「停止」：进程被 destroy、流关闭，属预期路径
                }
            }
            procHolder[0] = null
            running = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        // v2.0.12：Ping 目标带历史（复用统一输入历史 `HistoryTextField`；NP 用 Spinner，这里并入既有机制）
        HistoryTextField(
            value = host,
            onValueChange = { host = it },
            label = "目标（域名 / IP）",
            history = pingHistory,
            placeholder = "www.baidu.com 或 192.168.1.1",
            modifier = Modifier.fillMaxWidth(),
            onClearHistory = {
                scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_PING_HOST) }
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PingNumField("次数", count, Modifier.weight(0.8f)) { count = it }
            PingNumField("包大小", size, Modifier.weight(1f)) { size = it }
            PingNumField("总超时(s)", timeout, Modifier.weight(1f)) { timeout = it }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { start() }, enabled = !running && host.isNotBlank()) {
                Text(if (running) "Ping 中…" else "开始")
            }
            TextButton(onClick = { procHolder[0]?.destroy() }, enabled = running) { Text("停止") }
            TextButton(onClick = { output = "" }, enabled = output.isNotEmpty()) { Text("清空") }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(MtSpec.CornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(
                    output.ifEmpty { "Ping 输出会显示在这里（调用系统 ping，无 root 可用）" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (output.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                )
            }
        }
    }
}

@Composable
private fun PingNumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        // 审计修复：只收 ASCII 数字（`Char.isDigit()` 会放过全角"４"等，toIntOrNull 会静默回退默认值）
        onValueChange = { t -> onChange(t.filter { it in '0'..'9' }.take(6)) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ------------------------------------------------------------------------------------------------
// HTTP 请求
// ------------------------------------------------------------------------------------------------

/** 响应体读取上限（审计修复：避免巨型响应把内存打爆；超出部分丢弃并在状态行标注）。 */
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

/** 响应体视图（对齐 NP「网络工具」的 TEXT / HEX / RAW 三选，这里先做 TEXT / HEX）。 */
private enum class BodyView { TEXT, HEX }

/** 响应体视图小切换钮（TEXT / HEX）。 */
@Composable
private fun BodyViewChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 一次请求的结果（IO 线程算好、Main 线程一次性写状态）。 */
private data class HttpOutcome(val status: String, val headers: String, val body: String, val bytes: ByteArray? = null)

@Composable
private fun HttpPane() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("GET") }
    var methodMenu by remember { mutableStateOf(false) }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var respHeaders by remember { mutableStateOf("") }
    var respBody by remember { mutableStateOf("") }
    var respBytes by remember { mutableStateOf<ByteArray?>(null) }
    var bodyView by remember { mutableStateOf(BodyView.TEXT) }
    // 响应体显示（TEXT / HEX 双视图；HEX 只在字节或视图变化时计算一次，避免编辑 URL 时反复重算）
    val shownBody = remember(respBytes, bodyView, respBody) {
        val b = respBytes
        if (b != null && bodyView == BodyView.HEX) NetToolboxKit.hexDump(b) else respBody
    }
    // 在途请求（不参与重组：只被「取消 / 页面销毁」使用）
    val inFlight = remember { arrayOfNulls<okhttp3.Call>(1) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    DisposableEffect(Unit) { onDispose { inFlight[0]?.cancel() } }

    fun send() {
        val target = NetToolboxKit.normalizeUrl(url)
        if (target.isEmpty() || sending) return
        sending = true
        status = "请求中…"
        respHeaders = ""
        respBody = ""
        respBytes = null
        bodyView = BodyView.TEXT
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val parsed = NetToolboxKit.parseHeaderLines(headers)
                        // Content-Type 由 RequestBody 携带（避免发重复头），其余逐条附加
                        val contentType = parsed.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                        val baseType = contentType ?: "text/plain; charset=utf-8"
                        val media = baseType.toMediaTypeOrNull()
                        // OkHttp 规则：GET/HEAD 不能带体；POST/PUT/PATCH 必须带体（空体也补一个）
                        val rb: RequestBody? = when {
                            method == "GET" || method == "HEAD" -> null
                            body.isNotEmpty() -> body.toByteArray(Charsets.UTF_8).toRequestBody(media)
                            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(media)
                            else -> null
                        }
                        val request = Request.Builder()
                            .url(target)
                            .apply {
                                parsed.filterNot { it.first.equals("Content-Type", ignoreCase = true) }
                                    .forEach { (k, v) -> addHeader(k, v) }
                            }
                            .method(method, rb)
                            .build()
                        val call = client.newCall(request)
                        inFlight[0] = call
                        val t0 = System.currentTimeMillis()
                        try {
                            call.execute().use { resp ->
                                // 读取上限 8MB（peekBody 不消费原流；超出部分丢弃并在状态行标注）
                                val peeked = resp.peekBody(MAX_BODY_BYTES).bytes()
                                val declared = resp.body.contentLength()
                                val truncated = declared > MAX_BODY_BYTES ||
                                    (declared < 0 && peeked.size.toLong() >= MAX_BODY_BYTES)
                                val ms = System.currentTimeMillis() - t0
                                HttpOutcome(
                                    status = "HTTP ${resp.code} ${resp.message} · ${ms} ms" +
                                        if (truncated) " · 已截断(8MB)" else "",
                                    headers = buildString {
                                        resp.headers.forEach { (n, v) -> append(n).append(": ").append(v).append('\n') }
                                    },
                                    body = NetToolboxKit.decodeBody(peeked),
                                    bytes = peeked,
                                )
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
            } finally {
                sending = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                TextButton(onClick = { methodMenu = true }) { Text("$method ▾") }
                DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                    NetToolboxKit.HttpMethods.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { method = m; methodMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("https://…（无协议时按 http 处理）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it },
            label = { Text("请求头（每行 key: value，可整段粘贴）") },
            placeholder = { Text("User-Agent: PanelFM/…") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("请求体（GET / HEAD 不发送）") },
            enabled = method != "GET" && method != "HEAD",
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { send() }, enabled = !sending && url.isNotBlank()) {
                Text(if (sending) "请求中…" else "发送")
            }
            TextButton(onClick = { inFlight[0]?.cancel() }, enabled = sending) { Text("取消") }
            if (shownBody.isNotEmpty()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(shownBody)) }) { Text("复制正文") }
            }
            Text(
                status.ifEmpty { "响应显示在下方" },
                style = MaterialTheme.typography.labelSmall,
                color = if (status.startsWith("HTTP") || status.startsWith("请求中"))
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }
        // 响应体工具行：TEXT/HEX 视图切换 + 绿色 Size 徽标（对齐 NP「网络工具」）
        respBytes?.let { b ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BodyViewChip("TEXT", bodyView == BodyView.TEXT) { bodyView = BodyView.TEXT }
                BodyViewChip("HEX", bodyView == BodyView.HEX) { bodyView = BodyView.HEX }
                Spacer(Modifier.weight(1f))
                Text(
                    "Size:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Fmt.size(b.size.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5CBB7A), // NP_GREEN：Size 数字徽标（两主题都清晰）
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        // 响应头（有才占位；限高可滚）
        if (respHeaders.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(MtSpec.CornerSmall))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
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
        // 响应体
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(MtSpec.CornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(
                    shownBody.ifEmpty {
                        when {
                            sending -> "请求中…"
                            respBytes != null -> "（空响应体）"
                            else -> "响应体会显示在这里"
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
        Box(Modifier.padding(bottom = 6.dp))
    }
}
