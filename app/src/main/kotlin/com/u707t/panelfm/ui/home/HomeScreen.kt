package com.u707t.panelfm.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.LocalNetwork
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtFolderGlyph
import com.u707t.panelfm.core.ui.MtListRow
import com.u707t.panelfm.core.ui.SectionHeader
import com.u707t.panelfm.core.ui.UsageBar
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import kotlinx.coroutines.launch

/**
 * 主页（对齐 MT 管理器）：
 * 标题栏（图标 + 名称 + 副标题 + 主题切换 + ⋮） / 本地（带占用条） / 网络 / 工具，分组可折叠。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onScanLan: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenEditor: () -> Unit,
    onAddConnection: () -> Unit,
    onEditConnection: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connections by container.connections.collectAsState()
    val settings by container.settings.collectAsState()
    // 观察 taskEvents（任务状态变化也会刷新），仅用于统计「进行中」数量
    val tasks by container.engine.taskEvents.collectAsState(initial = emptyList())

    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<ConnectionConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectionConfig?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }

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

    var expandLocal by remember { mutableStateOf(true) }
    var expandNet by remember { mutableStateOf(true) }
    var expandTools by remember { mutableStateOf(true) }
    var spaces by remember { mutableStateOf<Map<String, SpaceInfo>>(emptyMap()) }

    val volumes = remember { LocalVolumes.volumes(context) }

    LaunchedEffect(Unit) {
        container.reloadConnections()
    }
    LaunchedEffect(volumes) {
        val map = mutableMapOf<String, SpaceInfo>()
        volumes.forEach { volume ->
            runCatching { container.localVfs.space(LocalVolumes.uri(volume, "/")) }
                .getOrNull()?.let { map[volume.authority] = it }
        }
        spaces = map
    }

    fun openVolume(authority: String, label: String, path: String = "/") {
        container.browser.open(
            container.browser.state.value.focused,
            VfsUri.of("local", authority, path),
            connectionId = null,
            label = label,
        )
        onOpenBrowser()
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
                .safeAreaPadding()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------------- 标题栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    MtFolderGlyph(size = 26.dp, color = MaterialTheme.colorScheme.onSurface)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text("PanelFM", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (settings.themeMode) {
                            ThemeMode.SYSTEM -> "主题跟随系统"
                            ThemeMode.LIGHT -> "浅色主题"
                            ThemeMode.DARK -> "深色主题"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconTextButton(
                    if (settings.themeMode == ThemeMode.DARK) "☀" else "☾",
                    contentDescription = if (settings.themeMode == ThemeMode.DARK) "切换到浅色主题" else "切换到深色主题",
                ) {
                    scope.launch {
                        container.prefs.setTheme(if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK)
                    }
                }
                Box {
                    IconTextButton("⋮", contentDescription = "更多菜单") { showTopMenu = true }
                    DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (settings.themeMode == ThemeMode.SYSTEM) "主题跟随系统 ✓" else "主题跟随系统") },
                            onClick = {
                                showTopMenu = false
                                scope.launch { container.prefs.setTheme(ThemeMode.SYSTEM) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("动态取色：" + if (settings.dynamicColor) "开" else "关") },
                            onClick = {
                                showTopMenu = false
                                scope.launch { container.prefs.setDynamicColor(!settings.dynamicColor) }
                            },
                        )
                        DropdownMenuItem(text = { Text("添加网络存储") }, onClick = { showTopMenu = false; onAddConnection() })
                        DropdownMenuItem(
                            text = { Text("添加本地存储") },
                            onClick = { showTopMenu = false; status = "已自动枚举：根目录 / 内部存储 / 应用目录 / 外置卡（SAF 授权在 M9 接入）" },
                        )
                        DropdownMenuItem(text = { Text("局域网扫描") }, onClick = { showTopMenu = false; onScanLan() })
                        DropdownMenuItem(text = { Text("设置") }, onClick = { showTopMenu = false; onOpenSettings() })
                    }
                }
            }

            // ---------------- 权限提示
            if (!storageGranted) {
                PermissionCard(
                    title = "需要「所有文件访问」权限",
                    detail = "Android 11+ 必须授予后才能完整浏览 /storage/emulated/0。",
                    actionLabel = "去授权",
                    onAction = {
                        runCatching {
                            requestStorage.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    },
                )
            }
            if (!localNetGranted) {
                PermissionCard(
                    title = "需要「局域网访问」权限（Android 17）",
                    detail = "未授权时访问 NAS / Alist / SMB 会直接超时。",
                    actionLabel = "去授权",
                    onAction = { runCatching { requestLocalNet.launch(LocalNetwork.PERMISSION) } },
                )
            }

            // ---------------- 本地
            SectionHeader("本地", expanded = expandLocal, onToggle = { expandLocal = !expandLocal })
            if (expandLocal) {
                volumes.forEach { volume ->
                    val space = spaces[volume.authority]
                    MtListRow(
                        title = volume.label,
                        subtitle = space?.let { "${Fmt.size(it.total - it.free)}已用，${Fmt.size(it.free)}可用" }
                            ?: volume.path,
                        icon = {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (volume.authority == "root") "📱" else if (volume.authority == "app") "🗂" else "💾",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                        onClick = { openVolume(volume.authority, volume.label, "/") },
                        extraBelow = space?.let {
                            {
                                UsageBar(used = it.total - it.free, total = it.total, modifier = Modifier.padding(top = 6.dp, end = 24.dp))
                            }
                        },
                    )
                }
            }

            // ---------------- 网络
            SectionHeader("网络", expanded = expandNet, onToggle = { expandNet = !expandNet })
            if (expandNet) {
                if (connections.isEmpty()) {
                    MtListRow(
                        title = "还没有网络存储",
                        subtitle = "点右下角 ＋ 添加 SFTP / FTP / FTPS / WebDAV",
                        icon = { NetworkBadge("＋") },
                        onClick = onAddConnection,
                    )
                }
                connections.forEach { config ->
                    MtListRow(
                        title = config.name.ifBlank { config.host },
                        subtitle = buildString {
                            append(config.type.label).append("  ")
                            append(if (config.type.scheme == "dav") "http://" else "")
                            append(config.host).append(":").append(config.port)
                            if (config.basePath.isNotBlank() && config.basePath != "/") append(config.basePath)
                        },
                        icon = { NetworkBadge(config.type.label.take(3).uppercase()) },
                        onClick = {
                            connecting = config.id
                            scope.launch {
                                try {
                                    container.openConnection(config)
                                    // WebDAV：进入虚拟根（basePath 是挂载点，由协议层拼回）；其余协议进入 basePath / 初始路径
                                    val uri = VfsUri.of(
                                        config.scheme,
                                        "${config.host}:${config.port}",
                                        config.openPath,
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
                        trailing = { if (connecting == config.id) Text("连接中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) },
                        onLongClick = { menuFor = config },
                    )
                }
            }

            // ---------------- 工具
            SectionHeader("工具", expanded = expandTools, onToggle = { expandTools = !expandTools })
            if (expandTools) {
                val active = tasks.count {
                    val s = it.state.value
                    s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
                }
                ToolRow("回收站", "🗑") { onOpenTrash() }
                ToolRow("远程管理", "🖥") { onOpenRemote() }
                ToolRow("已安装应用", "📦") { onOpenApps() }
                ToolRow("文本编辑器", "📄") { onOpenEditor() }
                ToolRow("局域网扫描", "🧭") { onScanLan() }
                ToolRow("书签", "🔖") { onOpenBookmarks() }
                ToolRow("传输任务" + if (active > 0) "（$active 进行中）" else "", "⬇") { onOpenTasks() }
                ToolRow("设置", "⚙") { onOpenSettings() }
                ToolRow("关于", "ℹ") { status = "PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME} · 双列文件管理器（本地 / SFTP · 跳板机 / FTP · FTPS / WebDAV / SMB / S3 / 压缩包），不含逆向功能" }
            }

            Box(Modifier.padding(bottom = 96.dp))
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
                        // 只断开这一条连接（旧实现 closeAll() 会把其它连接一起断掉）
                        container.disconnectConnection(config)
                        menuFor = null
                        status = "已断开会话"
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
                        container.disconnectConnection(config)
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
private fun NetworkBadge(text: String) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToolRow(title: String, emoji: String, onClick: () -> Unit) {
    MtListRow(
        title = title,
        subtitle = null,
        icon = {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center,
            ) { Text(emoji, style = MaterialTheme.typography.bodyMedium) }
        },
        onClick = onClick,
    )
}

@Composable
private fun PermissionCard(title: String, detail: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onAction() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
