package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtFolderGlyph
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.MtListRow
import com.u707t.panelfm.core.ui.RoundIconBox
import com.u707t.panelfm.core.ui.SectionHeader
import com.u707t.panelfm.core.ui.UsageBar
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.local.LocalVolume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MT 管理器侧边栏（截图复刻）：
 *  - 头部：应用图标 + 名称 + 主题副标题 + 右上 ⋮（主题跟随系统 / 添加网络存储▶ /
 *    添加本地存储 / 添加网络分组 / 管理工具分组 / 设置）
 *  - 「本地」：根目录 / 内部存储 / 应用目录，每行带「xx已用，xx可用」+ 蓝色占用条
 *  - 「网络」：已添加的网络存储；点击在活动窗口打开，长按编辑/删除
 *  - 「工具」：回收站 / 已安装应用 / 文本编辑器 / 远程管理 / 书签 /
 *    传输任务 / 局域网扫描 / 更多工具
 * 点击本地 / 网络节点 → 在**活动窗口**打开（MT 语义）。
 */
@Composable
fun MtSideDrawer(
    container: AppContainer,
    volumes: List<LocalVolume>,
    spaces: Map<String, SpaceInfo>,
    connectingId: Long?,
    onOpenVolume: (LocalVolume) -> Unit,
    onOpenConnection: (ConnectionConfig) -> Unit,
    /** 「后台」段：最近访问路径（点击在活动窗口打开） */
    onOpenRecentPath: (com.u707t.panelfm.core.vfs.VfsUri) -> Unit,
    onEditConnection: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenLanScan: () -> Unit,
    onAddConnection: (ConnectionType) -> Unit,
    onOpenSettings: () -> Unit,
    showStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsState()
    val connections by container.connections.collectAsState()
    // 观察 taskEvents（任务状态变化也会刷新），仅用于统计「进行中」数量
    val tasks by container.engine.taskEvents.collectAsState(initial = emptyList())

    var drawerMenu by remember { mutableStateOf(false) }
    var protocolSub by remember { mutableStateOf(false) }
    var expandMore by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ConnectionConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectionConfig?>(null) }
    // MT 0x7f1106fa「再按一次断开连接」：不可逆操作用「连按两次」而不是二次弹窗
    var disconnectArmed by remember { mutableStateOf<Long?>(null) }
    val systemDark = isSystemInDarkTheme()

    // ===== 后台：最近访问路径（MT 抽屉的「后台」段）
    val browserUi by container.browser.state.collectAsState()
    var recentPaths by remember { mutableStateOf<List<com.u707t.panelfm.core.vfs.VfsUri>>(emptyList()) }
    LaunchedEffect(browserUi.left.uri, browserUi.right.uri) {
        recentPaths = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.bookmarkDao.recentPaths(40) }.getOrDefault(emptyList())
        }.filter { uri ->
            uri.toString() != browserUi.left.uri.toString() &&
                uri.toString() != browserUi.right.uri.toString() &&
                container.locator.find(uri) != null
        }.distinctBy { it.toString() }.take(6)
    }

    // 「再按一次断开连接」的 2 秒窗口（MT 的 PressAgainMs）
    LaunchedEffect(disconnectArmed) {
        if (disconnectArmed != null) {
            kotlinx.coroutines.delay(MtGesture.PressAgainMs)
            disconnectArmed = null
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // ---------------- 头部（复刻 MT：深色底 #151515 + 图标 + 名称 + 副标题 + 右上 ⋮）
        val drawerHeaderBg = if (systemDark) MtSpec.TopBarDark else MtSpec.TopBarLight
        Row(
            Modifier
                .fillMaxWidth()
                .background(drawerHeaderBg)
                .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                MtFolderGlyph(size = 28.dp, color = Color.White)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text("PanelFM", style = MaterialTheme.typography.titleLarge, color = MtSpec.TopBarText)
                Text(
                    when (settings.themeMode) {
                        ThemeMode.SYSTEM -> "主题跟随系统"
                        ThemeMode.LIGHT -> "浅色主题"
                        ThemeMode.DARK -> "深色主题"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MtSpec.TopBarSubText,
                )
            }
            // ---------------- 侧边栏右上 ⋮（MT：主题跟随系统 / 添加存储 / 分组 / 设置）
            Box {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoRipple { drawerMenu = true; protocolSub = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "侧边栏菜单"
                        },
                ) {
                    MtVectorIcon(icon = MtIcon.MORE, size = 22.dp, tint = MtSpec.TopBarText)
                }
                DropdownMenu(expanded = drawerMenu, onDismissRequest = { drawerMenu = false }) {
                    if (!protocolSub) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("👕", Modifier.padding(end = 12.dp))
                                    Text("主题跟随系统", Modifier.weight(1f))
                                    Checkbox(checked = settings.themeMode == ThemeMode.SYSTEM, onCheckedChange = null)
                                }
                            },
                            onClick = {
                                drawerMenu = false
                                scope.launch {
                                    // 勾选 = SYSTEM；取消勾选 = 固定为当前系统生效的亮/暗
                                    container.prefs.setTheme(
                                        if (settings.themeMode == ThemeMode.SYSTEM) {
                                            if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT
                                        } else {
                                            ThemeMode.SYSTEM
                                        }
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.PLUS, size = 22.dp)
                                    Text("添加网络存储", Modifier.weight(1f).padding(start = 14.dp))
                                    MtVectorIcon(icon = MtIcon.CHEVRON_R, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { protocolSub = true },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.PLUS, size = 22.dp)
                                    Text("添加本地存储", Modifier.padding(start = 14.dp))
                                }
                            },
                            onClick = {
                                drawerMenu = false
                                showStatus("已自动枚举：根目录 / 内部存储 / 应用目录；外置 SD 卡（SAF）将在后续版本接入")
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.FOLDER, size = 22.dp)
                                    Text("添加网络分组", Modifier.padding(start = 14.dp))
                                }
                            },
                            onClick = {
                                drawerMenu = false
                                showStatus("网络分组：在「编辑连接 → 网络分组」中填写组名即可；同名分组会自动归拢")
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.BUILD, size = 22.dp)
                                    Text("管理工具分组", Modifier.padding(start = 14.dp))
                                }
                            },
                            onClick = {
                                drawerMenu = false
                                showStatus("工具分组为默认布局，自定义分组将在后续版本提供")
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.SETTINGS, size = 22.dp)
                                    Text("设置", Modifier.padding(start = 14.dp))
                                }
                            },
                            onClick = { drawerMenu = false; onOpenSettings() },
                        )
                    } else {
                        // 添加网络存储 ▶ 子菜单（协议列表）
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MtVectorIcon(icon = MtIcon.CHEVRON_L, size = 22.dp)
                                    Text("选择协议", Modifier.padding(start = 14.dp))
                                }
                            },
                            onClick = { protocolSub = false },
                        )
                        HSeparator()
                        listOf(
                            ConnectionType.SFTP,
                            ConnectionType.FTP,
                            ConnectionType.FTPS,
                            ConnectionType.WEBDAV,
                            ConnectionType.SMB,
                            ConnectionType.S3,
                        ).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, Modifier.padding(start = 28.dp)) },
                                onClick = {
                                    drawerMenu = false
                                    onAddConnection(type)
                                },
                            )
                        }
                    }
                }
            }
        }
        HSeparator()

        // ---------------- 分组列表（可滚动）
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ===== 本地（MT：带已用/可用 + 占用条）
            SectionHeader("本地")
            volumes.forEach { volume ->
                val space = spaces[volume.authority]
                MtListRow(
                    title = volume.label,
                    subtitle = space?.let { "${Fmt.size(it.total - it.free)}已用，${Fmt.size(it.free)}可用" } ?: volume.path,
                    icon = {
                        RoundIconBox(size = 42.dp) {
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
                    onClick = { onOpenVolume(volume) },
                    extraBelow = space?.let {
                        { UsageBar(used = it.total - it.free, total = it.total, modifier = Modifier.padding(top = 6.dp, end = 8.dp)) }
                    },
                )
            }

            // ===== 网络（「在侧拉栏隐藏地址」的连接不在这里显示；按分组归拢）
            SectionHeader("网络")
            val drawerConnections = connections.filter { it.option(ConnectionConfig.OPT_HIDDEN_IN_DRAWER) != "true" }
            if (drawerConnections.isEmpty()) {
                MtListRow(
                    title = "还没有网络存储",
                    subtitle = "右上角 ⋮ → 添加网络存储（SFTP / FTP / WebDAV / SMB / S3）",
                    icon = {
                        RoundIconBox(size = 42.dp) {
                            MtVectorIcon(icon = MtIcon.PLUS, size = 24.dp, tint = MaterialTheme.colorScheme.surface)
                        }
                    },
                    onClick = { onAddConnection(ConnectionType.SFTP) },
                )
            }
            // 分组名非空的连接按组名分节显示（MT：分组显示网络存储）；空组直接平铺
            val grouped = drawerConnections.filter { it.group.isNotBlank() }.groupBy { it.group }
            val ungrouped = drawerConnections.filter { it.group.isBlank() }
            ungrouped.forEach { config -> DrawerConnectionRow(config, connectingId, onOpenConnection, menuFor = { menuFor = it }) }
            grouped.forEach { (groupName, list) ->
                SectionHeader(groupName)
                list.forEach { config -> DrawerConnectionRow(config, connectingId, onOpenConnection, menuFor = { menuFor = it }) }
            }

            // ===== 后台（最近访问；点击在活动窗口打开）
            if (recentPaths.isNotEmpty()) {
                SectionHeader("后台")
                recentPaths.forEach { uri ->
                    val cfg = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                        ?: container.connectionByAuthority(uri.scheme, uri.authority)
                    MtListRow(
                        title = cfg?.name?.ifBlank { null } ?: when (uri.scheme) {
                            "local" -> "本地存储"
                            else -> uri.scheme.uppercase()
                        },
                        subtitle = uri.displayPath.ifEmpty { "/" },
                        icon = {
                            RoundIconBox(size = 42.dp) {
                                MtVectorIcon(
                                    icon = when (uri.scheme) {
                                        "local" -> MtIcon.SD
                                        "dav" -> MtIcon.CLOUD
                                        "ftp", "ftps" -> MtIcon.DNS
                                        "sftp" -> MtIcon.LOCK
                                        "smb" -> MtIcon.WEB
                                        "s3" -> MtIcon.CLOUD
                                        else -> MtIcon.CLOUD
                                    },
                                    size = 24.dp,
                                    tint = MaterialTheme.colorScheme.surface,
                                )
                            }
                        },
                        onClick = { onOpenRecentPath(uri) },
                    )
                }
            }

            // ===== 工具（MT：回收站 / 已安装应用 / 文本编辑器 / … / 更多工具）
            SectionHeader("工具")
            val active = tasks.count {
                val s = it.state.value
                s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
            }
            DrawerTool("回收站", MtIcon.DELETE, onOpenTrash)
            DrawerTool("已安装应用", MtIcon.EXTENSION, onOpenApps)
            DrawerTool("文本编辑器", MtIcon.CODE, onOpenEditor)
            DrawerTool("远程管理", MtIcon.DNS, onOpenRemote)
            DrawerTool("书签", MtIcon.BOOKMARK, onOpenBookmarks)
            DrawerTool("传输任务" + if (active > 0) "（$active 进行中）" else "", MtIcon.GET_APP, onOpenTasks)
            DrawerTool("局域网扫描", MtIcon.EXPLORE, onOpenLanScan)
            DrawerTool("更多工具", MtIcon.EXPAND) { expandMore = !expandMore }
            if (expandMore) {
                DrawerTool("设置", MtIcon.SETTINGS, onOpenSettings)
                DrawerTool("关于 PanelFM", MtIcon.INFO) {
                    showStatus("PanelFM · 双列文件管理器（本地 / SFTP / FTP / FTPS / WebDAV / SMB / S3 / 压缩包），不含逆向功能")
                }
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }

    // ---------------- 网络存储长按菜单（MT：编辑 / 断开 / 删除）
    menuFor?.let { config ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(config.name.ifBlank { config.host }) },
            text = { Text("${config.type.label} · ${config.host}:${config.port}") },
            confirmButton = {
                TextButton(onClick = { menuFor = null; onEditConnection(config.id) }) { Text("编辑") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // MT 0x7f1106fa：第一次点提示，2 秒内再点一次才真正断开
                        if (disconnectArmed == config.id) {
                            container.disconnectConnection(config)
                            disconnectArmed = null
                            menuFor = null
                            showStatus("已断开会话：${config.name.ifBlank { config.host }}")
                        } else {
                            disconnectArmed = config.id
                            showStatus("再按一次断开连接")
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
private fun DrawerTool(title: String, icon: MtIcon, onClick: () -> Unit) {
    MtListRow(
        title = title,
        subtitle = null,
        icon = {
            RoundIconBox(size = 42.dp) {
                MtVectorIcon(icon = icon, size = 24.dp, tint = MaterialTheme.colorScheme.surface)
            }
        },
        onClick = onClick,
    )
}

/** 侧边栏「网络」里的单个连接行（平铺与分组共用） */
@Composable
private fun DrawerConnectionRow(
    config: ConnectionConfig,
    connectingId: Long?,
    onOpen: (ConnectionConfig) -> Unit,
    menuFor: (ConnectionConfig) -> Unit,
) {
    MtListRow(
        title = config.name.ifBlank { config.host },
        subtitle = buildString {
            append(config.type.label).append("  ")
            if (config.type.scheme == "dav") append("http://")
            append(config.host).append(":").append(config.port)
            if (config.basePath.isNotBlank() && config.basePath != "/") append(config.basePath)
        },
        icon = {
            RoundIconBox(size = 42.dp) {
                Text(
                    config.type.label.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        onClick = { onOpen(config) },
        onLongClick = { menuFor(config) },
        trailing = {
            if (connectingId == config.id) {
                Text("连接中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
