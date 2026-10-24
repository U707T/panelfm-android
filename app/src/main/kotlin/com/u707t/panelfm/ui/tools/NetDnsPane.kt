package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================================================================================================
// DNS 查询（v2.1.0 新增）
//
// 为什么加：排查「连不上服务器」时第一件事是确认域名解析到哪。
// 系统 `InetAddress` 只给 A/AAAA，且不能指定服务器 —— 看不出「本地 DNS 污染」「只有内部 DNS 有记录」
// 这类问题。这里直接对指定服务器发原始查询，能看到 TTL / CNAME 链 / MX / TXT / NS / SOA。
//
// 交互：域名 → 记录类型 chips（点一下即查询）→ 结果列表（点单条复制）。
// 服务器默认用系统当前 DNS 不可靠，所以给固定预设 + 「自定义」。
// ================================================================================================

/** 记录类型 chips 用短标签（10 个 chip 要在两行内排完）。 */
private val dnsTypes = DnsKit.RType.entries

@Composable
internal fun DnsPane(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by container.settings.collectAsState()
    val history = settings.inputHistory[PrefsStore.RecordKeys.NET_DNS_HOST].orEmpty()

    var host by remember { mutableStateOf("www.baidu.com") }
    var type by remember { mutableStateOf(DnsKit.RType.A) }
    var server by remember { mutableStateOf(NetToolboxKit.DnsServers.first().first) }
    var customOpen by remember { mutableStateOf(false) }
    var customServer by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf<List<DnsKit.Record>>(emptyList()) }
    var rawText by remember { mutableStateOf("") }
    /** 结果来自「查询全部」（此时没有单一响应码，统计条不显示 NOERROR） */
    var combined by remember { mutableStateOf(false) }
    var rcodeText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun effectiveServer(): String = if (customOpen) customServer.trim() else server

    fun runQuery(rtype: DnsKit.RType) {
        if (running) return
        val name = host.trim()
        if (name.isEmpty()) {
            status = "失败：请先填域名"
            return
        }
        val srv = effectiveServer()
        if (srv.isEmpty()) {
            status = "失败：请填自定义 DNS 服务器"
            return
        }
        running = true
        combined = false
        status = "查询中：$name ${rtype.label} @$srv…"
        answers = emptyList()
        rawText = ""
        scope.launch {
            try {
                runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_DNS_HOST, name) }
                val r = withContext(Dispatchers.IO) { runCatching { DnsKit.query(srv, name, rtype) } }
                r.fold(
                    onSuccess = { rep ->
                        answers = rep.answers
                        rcodeText = if (rep.ok) "NOERROR" else rep.rcodeText()
                        rawText = renderReply(name, rtype, srv, rep)
                        status = when {
                            !rep.ok -> "失败：${rep.rcodeText()}"
                            rep.answers.isEmpty() -> "完成：${rtype.label} 无记录（NOERROR 但没有答案）"
                            else -> "完成：${rep.answers.size} 条 ${rtype.label} 记录"
                        }
                    },
                    onFailure = { e ->
                        answers = emptyList()
                        rcodeText = ""
                        status = "失败：${e.message ?: e.javaClass.simpleName}"
                    },
                )
            } finally {
                running = false
            }
        }
    }

    fun runAll() {
        if (running) return
        val name = host.trim()
        if (name.isEmpty()) {
            status = "失败：请先填域名"
            return
        }
        val srv = effectiveServer()
        if (srv.isEmpty()) {
            status = "失败：请填自定义 DNS 服务器"
            return
        }
        running = true
        combined = true
        answers = emptyList()
        status = "查询中：$name（常用类型全查）…"
        scope.launch {
            try {
                runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_DNS_HOST, name) }
                val types = listOf(
                    DnsKit.RType.A, DnsKit.RType.AAAA, DnsKit.RType.CNAME,
                    DnsKit.RType.MX, DnsKit.RType.TXT, DnsKit.RType.NS,
                )
                val text = StringBuilder()
                val all = ArrayList<DnsKit.Record>()
                var firstError: String? = null
                var okCount = 0
                withContext(Dispatchers.IO) {
                    types.forEach { t ->
                        val r = runCatching { DnsKit.query(srv, name, t, timeoutMs = 2500) }
                        val rep = r.getOrNull()
                        if (rep == null) {
                            if (firstError == null) firstError = r.exceptionOrNull()?.message
                            text.append("${t.label}: 查询失败（${r.exceptionOrNull()?.message ?: "?"}）\n")
                        } else {
                            okCount++
                            all += rep.answers
                            text.append("${t.label}: ")
                                .append(if (rep.ok) "${rep.answers.size} 条" else rep.rcodeText())
                                .append('\n')
                            rep.answers.forEach { rec ->
                                text.append("  ").append(rec.name).append("  ").append(rec.ttl)
                                    .append("s  ").append(rec.type).append("  ").append(rec.value).append('\n')
                            }
                        }
                    }
                }
                answers = all
                rawText = text.toString().trimEnd()
                rcodeText = ""
                status = when {
                    all.isNotEmpty() -> "完成：共 ${all.size} 条记录（$okCount/${types.size} 类型有应答）"
                    firstError != null -> "失败：$firstError"
                    else -> "完成：没有查到任何记录"
                }
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        HistoryTextField(
            value = host,
            onValueChange = { host = it },
            label = "域名 / IP（PTR 查询直接填 IP）",
            history = history,
            placeholder = "www.example.com",
            modifier = Modifier.fillMaxWidth(),
            onClearHistory = {
                scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_DNS_HOST) }
            },
        )
        // 记录类型：点一下即查（少一次「再点查询」的往返）
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dnsTypes.forEach { t ->
                NetChip(t.label, selected = type == t && !combined, enabled = !running) {
                    type = t
                    runQuery(t)
                }
            }
        }
        // 服务器：短名 chips + 自定义
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NetToolboxKit.DnsServers.forEach { (ip, name) ->
                NetChip(name, selected = !customOpen && server == ip, enabled = !running) {
                    customOpen = false
                    server = ip
                }
            }
            NetChip("自定义", selected = customOpen, enabled = !running) { customOpen = !customOpen }
        }
        if (customOpen) {
            OutlinedTextField(
                value = customServer,
                onValueChange = { customServer = it.filter { c -> c.isLetterOrDigit() || c in ".:-" } },
                label = { Text("自定义 DNS 服务器（IP）", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("192.168.1.1") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        } else {
            Text(
                "服务器：${NetToolboxKit.DnsServers.first { it.first == server }.second} $server",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
        NetActionRow {
            TextButton(onClick = { runQuery(type) }, enabled = !running && host.isNotBlank()) {
                Text(if (running) "查询中…" else "查询 ${type.label}")
            }
            TextButton(onClick = { runAll() }, enabled = !running && host.isNotBlank()) { Text("查全部") }
            NetVLine()
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(rawText))
                    status = "已复制查询结果"
                },
                enabled = rawText.isNotEmpty(),
            ) { Text("复制") }
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val name = "dns-" + host.trim().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
                        status = saveToPane(container, name, rawText.toByteArray())
                        saving = false
                    }
                },
                enabled = !saving && rawText.isNotEmpty(),
            ) { Text("存到窗格") }
        }
        if (status.isNotEmpty()) NetStatusLine(status)
        if (answers.isNotEmpty()) {
            NetStatBar(
                items = buildList {
                    add("记录" to "${answers.size}")
                    add("最小TTL" to "${answers.minOfOrNull { it.ttl } ?: 0}s")
                    if (!combined && rcodeText.isNotEmpty()) add("响应码" to rcodeText)
                },
                valueColor = if (!combined && rcodeText.startsWith("NOERROR")) null else NetAmber,
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
            if (answers.isEmpty()) {
                item {
                    NetEmptyHint(
                        when {
                            running -> "查询中…"
                            rcodeText.isNotEmpty() -> "该类型没有记录（$rcodeText）"
                            else -> "输入域名后点记录类型即可查询\n\n点记录行可复制单条值；「查全部」按 A/AAAA/CNAME/MX/TXT/NS 连查"
                        },
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    )
                }
            } else {
                items(answers) { rec ->
                    NetResultRow(
                        primary = rec.value,
                        secondary = "${rec.name} · ${rec.ttl}s",
                        badge = rec.type,
                        onClick = {
                            clipboard.setText(AnnotatedString(rec.value))
                            status = "已复制：${rec.value}"
                        },
                    )
                }
            }
        }
    }
}

/** 把一次应答渲染成可复制 / 可保存的纯文本（dig 风格）。 */
private fun renderReply(host: String, type: DnsKit.RType, server: String, rep: DnsKit.Reply): String =
    buildString {
        append("; 查询 ").append(host).append(' ').append(type.label).append(" @").append(server).append('\n')
        append("; 响应码 ").append(rep.rcodeText())
        if (rep.truncated) append("  (TC=1 应答被截断，可改用 TCP 或减小响应)")
        append('\n')
        rep.answers.forEach {
            append(it.name).append('\t').append(it.ttl).append('\t').append(it.type).append('\t').append(it.value).append('\n')
        }
        if (rep.authorities.isNotEmpty()) {
            append("; 权威\n")
            rep.authorities.forEach {
                append(it.name).append('\t').append(it.ttl).append('\t').append(it.type).append('\t').append(it.value).append('\n')
            }
        }
        if (rep.additionals.isNotEmpty()) {
            append("; 附加\n")
            rep.additionals.forEach {
                append(it.name).append('\t').append(it.ttl).append('\t').append(it.type).append('\t').append(it.value).append('\n')
            }
        }
    }.trimEnd()
