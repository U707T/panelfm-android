package com.u707t.panelfm.ui.connections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.DefaultGateway
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.LanScanner
import com.u707t.panelfm.core.common.PortScan
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.ui.tools.NetActionRow
import com.u707t.panelfm.ui.tools.NetChip
import com.u707t.panelfm.ui.tools.NetEmptyHint
import com.u707t.panelfm.ui.tools.NetGreen
import com.u707t.panelfm.ui.tools.NetNumField
import com.u707t.panelfm.ui.tools.NetProgress
import com.u707t.panelfm.ui.tools.NetResultRow
import com.u707t.panelfm.ui.tools.NetStatBar
import com.u707t.panelfm.ui.tools.NetStatusLine
import com.u707t.panelfm.ui.tools.NetVLine
import com.u707t.panelfm.ui.tools.saveToPane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ================================================================================================
// 局域网扫描（v2.1.2：与「网络工具箱 → 端口探测」合并）
//
// 合并的原因：
//  - 两者本来就是同一件事的两种切法 ——「网段 × 单端口」（找哪台机器开着 SSH）和
//    「单机 × 多端口」（我连不上这台机器，是哪个端口没开）。放在两个入口里，用户要先想
//    「我该点哪个」，而排查「连不上」时这两步往往先后都要做。
//  - 旧端口面板是闪退源（逐条协程扇出 + 无上限 banner 读取 + 组合期作用域没兜底），
//    正好借合并把它整体换成有界引擎 PortScan。
//
// 现在的形状：目标（网段 / 单机）+ 端口列表（快捷多选 / 预设 / 区间）+ 结果（点一下建连接）。
// ================================================================================================

/** 扫描模式：网段发现，还是单机多端口。 */
private enum class LanScanMode(val label: String) {
    SUBNET("网段"),
    HOST("单机"),
}

/**
 * 局域网扫描：并发 TCP 探测 + banner 识别。
 *
 * 默认扫描本机网段；切到「单机」后目标是一个 IP / 域名，可以一次探多个端口。
 * 点结果直接建立对应连接（协议按端口预选）。
 */
@Composable
fun LanScanScreen(container: AppContainer, onBack: () -> Unit, onPick: (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefixes = remember { LanScanner.localPrefixes() }
    // Android 17+：未授权「局域网访问」时 TCP 探测全部失败——扫描会静默 0 台，必须先给明确引导
    var localNetGranted by remember { mutableStateOf(LocalNetwork.isGranted(context)) }
    val requestLocalNet = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        localNetGranted = it
    }
    val settings by container.settings.collectAsState()
    val history = settings.inputHistory[PrefsStore.RecordKeys.NET_PORT_HOST].orEmpty()

    var mode by remember { mutableStateOf(LanScanMode.SUBNET) }
    // 默认网关留到切「单机」时再读：本页主力是网段扫描，没必要一进来就读 /proc/net/route
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(defaultPortsFor(LanScanMode.SUBNET)) }
    var timeoutMs by remember { mutableStateOf("500") }
    var concurrency by remember { mutableStateOf("64") }
    var showMore by remember { mutableStateOf(false) }
    var onlyOpen by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<PortScan.Result>>(emptyList()) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val parsed = remember(portText) { PortScan.parsePortsDetailed(portText) }
    val ports = parsed.list
    val hosts = remember(mode, host, prefixes) {
        when (mode) {
            LanScanMode.SUBNET -> prefixes.flatMap { LanScanner.hosts(it) }
            LanScanMode.HOST -> listOf(host.trim()).filter { it.isNotEmpty() }
        }
    }
    val plan = hosts.size * ports.size
    val overLimit = plan > PortScan.MAX_PROBES
    val targetLine = remember(mode, host, prefixes, ports) { describeTarget(mode, host, prefixes, ports) }

    fun switchMode(next: LanScanMode) {
        if (next == mode) return
        // 端口列表还停在「上一模式的默认值」时跟着切；用户手改过就不动
        if (portText == defaultPortsFor(mode)) portText = defaultPortsFor(next)
        if (next == LanScanMode.HOST && host.isBlank()) host = DefaultGateway.ipv4(context) ?: ""
        mode = next
    }

    fun togglePort(p: Int) {
        val cur = ports.toMutableSet()
        if (!cur.remove(p)) cur += p
        portText = cur.sorted().joinToString(",")
    }

    fun start() {
        if (scanning) return
        if (hosts.isEmpty()) {
            status = if (mode == LanScanMode.SUBNET) {
                "失败：未检测到本机网段（请先连 Wi-Fi / 有线网）"
            } else {
                "失败：请先填主机（IP / 域名）"
            }
            return
        }
        if (ports.isEmpty()) {
            status = "失败：端口列表为空（示例：22,80,443 或 1-1024）"
            return
        }
        if (overLimit) {
            status = "失败：目标太多（${hosts.size} × ${ports.size} = $plan 次 > 上限 ${PortScan.MAX_PROBES}），请减少端口"
            return
        }
        scanning = true
        done = 0
        total = plan
        elapsedMs = 0
        results = emptyList()
        status = "扫描中…"
        val timeout = timeoutMs.toIntOrNull()?.coerceIn(100, 10_000) ?: 500
        val conc = concurrency.toIntOrNull()?.coerceIn(1, 128) ?: 64
        val mode0 = mode
        val hosts0 = hosts
        val ports0 = ports
        job = scope.launch {
            // 组合期的 rememberCoroutineScope 没有异常处理器：这里漏一个异常就是整个 App 闪退。
            // 引擎本身不抛（PortScan.probe 把失败都收成 open=false），这里仍然兜住「其余一切」。
            val acc = ArrayList<PortScan.Result>()
            try {
                if (mode0 == LanScanMode.HOST) {
                    runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_PORT_HOST, hosts0.first()) }
                }
                PortScan.scan(hosts0, ports0, timeout, conc).collect { ev ->
                    when (ev) {
                        is PortScan.Event.Progress -> {
                            done = ev.done
                            total = ev.total
                        }
                        is PortScan.Event.Found -> {
                            val r = ev.result
                            // 网段模式只留开放端口（254 台 × N 端口的关闭项列出来没法看）；
                            // 单机模式全留，交给「只看开放」开关
                            if (r.open || mode0 == LanScanMode.HOST) {
                                acc += r
                                results = PortScan.sortResults(acc)
                            }
                        }
                        is PortScan.Event.Finished -> {
                            elapsedMs = ev.elapsedMs
                            done = ev.total
                            total = ev.total
                            status = if (ev.open == 0) {
                                "完成：没有发现开放端口（目标不可达 / 全部被过滤）· 用时 ${Fmt.duration(ev.elapsedMs)}"
                            } else {
                                "完成：" + PortScan.summary(ev.open, ev.total, ev.elapsedMs)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                scanning = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scanning = false
        status = "已停止（已完成 $done / $total）"
    }

    val shown = if (mode == LanScanMode.HOST && !onlyOpen) results else results.filter { it.open }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "局域网扫描", subtitle = "网段发现 · 端口探测", onBack = onBack)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetChip(LanScanMode.SUBNET.label, selected = mode == LanScanMode.SUBNET, enabled = !scanning) {
                switchMode(LanScanMode.SUBNET)
            }
            NetChip(LanScanMode.HOST.label, selected = mode == LanScanMode.HOST, enabled = !scanning) {
                switchMode(LanScanMode.HOST)
            }
            Text(
                when (mode) {
                    LanScanMode.SUBNET -> "扫本机网段里的主机"
                    LanScanMode.HOST -> "扫一台主机的多个端口"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
            )
        }

        if (!localNetGranted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未授权「局域网访问」（Android 17）——扫描结果不可信",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { runCatching { requestLocalNet.launch(LocalNetwork.PERMISSION) } }) { Text("去授权") }
            }
        }

        when (mode) {
            LanScanMode.SUBNET -> Text(
                "本机网段：" + if (prefixes.isEmpty()) {
                    "未检测到（请确认 Wi-Fi / 网络连接）"
                } else {
                    prefixes.joinToString(", ") + ".0/24"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            LanScanMode.HOST -> HistoryTextField(
                value = host,
                onValueChange = { host = it },
                label = "主机（IP / 域名）",
                history = history,
                placeholder = "192.168.1.1",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                onClearHistory = {
                    scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_PORT_HOST) }
                },
            )
        }

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("端口（逗号分隔，支持 1-1024 区间）", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            isError = parsed.truncated,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 快捷端口：多选开关（再点一次取消）。端口列表是一段大区间时不开多选，避免把区间展开成一长串
            val canToggle = ports.size <= 32 && !portText.contains('-')
            lanScanQuickPorts.forEach { p ->
                NetChip(p.toString(), selected = p in ports, enabled = !scanning && canToggle) { togglePort(p) }
            }
            NetChip(if (showMore) "更多 ︿" else "更多 ﹀") { showMore = !showMore }
        }
        if (showMore) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetChip("常用端口", enabled = !scanning) {
                    portText = PortScan.CommonPorts.joinToString(",") { it.first.toString() }
                }
                NetChip("Web", enabled = !scanning) { portText = "80,443,8080,8443,8888" }
                NetChip("数据库", enabled = !scanning) { portText = "3306,5432,6379,27017,1433,1521" }
                NetChip("远程", enabled = !scanning) { portText = "22,23,3389,5900" }
                NetChip("清空", enabled = !scanning) { portText = "" }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetNumField("超时(ms)", timeoutMs, Modifier.weight(1f)) { timeoutMs = it }
                NetNumField("并发", concurrency, Modifier.weight(1f)) { concurrency = it }
            }
        }

        Text(
            when {
                hosts.isEmpty() -> if (mode == LanScanMode.SUBNET) {
                    "未检测到本机网段（请先连 Wi-Fi / 有线网）"
                } else {
                    "填主机后可开始"
                }
                ports.isEmpty() -> "端口列表为空（示例：22,80,443 或 1-1024）"
                overLimit -> "目标太多：${hosts.size} × ${ports.size} = $plan 次" +
                    "（上限 ${PortScan.MAX_PROBES}），请减少端口"
                parsed.truncated -> "端口过多，只取前 ${ports.size} 个；将探测 ${plan} 次"
                else -> "将探测：${hosts.size} ${if (mode == LanScanMode.SUBNET) "台主机" else "个主机"} × ${ports.size} 个端口 = $plan 次"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (overLimit || hosts.isEmpty() || ports.isEmpty()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )

        NetActionRow {
            TextButton(
                onClick = { start() },
                enabled = !scanning && hosts.isNotEmpty() && ports.isNotEmpty() && !overLimit,
            ) { Text(if (scanning) "扫描中…" else "开始扫描") }
            TextButton(onClick = { stop() }, enabled = scanning) { Text("停止") }
            NetVLine()
            TextButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            PortScan.render("局域网扫描", targetLine, results, elapsedMs)
                        )
                    )
                    status = "已复制 ${results.size} 条结果"
                },
                enabled = results.isNotEmpty(),
            ) { Text("复制") }
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val name = "lan-" + sanitizeName(
                            if (mode == LanScanMode.SUBNET) prefixes.firstOrNull() ?: "scan" else host.trim()
                        ) + ".txt"
                        status = saveToPane(
                            container,
                            name,
                            PortScan.render("局域网扫描", targetLine, results, elapsedMs).toByteArray(),
                        )
                        saving = false
                    }
                },
                enabled = !saving && results.isNotEmpty(),
            ) { Text("存到窗格") }
            if (mode == LanScanMode.HOST) {
                NetChip("只看开放", selected = onlyOpen, enabled = results.isNotEmpty()) { onlyOpen = !onlyOpen }
            }
        }

        if (scanning || done > 0) {
            NetProgress(
                if (total > 0) done.toFloat() / total else null,
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        if (status.isNotEmpty()) NetStatusLine(status)
        NetStatBar(
            items = buildList {
                add("已发现" to "${results.count { it.open }}")
                add("已探" to "$done/$total")
                if (elapsedMs > 0) add("用时" to Fmt.duration(elapsedMs))
            },
            valueColor = if (results.any { it.open }) NetGreen else null,
        )

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
                            scanning -> "扫描中… $done / $total"
                            results.isEmpty() -> "选好目标与端口后开始扫描\n\n" +
                                "开放端口会显示 banner（SSH / FTP / Redis 等会先发问候）；点结果直接建连接"
                            else -> "没有开放端口（已探 ${results.size} 个组合）"
                        },
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                    )
                }
            } else {
                items(shown, key = { it.host + ":" + it.port }) { r ->
                    NetResultRow(
                        primary = "${r.host}:${r.port}",
                        secondary = (r.banner?.takeIf { it.isNotBlank() } ?: "已连通") + " · ${r.elapsedMs}ms",
                        badge = if (r.open) (PortScan.portLabel(r.port) ?: "开放") else "关闭",
                        badgeColor = if (r.open) NetGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { onPick(r.host, r.port) },
                    )
                }
            }
        }
    }
}

/** 扫描页默认端口：网段扫描只开两个「本 App 真会连」的口子（254 × 2 = 508 次，几秒出结果）。 */
private fun defaultPortsFor(mode: LanScanMode = LanScanMode.SUBNET): String =
    if (mode == LanScanMode.SUBNET) "5244,22" else "22,80,443,445,3306,5432,6379,8080"

/** 扫描页的快捷端口列表：**5244（OpenList / Alist 的 WebDAV）在最前**。 */
internal val lanScanQuickPorts = listOf(5244, 22, 80, 443, 445, 21)

/** 端口小胶囊已统一到 `NetChip`（多选开关），这里只保留连接协议的映射。 */
internal fun connectionTypeForScanPort(port: Int): ConnectionType? = when (port) {
    5244, 80, 443 -> ConnectionType.WEBDAV
    22 -> ConnectionType.SFTP
    21 -> ConnectionType.FTP
    445 -> ConnectionType.SMB
    else -> null
}

/** 保存用的文件名片段：去掉路径分隔符与奇怪字符。 */
private fun sanitizeName(raw: String): String =
    raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "scan" }

/** 结果文件与状态行里的目标描述。 */
private fun describeTarget(
    mode: LanScanMode,
    host: String,
    prefixes: List<String>,
    ports: List<Int>,
): String {
    val where = if (mode == LanScanMode.SUBNET) {
        if (prefixes.isEmpty()) "网段 未检测到" else "网段 " + prefixes.joinToString(", ") { "$it.0/24" }
    } else {
        "主机 " + host.trim().ifEmpty { "—" }
    }
    return where + " × 端口 " + (if (ports.isEmpty()) "—" else ports.joinToString(","))
}
