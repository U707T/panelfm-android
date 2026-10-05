package com.u707t.panelfm.ui.home

import androidx.core.net.toUri
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
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import androidx.compose.ui.graphics.Color
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
    val browserState by container.browser.state.collectAsState()
    // 观察 taskEvents（任务状态变化也会刷新），仅用于统计「进行中」数量
    val tasks by container.engine.snapshots.collectAsState(initial = emptyList())

    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<ConnectionConfig?>(null) }
    // MT 0x7f1106fa「再按一次断开连接」的 2 秒窗口
    var disconnectArmed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(disconnectArmed) {
        if (disconnectArmed != null) {
            kotlinx.coroutines.delay(com.u707t.panelfm.core.ui.MtGesture.PressAgainMs)
            disconnectArmed = null
        }
    }
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
            browserState.focused,
            VfsUri.of("local", authority, path),
            connectionId = null,
            label = label,
        )
        onOpenBrowser()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddConnection,
                containerColor = MtSpec.FabRed,
                contentColor = Color.White,
            ) {
                MtVectorIcon(icon = MtIcon.PLUS, size = MtSpec.FabIcon, tint = Color.White)
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
                    MtIconButton(icon = MtIcon.MORE, contentDescription = "更多菜单") { showTopMenu = true }
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                requestStorage.launch(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .setData("package:${context.packageName}".toUri())
                                )
                            }
                        } else {
                            storageGranted = true
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
                        subtitle = space?.let { com.u707t.panelfm.core.ui.usageText(it.total - it.free, it.free) }
                            ?: volume.path,
                        icon = {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                MtVectorIcon(
                                    icon = when (volume.authority) {
                                        "root" -> MtIcon.ANDROID
                                        "app" -> MtIcon.LAYERS
                                        else -> MtIcon.SD
                                    },
                                    size = 24.dp,
                                    tint = MaterialTheme.colorScheme.surface,
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

            // ---------------- 网络（按分组归拢；分组名在「编辑连接」里填写）
            SectionHeader("网络", expanded = expandNet, onToggle = { expandNet = !expandNet })
            if (expandNet) {
                if (connections.isEmpty()) {
                    MtListRow(
                        title = "还没有网络存储",
                        subtitle = "点右下角 ＋ 添加 SFTP / FTP / FTPS / WebDAV",
                        icon = { NetworkBadge(MtIcon.PLUS) },
                        onClick = onAddConnection,
                    )
                }

                /** 打开连接：进入虚拟根 / 初始路径，然后切到双列页（分组与平铺共用） */
                fun openNetworkConnection(config: ConnectionConfig) {
                    connecting = config.id
                    scope.launch {
                        try {
                            container.openConnection(config)
                            // WebDAV：进入虚拟根（basePath 是挂载点，由协议层拼回）；其余协议进入 basePath / 初始路径。
                            // 连接号统一由 AppContainer 注入（避免各处手工拼串漏掉 c=）。
                            val uri = container.uriForConnection(config)
                            container.browser.open(browserState.focused, uri, config.id, config.name)
                            connecting = null
                            onOpenBrowser()
                        } catch (e: Exception) {
                            connecting = null
                            status = (e as? VfsException)?.userMessage ?: (e.message ?: "连接失败")
                        }
                    }
                }

                val grouped = connections.filter { it.group.isNotBlank() }.groupBy { it.group }
                val ungrouped = connections.filter { it.group.isBlank() }
                ungrouped.forEach { config ->
                    HomeConnectionRow(
                        config = config,
                        connecting = connecting,
                        onOpen = { openNetworkConnection(config) },
                        onLongClick = { menuFor = config },
                    )
                }
                grouped.forEach { (groupName, list) ->
                    SectionHeader(groupName)
                    list.forEach { config ->
                        HomeConnectionRow(
                            config = config,
                            connecting = connecting,
                            onOpen = { openNetworkConnection(config) },
                            onLongClick = { menuFor = config },
                        )
                    }
                }
            }

            // ---------------- 工具
            SectionHeader("工具", expanded = expandTools, onToggle = { expandTools = !expandTools })
            if (expandTools) {
                val active = tasks.count {
                    val s = it.state
                    s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
                }
                ToolRow("回收站", MtIcon.DELETE) { onOpenTrash() }
                ToolRow("远程管理", MtIcon.DNS) { onOpenRemote() }
                ToolRow("已安装应用", MtIcon.EXTENSION) { onOpenApps() }
                ToolRow("文本编辑器", MtIcon.CODE) { onOpenEditor() }
                ToolRow("局域网扫描", MtIcon.EXPLORE) { onScanLan() }
                ToolRow("书签", MtIcon.BOOKMARK) { onOpenBookmarks() }
                ToolRow("传输任务" + if (active > 0) "（$active 进行中）" else "", MtIcon.GET_APP) { onOpenTasks() }
                ToolRow("设置", MtIcon.SETTINGS) { onOpenSettings() }
                ToolRow("关于", MtIcon.INFO) { status = "PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME} · 双列文件管理器（本地 / SFTP · 跳板机 / FTP · FTPS / WebDAV / SMB / S3 / 压缩包），不含逆向功能" }
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
                        // MT 0x7f1106fa：第一次点提示，2 秒内再点一次才真正断开
                        if (disconnectArmed == config.id) {
                            container.disconnectConnection(config)
                            disconnectArmed = null
                            menuFor = null
                            status = "已断开会话"
                        } else {
                            disconnectArmed = config.id
                            status = "再按一次断开连接"
                        }
                    }) { Text(if (disconnectArmed == config.id) "再按一次断开连接" else "断开") }
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
private fun NetworkBadge(icon: MtIcon) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(icon = icon, size = 24.dp, tint = MaterialTheme.colorScheme.surface)
    }
}

/** 主页「网络」里的单个连接行（分组与平铺共用） */
@Composable
private fun HomeConnectionRow(
    config: ConnectionConfig,
    connecting: Long?,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
) {
    MtListRow(
        title = config.name.ifBlank { config.host },
        subtitle = buildString {
            append(config.type.label).append("  ")
            append(if (config.type.scheme == "dav") "http://" else "")
            append(config.host).append(":").append(config.port)
            if (config.basePath.isNotBlank() && config.basePath != "/") append(config.basePath)
        },
        icon = {
            NetworkBadge(
                when (config.type.scheme) {
                    "dav" -> MtIcon.CLOUD
                    "ftp", "ftps" -> MtIcon.DNS
                    "sftp" -> MtIcon.LOCK
                    "smb" -> MtIcon.WEB
                    "s3" -> MtIcon.CLOUD
                    else -> MtIcon.DNS
                }
            )
        },
        onClick = onOpen,
        onLongClick = onLongClick,
        trailing = {
            if (connecting == config.id) {
                Text("连接中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun ToolRow(title: String, icon: MtIcon, onClick: () -> Unit) {
    MtListRow(
        title = title,
        subtitle = null,
        icon = { NetworkBadge(icon) },
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
