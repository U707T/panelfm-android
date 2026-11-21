package com.u707t.panelfm.ui.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtListRow
import com.u707t.panelfm.tools.TrashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.u707t.panelfm.ui.browser.refresh
import com.u707t.panelfm.ui.browser.refreshAll

// ---------------------------------------------------------------------------
// 回收站（本地文件：删除 → 回收站，可还原 / 彻底删除）
// ---------------------------------------------------------------------------

@Composable
fun TrashScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    // 索引读盘要放在 IO 上：list() 是「读 index.json + 解析 JSON」，条目多时是几十毫秒级；
    // 旧实现直接在组合期同步读（remember { container.trash.list() }），
    // 点进回收站会白卡一帧，条目越多越明显。
    val items by produceState(initialValue = emptyList<TrashService.Entry>(), version) {
        value = withContext(Dispatchers.IO) { container.trash.list() }
    }
    // 破坏性操作二次确认：旧实现「清空 / 彻底删除」单击即执行，
    // 比普通删除（有确认框）还少一道确认，而这两处恰恰不可逆。
    var confirmPurgeAll by remember { mutableStateOf(false) }
    var purgeTarget by remember { mutableStateOf<TrashService.Entry?>(null) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "回收站",
            subtitle = "${items.size} 项",
            onBack = onBack,
        ) {
            TextButton(
                enabled = items.isNotEmpty(),
                onClick = { confirmPurgeAll = true },
            ) { Text("清空") }
        }

        if (items.isEmpty()) {
            EmptyState("回收站是空的", "删除本地文件会先进这里，可随时还原")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { entry ->
                    MtListRow(
                        title = entry.name,
                        subtitle = "原位置：${entry.originalPath} · ${Fmt.fullTime(entry.deletedAt)}" +
                            if (entry.size > 0) " · ${Fmt.size(entry.size)}" else "",
                        icon = { FileIcon(name = entry.name, isDirectory = entry.isDirectory, size = 38.dp) },
                        onClick = {
                            scope.launch {
                                val ok = container.trash.restore(entry)
                                container.browser.refreshAll()
                                version++
                                container.browser.showStatus(
                                    if (ok) "已还原「${entry.name}」（原位置重名时自动改为「名称 (1)」）"
                                    else "还原失败：原位置不可写"
                                )
                            }
                        },
                        trailing = {
                            Row {
                                TextButton(onClick = { purgeTarget = entry }) {
                                    Text("彻底删除", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmPurgeAll) {
        AlertDialog(
            onDismissRequest = { confirmPurgeAll = false },
            title = { Text("清空回收站？") },
            text = { Text("将永久删除回收站里的 ${items.size} 项，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurgeAll = false
                    scope.launch {
                        container.trash.purgeAll()
                        version++
                        container.browser.showStatus("已清空回收站")
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurgeAll = false }) { Text("取消") } },
        )
    }
    purgeTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除？") },
            text = { Text("「${entry.name}」将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    purgeTarget = null
                    scope.launch {
                        container.trash.purge(entry)
                        version++
                        container.browser.showStatus("已彻底删除「${entry.name}」")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("取消") } },
        )
    }
}

// ---------------------------------------------------------------------------
// 远程管理（内置只读 HTTP 服务：电脑浏览器直接浏览/下载当前窗格目录）
// ---------------------------------------------------------------------------

@Composable
fun RemoteScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 第 8 批 🔵4：状态从服务真实值初始化 —— 服务不随本页销毁（启动后切走再回来），
    // 旧实现每次进页都显示「启动服务」+空 URL，与「服务仍在跑」的事实脱节。
    val remote = container.remote
    var running by remember { mutableStateOf(remote.running) }
    var url by remember { mutableStateOf(remote.url) }
    var log by remember {
        mutableStateOf(
            if (remote.running) {
                "服务运行中：${remote.url}（目录：${remote.servedRoot?.displayPath ?: "?"}）"
            } else ""
        )
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "远程管理", onBack = onBack)
        Text(
            "使用下方完整授权链接浏览/下载当前目录（只读）。链接持有人均可访问；请勿转发，只在可信网络使用。应用私有目录及无法验证链接边界的存储不支持分享。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                scope.launch {
                    if (running) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { remote.stop() }
                        running = false
                        url = ""
                        log = "已停止，旧授权链接已失效"
                    } else {
                        val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            remote.start(container.browser.state.value.focusedPane.uri)
                        }
                        running = started != null
                        url = started ?: ""
                        log = if (started == null) "启动失败：请检查目录权限；私有目录和无法验证符号链接边界的存储不支持分享"
                        else "已启动（目录：${remote.servedRoot?.displayPath ?: "?"}）"
                    }
                }
            }) { Text(if (running) "停止服务" else "启动服务") }
            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // U11：地址可一键复制（旧实现只能肉眼抄）
            if (url.isNotBlank()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                    log = "已复制到剪贴板：$url"
                }) { Text("复制") }
            }
        }
        Text(log, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "提示：完整链接包含本次授权。停止或重启服务后旧链接失效；仅绑定一个本地接口，未找到局域网地址时只允许本机访问。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(16.dp),
        )
    }
}
