package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.HistoryTextField
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.ui.browser.refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================================================================================================
// 网络工具箱（v2.1.0 重构）
//
// 旧版只有 Ping + HTTP 两个页签，且：
//  - Ping 的统计（丢包 / 平均延迟）藏在原始输出里，没有结论行；「总超时」写死 4 秒，
//    次数改成 20 之后仍然 4 秒被 `-w` 掐断，看起来像「只 ping 了几次」；
//  - HTTP 响应体一律按 UTF-8 硬解（GBK 页面整屏乱码、PNG 刷满替换符）；
//    手写 Host / Content-Length 这类受限头会让 OkHttp 直接抛异常，界面上只有一句「请求失败」；
//  - 没有 DNS 查询、没有端口探测 —— 排查「连不上」缺的就是这两步；
//  - 结果只能在屏幕上读，取不走（这是个文件管理器，结果本该能存成文件）。
//
// 现在三页签：Ping / DNS / HTTP，共用一套输入区 + 统计条 + 结果区（见 NetWidgets）。
// 每页都支持「复制结果」与「存到当前窗格」。
//
// v2.1.2：原「端口」页签已合并进「局域网扫描」（网段 × 多端口 / 单机 × 多端口同一页），
// 引擎换成 core/common 的 PortScan —— 端口面板是旧的闪退源（协程扇出 + 无上限 banner 读取）。
// ================================================================================================

private enum class NetTab(val label: String) {
    PING("Ping"),
    DNS("DNS"),
    HTTP("HTTP"),
}

/**
 * 网络工具箱入口。
 *
 * @param container 提供输入历史（PrefsStore）、当前窗格（保存结果）、协程作用域
 */
@Composable
fun NetToolboxScreen(container: AppContainer, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(NetTab.PING) }

    Column(
        Modifier
            .fillMaxSize()
            .safeAreaPadding(),
    ) {
        MtScreenTopBar(
            title = "网络工具箱",
            subtitle = "Ping · DNS · HTTP",
            onBack = onBack,
        )
        NetTabRow(
            tabs = NetTab.entries.map { it.label },
            current = tab.ordinal,
            onSelect = { tab = NetTab.entries[it] },
        )
        when (tab) {
            NetTab.PING -> PingPane(container)
            NetTab.DNS -> DnsPane(container)
            NetTab.HTTP -> HttpPane(container)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Ping
// ------------------------------------------------------------------------------------------------

@Composable
internal fun PingPane(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by container.settings.collectAsState()
    val history = settings.inputHistory[PrefsStore.RecordKeys.NET_PING_HOST].orEmpty()

    var host by remember { mutableStateOf(NetToolboxKit.PingPresets.first()) }
    var count by remember { mutableStateOf("4") }
    var size by remember { mutableStateOf("64") }
    // 「总超时」默认跟随次数（autoDeadlineSec）：旧版固定 4 秒，改次数后会被提前掐断
    var timeoutAuto by remember { mutableStateOf(true) }
    var timeout by remember { mutableStateOf(NetToolboxKit.autoDeadlineSec(4).toString()) }
    var family by remember { mutableStateOf(0) } // 0 = 自动 / 4 / 6
    var showAdvanced by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf(PingStats()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val procHolder = remember { arrayOfNulls<Process>(1) }
    val scroll = rememberScrollState()

    // 自动超时：次数一变就重算（用户手动改过就不再覆盖）
    LaunchedEffect(count, timeoutAuto) {
        if (timeoutAuto) timeout = NetToolboxKit.autoDeadlineSec(count.toIntOrNull() ?: 4).toString()
    }
    // 输出跟进：每来一行滚到底。先让出一帧，否则 maxValue 还是上一帧的高度，滚不到真正的末尾。
    LaunchedEffect(output) {
        kotlinx.coroutines.yield()
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }
    DisposableEffect(Unit) { onDispose { procHolder[0]?.destroy() } }

    fun start() {
        val target = host.trim()
        if (target.isEmpty() || running) return
        if (!NetToolboxKit.isPingTargetValid(target)) {
            status = "失败：目标不能以 - 开头（会被系统 ping 当成参数）"
            return
        }
        output = ""
        stats = PingStats()
        status = "Ping 中…"
        running = true
        scope.launch {
            // 状态旗标必须有 finally：写历史 / 起进程任何一步失败都不能把按钮永久卡在「Ping 中…」
            try {
                runCatching { container.prefs.addInputHistory(PrefsStore.RecordKeys.NET_PING_HOST, target) }
                val args = NetToolboxKit.buildPingArgs(
                    host = target,
                    count = count.toIntOrNull() ?: 4,
                    size = size.toIntOrNull() ?: 64,
                    timeoutSec = timeout.toIntOrNull() ?: NetToolboxKit.autoDeadlineSec(count.toIntOrNull() ?: 4),
                    family = family.takeIf { it != 0 },
                )
                val p = withContext(Dispatchers.IO) {
                    runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
                }
                if (p == null) {
                    status = "失败：无法启动系统 ping（/system/bin/ping 不可用）"
                    return@launch
                }
                procHolder[0] = p
                withContext(Dispatchers.IO) {
                    try {
                        p.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                withContext(Dispatchers.Main) {
                                    output += line + "\n"
                                    stats = PingStats.feed(stats, line)
                                }
                            }
                        }
                        p.waitFor()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 用户点了「停止」：进程被 destroy、流关闭，属预期路径
                    }
                }
                status = if (stats.received == 0) "失败：没有任何应答（目标不可达 / 被防火墙丢弃）"
                else "完成：" + stats.summary()
            } finally {
                val p = procHolder[0]
                procHolder[0] = null
                if (p != null && p.isAlive) runCatching { p.destroy() }
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        HistoryTextField(
            value = host,
            onValueChange = { host = it },
            label = "目标（域名 / IP）",
            history = history,
            placeholder = "www.baidu.com 或 192.168.1.1",
            modifier = Modifier.fillMaxWidth(),
            onClearHistory = {
                scope.launch { container.prefs.clearInputHistory(PrefsStore.RecordKeys.NET_PING_HOST) }
            },
        )
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NetToolboxKit.PingPresets.forEach { p ->
                NetChip(p, selected = host == p) { host = p }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetNumField("次数", count, Modifier.weight(1f)) { count = it }
            NetChip("IPv4", family == 4) { family = if (family == 4) 0 else 4 }
            NetChip("IPv6", family == 6) { family = if (family == 6) 0 else 6 }
            NetChip(if (showAdvanced) "参数 ︿" else "参数 ﹀") { showAdvanced = !showAdvanced }
        }
        if (showAdvanced) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetNumField("包大小(B)", size, Modifier.weight(1f)) { size = it }
                NetNumField("总超时(s)", timeout, Modifier.weight(1f)) {
                    timeout = it
                    timeoutAuto = false
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (timeoutAuto) "总超时按次数自动推导（每包约 1 秒 + 余量）" else "总超时为手动值",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!timeoutAuto) {
                    TextButton(onClick = { timeoutAuto = true }) { Text("恢复自动", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        NetActionRow {
            TextButton(onClick = { start() }, enabled = !running && host.isNotBlank()) {
                Text(if (running) "Ping 中…" else "开始")
            }
            TextButton(
                onClick = {
                    procHolder[0]?.destroy()
                    status = "已停止（收到 ${stats.received} 个应答）"
                },
                enabled = running,
            ) { Text("停止") }
            NetVLine()
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(output))
                    status = "已复制 ${output.length} 个字符"
                },
                enabled = output.isNotEmpty(),
            ) { Text("复制") }
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val name = "ping-" + host.trim().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
                        status = saveToPane(container, name, output.toByteArray())
                        saving = false
                    }
                },
                enabled = !saving && output.isNotEmpty(),
            ) { Text("存到窗格") }
            TextButton(onClick = { output = ""; stats = PingStats(); status = "" }, enabled = output.isNotEmpty()) {
                Text("清空")
            }
        }
        // 结论行：丢包 / 平均 / 最快 / 最慢 / 抖动，一眼可读（不用去翻原始输出）
        if (stats.transmitted > 0 || stats.received > 0) {
            NetStatBar(
                items = buildList {
                    add("收/发" to "${stats.received}/${stats.transmitted}")
                    add("丢包" to "${stats.lossPercent.toInt()}%")
                    stats.avg?.let { add("平均" to "${round1(it)}ms") }
                    stats.min?.let { add("最快" to "${round1(it)}ms") }
                    stats.max?.let { add("最慢" to "${round1(it)}ms") }
                    stats.jitter?.let { add("抖动" to "${round1(it)}ms") }
                },
                valueColor = if (stats.received == 0) MaterialTheme.colorScheme.error else null,
            )
        }
        if (status.isNotEmpty()) NetStatusLine(status)
        NetOutputBox(
            text = output,
            placeholder = "Ping 输出会显示在这里（调用系统 ping，无需 root）",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp),
            scrollState = scroll,
        )
    }
}

private fun round1(v: Double): String {
    val r = kotlin.math.round(v * 10) / 10
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/** 只收 ASCII 数字的数值输入框（`Char.isDigit()` 会放过全角"４"，`toIntOrNull` 会静默回退默认值）。 */
@Composable
internal fun NetNumField(
    label: String,
    value: String,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter { it in '0'..'9' }.take(6)) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * 把文本写进**当前窗格**（沿用统一的重名策略 `名称 (1).ext`，不静默覆盖同名文件）。
 * 返回一句可直接显示的状态。
 */
internal suspend fun saveToPane(container: AppContainer, baseName: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val state = container.browser.state.value
            val pane = state.focusedPane
            val vfs = container.locator.find(pane.uri) ?: return@runCatching "失败：当前窗格不可写"
            val dest = container.uniqueChild(pane.uri, baseName)
            val writer = vfs.openWrite(dest, size = bytes.size.toLong(), offset = 0L)
            try {
                writer.write(bytes, 0, bytes.size)
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
            container.browser.refresh(state.focused)
            "已保存 ${dest.name} 到 ${pane.uri.displayPath}"
        }.getOrElse { "失败：保存不了（${it.message ?: it.javaClass.simpleName}）" }
    }
