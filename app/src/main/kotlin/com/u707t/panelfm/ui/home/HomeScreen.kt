package com.u707t.panelfm.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.SectionHeader
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import kotlinx.coroutines.launch

/**
 * 主页四段：本地 / 网络 / 后台 / 工具（MT 语义）。
 * 点击任意存储 → 在当前聚焦窗格打开并进入双列页。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddConnection: () -> Unit,
    onEditConnection: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connections by container.connections.collectAsState()
    val tasks by container.engine.tasks.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<ConnectionConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectionConfig?>(null) }

    var storageGranted by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true)
    }
    var localNetGranted by remember { mutableStateOf(LocalNetwork.isGranted(context)) }

    val requestStorage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageGranted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    }
    val requestLocalNet = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        localNetGranted = it
    }

    LaunchedEffect(Unit) {
        container.reloadConnections()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddConnection) {
                Text("＋", style = MaterialTheme.typography.titleLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("PanelFM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "双列文件管理器 · 本地 / FTP / FTPS / WebDAV（SFTP·SMB·S3 在 M4–M6 接入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenTasks() }) { Text("任务") }
                IconButton(onClick = onOpenSettings) { Text("⚙", style = MaterialTheme.typography.titleMedium) }
            }

            // 授权提示
            if (!storageGranted) {
                PermissionCard(
                    title = "需要「所有文件访问」权限",
                    detail = "Android 11+ 必须授予后才能完整浏览 /storage/emulated/0（否则只能用应用私有目录）。",
                    actionLabel = "去授权",
                    onAction = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${context.packageName}"))
                        runCatching { requestStorage.launch(intent) }
                    },
                )
            }
            if (!localNetGranted) {
                PermissionCard(
                    title = "需要「局域网访问」权限（Android 17）",
                    detail = "未授权时访问 NAS / Alist / SMB 会直接超时，且不会有任何提示。",
                    actionLabel = "去授权",
                    onAction = { runCatching { requestLocalNet.launch(LocalNetwork.PERMISSION) } },
                )
            }

            // ---- 本地
            SectionHeader("本地")
            val volumes = remember { LocalVolumes.volumes(context) }
            volumes.forEach { volume ->
                HomeRow(
                    title = volume.label,
                    subtitle = volume.path,
                    isDirectory = true,
                    onClick = {
                        container.browser.open(
                            container.browser.state.value.focused,
                            LocalVolumes.uri(volume, "/"),
                            connectionId = null,
                            label = volume.label,
                        )
                        onOpenBrowser()
                    },
                )
            }

            // ---- 网络
            SectionHeader("网络存储")
            if (connections.isEmpty()) {
                HomeRow(
                    title = "还没有网络存储",
                    subtitle = "点右下角 ＋ 添加 FTP / FTPS / WebDAV",
                    isDirectory = false,
                    onClick = onAddConnection,
                )
            }
            connections.forEach { config ->
                HomeRow(
                    title = config.name.ifBlank { config.host },
                    subtitle = "${config.type.label} · ${config.host}:${config.port}${config.basePath.takeIf { it != "/" } ?: ""}",
                    isDirectory = false,
                    trailing = if (connecting == config.id) "连接中…" else "",
                    onClick = {
                        connecting = config.id
                        scope.launch {
                            try {
                                container.openConnection(config)
                                val uri = VfsUri.of(
                                    config.scheme,
                                    "${config.host}:${config.port}",
                                    config.basePath.ifBlank { "/" },
                                    "c=${config.id}",
                                )
                                container.browser.open(container.browser.state.value.focused, uri, config.id, config.name)
                                connecting = null
                                onOpenBrowser()
                            } catch (e: Exception) {
                                connecting = null
                                status = (e as? VfsException)?.userMessage ?: (e.message ?: "连接失败")
                            }
                        }
                    },
                    onMore = { menuFor = config },
                )
            }

            // ---- 后台
            SectionHeader("后台任务")
            val active = tasks.filter {
                val s = it.state.value
                s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
            }
            if (active.isEmpty()) {
                HomeRow(title = "没有进行中的任务", subtitle = "双列页里 ⇄ 复制/移动会出现在这里", isDirectory = false, onClick = onOpenTasks)
            } else {
                active.take(3).forEach { task ->
                    val s = task.state.value
                    val subtitle = when (s) {
                        is TaskState.Running -> "${s.currentName} · ${Fmt.transferred(s.doneBytes, s.totalBytes)} · ${Fmt.speed(s.speedBps)}"
                        is TaskState.Paused -> "已暂停"
                        is TaskState.WaitingConflict -> "等待冲突选择"
                        TaskState.Queued -> "排队中"
                        else -> ""
                    }
                    HomeRow(title = task.title, subtitle = subtitle, isDirectory = false, onClick = onOpenTasks)
                }
            }

            // ---- 工具
            SectionHeader("工具")
            HomeRow(title = "设置", subtitle = "主题 / 排序 / 并发 / User-Agent", isDirectory = false, onClick = onOpenSettings)
            HomeRow(title = "传输任务", subtitle = "取消 / 暂停 / 重试", isDirectory = false, onClick = onOpenTasks)
            HomeRow(
                title = "关于",
                subtitle = "PanelFM 0.1.0 · 仅文件管理与预览，不含任何逆向功能",
                isDirectory = false,
                onClick = { status = "复刻 MT 管理器的「文件管理 + 预览 + 双列 + 多协议」，不含逆向能力" },
            )
            Box(Modifier.padding(bottom = 90.dp))
        }
    }

    status?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = { TextButton(onClick = { status = null }) { Text("知道了") } },
            ) { Text(msg) }
        }
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(4000)
            status = null
        }
    }

    menuFor?.let { config ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(config.name.ifBlank { config.host }) },
            text = { Text("${config.type.label} · ${config.host}:${config.port}") },
            confirmButton = { TextButton(onClick = { menuFor = null; onEditConnection(config.id) }) { Text("编辑") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        container.scope.launch { container.registry.closeAll(); container.reloadConnections() }
                        menuFor = null
                        status = "已断开该连接的会话"
                    }) { Text("断开") }
                    TextButton(onClick = { deleteTarget = config; menuFor = null }) { Text("删除") }
                }
            },
        )
    }

    deleteTarget?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除连接") },
            text = { Text("确定删除「${config.name.ifBlank { config.host }}」？口令也会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.connectionDao.delete(config.id)
                        container.reloadConnections()
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PermissionCard(title: String, detail: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clickable { onAction() }
            .padding(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HomeRow(
    title: String,
    subtitle: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    trailing: String = "",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileIcon(name = title, isDirectory = isDirectory, size = 38.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
                Text("⋮", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
