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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtListRow
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

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("回收站", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${items.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                scope.launch {
                    container.trash.purgeAll()
                    version++
                }
            }) { Text("清空") }
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
                                if (!ok) container.browser.showStatus("还原失败：原位置不可写")
                            }
                        },
                        trailing = {
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        container.trash.purge(entry)
                                        version++
                                    }
                                }) { Text("彻底删除", style = MaterialTheme.typography.labelSmall) }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 已安装应用（列表 + 导出 APK 到当前窗格）
// ---------------------------------------------------------------------------

@Composable
fun AppsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

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
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("已安装应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${apps.size} 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用名 / 包名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
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
                        scope.launch {
                            val out = exportApk(context, container, info)
                            status = out
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
    info: ApplicationInfo,
): String = withContext(Dispatchers.IO) {
    runCatching {
        val src = info.sourceDir ?: return@runCatching "无 APK 路径"
        val pane = container.browser.state.value.focusedPane
        val label = context.packageManager.getApplicationLabel(info).toString().replace('/', '_')
        val dest = pane.uri.child("$label.apk")
        val vfs = container.locator.find(pane.uri) ?: return@runCatching "当前窗格不可写"
        val writer = vfs.openWrite(dest, size = File(src).length(), offset = 0L)
        File(src).inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                writer.write(buf, 0, n)
            }
        }
        writer.commit()
        container.browser.refresh(pane.let { container.browser.state.value.focused })
        "已导出到 ${pane.uri.displayPath}/$label.apk"
    }.getOrElse { "导出失败：${it.message}" }
}

// ---------------------------------------------------------------------------
// 远程管理（内置只读 HTTP 服务：电脑浏览器直接浏览/下载当前窗格目录）
// ---------------------------------------------------------------------------

@Composable
fun RemoteScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("远程管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
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
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
