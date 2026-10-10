package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtFolderGlyph
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
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
 *  - 头部：应用图标 + 名称 + 主题副标题 + 右上 ⋮（主题跟随系统 / 添加网络存储▶ / 设置）
 *  - 「本地」：根目录 / 内部存储 / 应用目录，每行带「xx已用，xx可用」+ 蓝色占用条
 *  - 「网络」：已添加的网络存储；点击在活动窗口打开，长按编辑/删除
 *  - 「后台」：**网络挂载**（MT 语义：只收网络路径，每个挂载一行）
 *  - 「工具」：回收站 / 已安装应用 / 文本编辑器 / 远程管理 / 书签 /
 *    传输任务 / 局域网扫描 / 更多工具
 * 各段标题右侧 ︿ 可折叠（v1.3.3 修复：网络 / 后台此前未按折叠态门控）。
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
    /** 网络工具箱（Ping / HTTP，v2.0.12） */
    onOpenNetToolbox: () -> Unit,
    /** 字符串工具箱（编码 / 摘要 / 文本 / 进制，v2.0.12） */
    onOpenTextToolbox: () -> Unit,
    onAddConnection: (ConnectionType) -> Unit,
    onOpenSettings: () -> Unit,
    showStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsState()
    val connections by container.connections.collectAsState()
    // 观察 taskEvents（任务状态变化也会刷新），仅用于统计「进行中」数量
    val tasks by container.engine.snapshots.collectAsState(initial = emptyList())

    var drawerMenu by remember { mutableStateOf(false) }
    var protocolSub by remember { mutableStateOf(false) }
    var expandMore by remember { mutableStateOf(false) }
    // 「关于」弹对话框（与首页一致；旧实现把简介塞进 snackbar）
    var showAbout by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ConnectionConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectionConfig?>(null) }
    // MT 0x7f1106fa「再按一次断开连接」：不可逆操作用「连按两次」而不是二次弹窗
    var disconnectArmed by remember { mutableStateOf<Long?>(null) }
    val appDark = LocalPanelDarkTheme.current
    // 三段折叠态（MT 截图：「本地 / 网络 / 工具」标题右侧都有 ︿，点标题折叠）
    var expandLocal by remember { mutableStateOf(true) }
    var expandNet by remember { mutableStateOf(true) }
    var expandTools by remember { mutableStateOf(true) }
    var expandRecent by remember { mutableStateOf(true) }

    // ===== 后台：网络挂载（MT 语义 —— 只收网络路径，本地 / 压缩包不属于这里）
    val browserUi by container.browser.state.collectAsState()
    var recentPaths by remember { mutableStateOf<List<com.u707t.panelfm.core.vfs.VfsUri>>(emptyList()) }
    LaunchedEffect(browserUi.left.uri, browserUi.right.uri) {
        recentPaths = withContext(kotlinx.coroutines.Dispatchers.IO) {
            drawerNetworkMounts(
                uris = runCatching { container.bookmarkDao.recentPaths(40) }.getOrDefault(emptyList()),
                exclude = setOf(browserUi.left.uri.toString(), browserUi.right.uri.toString()),
                exists = { uri -> container.locator.find(uri) != null },
            )
        }
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
        val drawerHeaderBg = if (appDark) MtSpec.TopBarDark else MtSpec.TopBarLight
        Row(
            Modifier
                .fillMaxWidth()
                .background(drawerHeaderBg)
                .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // MT 截图：头部图标是**圆角方形**（应用图标形状），不是圆形
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(MtSpec.CornerMedium))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                MtFolderGlyph(size = 30.dp, color = Color(0xFF3C3C3C))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    "PanelFM",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    color = MtSpec.TopBarText,
                )
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
                        .clip(RoundedCornerShape(MtSpec.CornerSmall))
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
                                            if (appDark) ThemeMode.DARK else ThemeMode.LIGHT
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
                        // 说明：原「添加本地存储 / 添加网络分组 / 管理工具分组」三个入口只弹一条说明，
                        // 属于「空承诺」死入口 —— 已移除（按 AUDIT-UX U8：要么实现、要么不显示）；
                        // 网络分组仍可在「编辑连接 → 网络分组」里填写，功能不受影响。
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
            // ===== 本地（MT：带已用/可用 + 占用条；标题可折叠）
            SectionHeader("本地", expanded = expandLocal, onToggle = { expandLocal = !expandLocal })
            if (expandLocal) volumes.forEach { volume ->
                val space = spaces[volume.authority]
                MtListRow(
                    title = volume.label,
                    subtitle = space?.let { com.u707t.panelfm.core.ui.usageText(it.total - it.free, it.free) } ?: volume.path,
                    icon = {
                        RoundIconBox(size = 30.dp) {
                            MtVectorIcon(
                                icon = when (volume.authority) {
                                    "root" -> MtIcon.ANDROID
                                    "app" -> MtIcon.LAYERS
                                    else -> MtIcon.SD
                                },
                                size = 14.dp,
                                tint = MaterialTheme.colorScheme.surface,
                            )
                        }
                    },
                    onClick = { onOpenVolume(volume) },
                    extraBelow = space?.let {
                        { UsageBar(used = it.total - it.free, total = it.total, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) }
                    },
                    titleSize = 14.sp,
                )
            }

            // ===== 网络（「在侧拉栏隐藏地址」的连接不在这里显示；按分组归拢）
            SectionHeader("网络", expanded = expandNet, onToggle = { expandNet = !expandNet })
            // 修复：连接行此前未按折叠态门控 —— 点标题箭头会翻转，但内容照旧渲染（「收不起来」）
            if (expandNet) {
                val drawerConnections = connections.filter { it.option(ConnectionConfig.OPT_HIDDEN_IN_DRAWER) != "true" }
                if (drawerConnections.isEmpty()) {
                    MtListRow(
                        title = "还没有网络存储",
                        titleSize = 14.sp,
                        subtitle = "右上角 ⋮ → 添加网络存储（SFTP / FTP / WebDAV / SMB / S3）",
                        icon = {
                            RoundIconBox(size = 30.dp) {
                                MtVectorIcon(icon = MtIcon.PLUS, size = 14.dp, tint = MaterialTheme.colorScheme.surface)
                            }
                        },
                        onClick = { onAddConnection(ConnectionType.SFTP) },
                    )
                } else {
                    // 分组名非空的连接按组名分节显示（MT：分组显示网络存储）；空组直接平铺
                    val grouped = drawerConnections.filter { it.group.isNotBlank() }.groupBy { it.group }
                    val ungrouped = drawerConnections.filter { it.group.isBlank() }
                    ungrouped.forEach { config -> DrawerConnectionRow(config, connectingId, onOpenConnection, menuFor = { menuFor = it }) }
                    grouped.forEach { (groupName, list) ->
                        SectionHeader(groupName)
                        list.forEach { config -> DrawerConnectionRow(config, connectingId, onOpenConnection, menuFor = { menuFor = it }) }
                    }
                }
            }

            // ===== 后台（网络挂载；点击在活动窗口打开）
            if (recentPaths.isNotEmpty()) {
                SectionHeader("后台", expanded = expandRecent, onToggle = { expandRecent = !expandRecent })
                // 修复：此前未按折叠态门控 —— 点箭头会翻转，但行照旧渲染（「收不起来」）
                if (expandRecent) {
                    recentPaths.forEach { uri ->
                        val cfg = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                            ?: container.connectionByAuthority(uri.scheme, uri.authority)
                        MtListRow(
                            title = cfg?.name?.ifBlank { null } ?: when (uri.scheme) {
                                "local" -> "本地存储"
                                else -> uri.scheme.uppercase()
                            },
                            titleSize = 14.sp,
                            subtitle = uri.displayPath.ifEmpty { "/" },
                            icon = {
                                RoundIconBox(size = 30.dp) {
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
                                        size = 14.dp,
                                        tint = MaterialTheme.colorScheme.surface,
                                    )
                                }
                            },
                            onClick = { onOpenRecentPath(uri) },
                        )
                    }
                }
            }

            // ===== 工具（MT 截图：回收站 / 插件管理 / 远程管理 / 已安装应用 / 文本编辑器 /
            //        终端模拟器 / … / 更多工具；标题右侧 ︿ 可折叠，默认展开）
            SectionHeader("工具", expanded = expandTools, onToggle = { expandTools = !expandTools })
            if (expandTools) {
                val active = tasks.count { it.state.isActive }
                DrawerTool("回收站", MtIcon.DELETE, onOpenTrash)
                DrawerTool("已安装应用", MtIcon.EXTENSION, onOpenApps)
                DrawerTool("文本编辑器", MtIcon.CODE, onOpenEditor)
                DrawerTool("远程管理", MtIcon.DNS, onOpenRemote)
                DrawerTool("书签", MtIcon.BOOKMARK, onOpenBookmarks)
                // v2.0.12：进行中的任务把总体进度内嵌在条目里（借鉴 NP「抽屉条目进度条」）
                val activeProgress = tasks.filter { it.state.isActive }
                    .map { it.overallProgress() }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
                DrawerTool(
                    "传输任务" + if (active > 0) "（$active 进行中）" else "",
                    MtIcon.GET_APP,
                    onOpenTasks,
                    progress = activeProgress,
                )
                DrawerTool("局域网扫描", MtIcon.EXPLORE, onOpenLanScan)
                DrawerTool("网络工具箱", MtIcon.WEB, onOpenNetToolbox)
                DrawerTool("字符串工具箱", MtIcon.TEXT_SIZE, onOpenTextToolbox)
                DrawerTool("更多工具", if (expandMore) MtIcon.UNFOLD_UP else MtIcon.UNFOLD_DOWN, onClick = { expandMore = !expandMore })
                if (expandMore) {
                    DrawerTool("设置", MtIcon.SETTINGS, onOpenSettings)
                    DrawerTool("关于 PanelFM", MtIcon.INFO, onClick = { showAbout = true })
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

    if (showAbout) {
        MessageDialog(
            title = "关于 PanelFM",
            message = "PanelFM ${com.u707t.panelfm.BuildConfig.VERSION_NAME}\n\n" +
                "双列文件管理器：本地 / SFTP（跳板机）/ FTP · FTPS / WebDAV / SMB / S3 / 压缩包。\n\n" +
                "本项目不含任何逆向工程功能（不做 DEX / Arsc / APK 编辑）。",
            onDismiss = { showAbout = false },
        )
    }
}

@Composable
private fun DrawerTool(title: String, icon: MtIcon, onClick: () -> Unit, progress: Float? = null) {
    MtListRow(
        title = title,
        titleSize = 14.sp,
        subtitle = null,
        icon = {
            RoundIconBox(size = 30.dp) {
                MtVectorIcon(icon = icon, size = 14.dp, tint = MaterialTheme.colorScheme.surface)
            }
        },
        onClick = onClick,
        // 后台任务进度条（v2.0.12）：抽拉抽屉即见；不做常驻、不占行高（仅在有条目时多出 3dp）
        extraBelow = if (progress != null) {
            {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        } else null,
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
        titleSize = 14.sp,
        subtitle = buildString {
            append(config.type.label).append("  ")
            if (config.type.scheme == "dav") append("http://")
            append(config.host).append(":").append(config.port)
            if (config.basePath.isNotBlank() && config.basePath != "/") append(config.basePath)
        },
        icon = {
            RoundIconBox(size = 30.dp) {
                Text(
                    config.type.label.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
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
