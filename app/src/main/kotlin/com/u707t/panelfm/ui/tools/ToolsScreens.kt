package com.u707t.panelfm.ui.tools

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

// ---------------------------------------------------------------------------
// 回收站（本地文件：删除 → 回收站，可还原 / 彻底删除）
// ---------------------------------------------------------------------------

@Composable
fun TrashScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val items = remember(version) { container.trash.list() }
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
// 已安装应用（列表 + 导出 APK 到当前窗格）
// ---------------------------------------------------------------------------

@Composable
fun AppsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    /** U19：加载完成前不显示「0 个」（旧实现先把 0 闪出来） */
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    /** U7b：同一时间只导出一个，防连点并发写同一目录 */
    var exporting by remember { mutableStateOf(false) }
    // F16：打开即聚焦搜索框
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { searchFocus.requestFocus() }
        keyboard?.show()
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }.getOrDefault(emptyList())
                .filter { it.packageName != context.packageName }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "已安装应用",
            subtitle = if (loaded) "${apps.size} 个" else "读取中…",
            onBack = onBack,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用名 / 包名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(searchFocus),
        )
        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }

        val pm = context.packageManager
        val filtered = apps.filter {
            val label = pm.getApplicationLabel(it).toString()
            query.isBlank() || label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName }) { info ->
                MtListRow(
                    title = pm.getApplicationLabel(info).toString(),
                    subtitle = "${info.packageName} · v" + runCatching { pm.getPackageInfo(info.packageName, 0).versionName }.getOrNull().orEmpty(),
                    icon = { FileIcon(name = "app.apk", isDirectory = false, size = 38.dp) },
                    onClick = {
                        // U7b：导出改到容器作用域执行 —— 旧实现用 rememberCoroutineScope，离开本页即被取消；
                        // 进度直接写 status，完成后同时进浏览器状态栏（返回时能看到结果）
                        if (!exporting) {
                            exporting = true
                            val appLabel = pm.getApplicationLabel(info).toString()
                            val side = container.browser.state.value.focused
                            status = "导出中：$appLabel…"
                            container.scope.launch {
                                val msg = exportApk(context, container, side, info) { done, total ->
                                    val pct = if (total > 0) (done * 100 / total) else -1
                                    status = if (pct >= 0) "导出中：$appLabel $pct%"
                                    else "导出中：$appLabel " + Fmt.transferred(done, total)
                                }
                                status = msg
                                container.browser.showStatus(msg)
                                exporting = false
                            }
                        }
                    },
                    trailing = { Text("导出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

private suspend fun exportApk(
    context: android.content.Context,
    container: AppContainer,
    side: com.u707t.panelfm.ui.browser.PaneSide,
    info: ApplicationInfo,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): String = withContext(Dispatchers.IO) {
    runCatching {
        val src = info.sourceDir ?: return@runCatching "无 APK 路径"
        val pane = container.browser.state.value.pane(side)
        val label = context.packageManager.getApplicationLabel(info).toString().replace('/', '_')
        // 目标名不能固定成 `$label.apk`：`VfsWriter.commit()`（本地实现）是「先删同名再改名」，
        // 直接写会**静默覆盖**已存在的同名 APK。改用统一的重名策略 `名称 (1).apk`。
        val dest = container.uniqueChild(pane.uri, "$label.apk")
        val vfs = container.locator.find(pane.uri) ?: return@runCatching "当前窗格不可写"
        val total = File(src).length()
        val writer = vfs.openWrite(dest, size = total, offset = 0L)
        try {
            File(src).inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastTick = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    writer.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    // 250ms 节流：进度可见但不刷爆重组
                    if (now - lastTick >= 250) {
                        lastTick = now
                        onProgress(done, total)
                    }
                }
            }
            writer.commit()
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw e
        }
        onProgress(total, total)
        container.browser.refresh(side)
        "已导出 ${dest.name} 到 ${pane.uri.displayPath}"
    }.getOrElse { "导出失败：${it.message}" }
}

// ---------------------------------------------------------------------------
// 远程管理（内置只读 HTTP 服务：电脑浏览器直接浏览/下载当前窗格目录）
// ---------------------------------------------------------------------------

@Composable
fun RemoteScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var running by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "远程管理", onBack = onBack)
        Text(
            "在电脑浏览器里打开下面的地址，即可浏览/下载当前窗格目录（只读，仅在局域网内可访问）。",
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
                if (running) {
                    container.remote.stop()
                    running = false
                    url = ""
                } else {
                    scope.launch {
                        val started = container.remote.start(container.browser.state.value.focusedPane.uri)
                        running = started != null
                        url = started ?: ""
                        log = if (started == null) "启动失败（端口被占用？）" else "已启动：$started"
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
            "提示：手机与电脑需在同一局域网，且本应用已获得「局域网访问」权限。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(16.dp),
        )
    }
}
