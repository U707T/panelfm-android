package com.u707t.panelfm.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.LanScanner
import com.u707t.panelfm.ui.browser.ThinProgressBar
import kotlinx.coroutines.launch

/**
 * 局域网扫描：并发 TCP 探测 + banner 识别（SSH/FTP/SMB/WebDAV 端口都能扫）。
 * 点结果即可直接建立对应连接。
 */
@Composable
fun LanScanScreen(container: AppContainer, onBack: () -> Unit, onPick: (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val prefixes = remember { LanScanner.localPrefixes() }
    var port by remember { mutableStateOf(22) }
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf<List<LanScanner.Host>>(emptyList()) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("局域网扫描", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        Text(
            "本机网段：" + if (prefixes.isEmpty()) "未检测到（需要局域网访问权限）" else prefixes.joinToString(", ") + ".0/24",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("端口：", style = MaterialTheme.typography.labelSmall)
            listOf(22, 21, 445, 80, 443).forEach { p ->
                TextButton(onClick = { port = p }) {
                    Text(
                        p.toString(),
                        color = if (p == port) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                enabled = !scanning && prefixes.isNotEmpty(),
                onClick = {
                    scanning = true
                    done = 0
                    found = emptyList()
                    total = prefixes.size * 254
                    scope.launch {
                        LanScanner.scan(
                            prefixes = prefixes,
                            port = port,
                            timeoutMs = 350,
                            concurrency = 64,
                            // 进度 = 已探测地址数（旧实现用「命中数」当进度 → 进度条几乎永远走不满）
                            onProgress = { probed, all -> done = probed.coerceAtMost(all) },
                        ) { host ->
                            found = (found + host).sortedBy { it.address.substringAfterLast('.').toIntOrNull() ?: 0 }
                        }
                        scanning = false
                    }
                },
            ) { Text(if (scanning) "扫描中…" else "开始扫描") }
        }

        if (scanning || done > 0) {
            ThinProgressBar(
                if (total > 0) done.toFloat() / total else 0f,
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        Text(
            "已发现 ${found.size} 台（进度 $done/$total）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(found, key = { it.address }) { host ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(host.address, host.port) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${host.address}:${host.port}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            host.banner?.takeIf { it.isNotBlank() } ?: "已连通（未识别 banner）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("建连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
