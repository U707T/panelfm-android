package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.DefaultGateway
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

// ================================================================================================
// 端口探测（新增）
//
// 和「局域网扫描」的分工：
//  - 局域网扫描是**网段 × 单端口**（找哪台机器开着 SSH）；
//  - 这里是**单主机 × 多端口**（我连不上这台机器，是哪个端口没开 / 被墙）。
// 两者互补，都是排查「连不上」的必经步骤，所以放在同一个工具箱里。
//
// 实现要点：
//  - 并发 TCP connect（默认 32 并发 / 800ms 超时），逐条回调，边探边出结果；
//  - 开放端口尝试读 banner（SSH / FTP / SMTP / Redis 都会先说话），读不到不阻塞；
//  - Android 17 起未授权 ACCESS_LOCAL_NETWORK 时局域网目标一律失败，先给授权引导。
// ================================================================================================

@Composable
internal fun PortPane(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by container.settings.collectAsState()
    val history = settings.inputHistory[PrefsStore.RecordKeys.NET_PORT_HOST].orEmpty()

    var host by remember { mutableStateOf(DefaultGateway.ipv4(context) ?: "127.0.0.1") }
    var portText by remember { mutableStateOf("22,80,443,445,3306,5432,6379,8080") }
    var timeoutMs by remember { mutableStateOf("800") }
    var concurrency by remember { mutableStateOf("32") }
    var showAdvanced by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<NetToolboxKit.PortResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var onlyOpen by remember { mutableStateOf(true) }

    val ports = remember(portText) { NetToolboxKit.parsePorts(portText) }
    val localNetOk = LocalNetwork.isGranted(context)

    fun start() {
        if (running) return
        val target = host.trim()
        if (target.isEmpty()) {
            status = "失败：请先填主机"
            return
        }
        if (ports.isEmpty()) {
            status = "失败：端口列表为空（示例：22,80,443 或 1-1024）"
            return
        }
        running = true
        done = 0
        total = ports.size
        results = emptyList()
        status = "探测中…"
        val timeout = timeoutMs.toIntOrNull()?.coerceIn(100, 10_000) ?: 800
        val conc = concurrency.toIntOrNull()?.coerceIn(1, 128) ?: 32
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_PORT_HOST, target) }
                // 累积用普通集合（不在每个结果上重建列表）：2048 个端口时旧写法是 O(n² log n)，
                // 主线程会在扫描末尾连续掉帧。UI 每 8 条结果刷新一次即可。
                val acc = java.util.Collections.synchronizedList(ArrayList<NetToolboxKit.PortResult>())
                var sinceFlush = 0
                withContext(Dispatchers.IO) {
                    val sem = Semaphore(conc)
                    coroutineScope {
                        ports.forEach { port ->
                            launch {
                                sem.withPermit {
                                    val r = probePort(target, port, timeout)
                                    acc.add(r)
                                    val flush = synchronized(acc) {
                                        sinceFlush += 1
                                        if (sinceFlush >= 8 || acc.size == ports.size) {
                                            sinceFlush = 0
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    if (flush) {
                                        val snapshot = synchronized(acc) { acc.toList() }
                                        withContext(Dispatchers.Main) {
                                            results = snapshot.sortedWith(
                                                compareByDescending<NetToolboxKit.PortResult> { it.open }
                                                    .thenBy { it.port }
                                            )
                                            done = snapshot.size
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val snapshot = synchronized(acc) { acc.toList() }
                results = snapshot.sortedWith(
                    compareByDescending<NetToolboxKit.PortResult> { it.open }.thenBy { it.port }
                )
                done = snapshot.size
                val ms = System.currentTimeMillis() - t0
                val open = snapshot.count { it.open }
                status = if (open == 0) {
                    "完成：没有开放端口（目标不可达 / 全部被过滤）· 用时 ${ms}ms"
                } else {
                    "完成：" + NetToolboxKit.portSummary(snapshot, ms)
                }
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        status = "已停止（已完成 $done / $total）"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!localNetOk && LocalNetwork.required) {
            Text(
                "未授权「局域网访问」（Android 17）—— 局域网目标的探测结果不可信",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        HistoryTextField(
            value = host,
            onValueChange = { host = it },
            label = "主机（IP / 域名）",
            history = history,
            placeholder = "192.168.1.1",
            modifier = Modifier.fillMaxWidth(),
            onClearHistory = {
                scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_PORT_HOST) }
            },
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("端口（逗号分隔，支持 1-1024 区间）", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NetChip("常用端口", enabled = !running) {
                portText = NetToolboxKit.CommonPorts.joinToString(",") { it.first.toString() }
            }
            NetChip("Web", enabled = !running) { portText = "80,443,8080,8443,8888" }
            NetChip("数据库", enabled = !running) { portText = "3306,5432,6379,27017,1433,1521" }
            NetChip("远程", enabled = !running) { portText = "22,23,3389,5900" }
            NetChip(if (showAdvanced) "参数 ︿" else "参数 ﹀") { showAdvanced = !showAdvanced }
        }
        if (showAdvanced) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetNumField("超时(ms)", timeoutMs, Modifier.weight(1f)) { timeoutMs = it }
                NetNumField("并发", concurrency, Modifier.weight(1f)) { concurrency = it }
            }
        }
        NetActionRow {
            TextButton(onClick = { start() }, enabled = !running && host.isNotBlank() && ports.isNotEmpty()) {
                Text(if (running) "探测中…" else "开始探测")
            }
            TextButton(onClick = { stop() }, enabled = running) { Text("停止") }
            NetVLine()
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(renderPorts(host, results)))
                    status = "已复制探测结果"
                },
                enabled = results.isNotEmpty(),
            ) { Text("复制") }
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val name = "ports-" + host.trim().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
                        status = saveToPane(container, name, renderPorts(host, results).toByteArray())
                        saving = false
                    }
                },
                enabled = !saving && results.isNotEmpty(),
            ) { Text("存到窗格") }
            NetChip("只看开放", selected = onlyOpen, enabled = results.isNotEmpty()) { onlyOpen = !onlyOpen }
        }
        if (running || done > 0) {
            NetProgress(if (total > 0) done.toFloat() / total else null, Modifier.padding(top = 2.dp))
        }
        if (status.isNotEmpty()) NetStatusLine(status)
        val shown = if (onlyOpen) results.filter { it.open } else results
        if (results.isNotEmpty()) {
            NetStatBar(
                items = listOf(
                    "开放" to "${results.count { it.open }}",
                    "已探" to "$done/$total",
                ),
                valueColor = if (results.any { it.open }) NetGreen else null,
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(MtSpec.CornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        ) {
            if (shown.isEmpty()) {
                item {
                    NetEmptyHint(
                        when {
                            running -> "探测中… $done / $total"
                            results.isEmpty() -> "填主机 + 端口后开始探测\n\n开放端口会显示 banner（SSH / FTP / Redis 等会先发问候）"
                            else -> "没有开放端口（${results.size} 个已探端口全部关闭或被过滤）"
                        },
                        Modifier.fillMaxWidth().padding(vertical = 60.dp),
                    )
                }
            } else {
                items(shown, key = { it.port }) { r ->
                    NetResultRow(
                        primary = "${r.port}",
                        secondary = (NetToolboxKit.portLabel(r.port) ?: "") +
                            (r.banner?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            " · ${r.elapsedMs}ms",
                        badge = if (r.open) "开放" else "关闭",
                        badgeColor = if (r.open) NetGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            clipboard.setText(AnnotatedString("${host.trim()}:${r.port}"))
                            status = "已复制 ${host.trim()}:${r.port}"
                        },
                    )
                }
            }
        }
    }
}

/** 单端口探测：连通返回 open=true 并尽力读 banner（不读也不影响判定）。 */
private suspend fun probePort(host: String, port: Int, timeoutMs: Int): NetToolboxKit.PortResult =
    withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        var banner: String? = null
        val open = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                // 有些服务（HTTP / WebDAV）不主动说话：给 250ms 机会，读不到就算了
                socket.soTimeout = 250
                banner = runCatching {
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            true
        }.getOrDefault(false)
        NetToolboxKit.PortResult(
            port = port,
            open = open,
            banner = banner?.take(80),
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }

/** 探测结果渲染成纯文本（复制 / 保存共用）。 */
private fun renderPorts(host: String, results: List<NetToolboxKit.PortResult>): String = buildString {
    append("# 端口探测 ").append(host.trim()).append('\n')
    append("# ").append(NetToolboxKit.portSummary(results, 0).substringBefore(" · 用时")).append('\n')
    results.forEach { r ->
        append(if (r.open) "OPEN  " else "closed")
        append('\t').append(r.port)
        NetToolboxKit.portLabel(r.port)?.let { append('\t').append(it) }
        r.banner?.takeIf { it.isNotBlank() }?.let { append('\t').append(it) }
        append('\n')
    }
}.trimEnd()
