package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlinx.coroutines.launch

/**
 * 双列主界面（对齐 MT 管理器 · 官方手册 + 截图复刻）：
 *  - **打开即是双列**；顶部 ≡（侧边栏抽屉）+ 面包屑路径 + 统计 + ⋮
 *  - 侧边栏：本地（占用条）/ 网络 / 工具，点击在活动窗口打开；右上 ⋮ = 主题跟随系统 / 添加存储 / 分组 / 设置
 *  - 列表首行 `..`，行高固定；**左右滑动任意项 = 进入多选**（继续滑过行间 = 连续区间选择）
 *  - 长按松手 = 动作菜单（跨窗格复制/移动走动作菜单「复制 -> / 移动 ->」或 ⇄；长按拖动已移除）
 *  - 底部 `← → ＋ ⇄ ↑`：＋弹新建菜单；**⇄ 点击 = 交换窗口**（长按 = 过滤）；长按 ↑ = 路径跳转；底栏上滑 = 书签
 *  - 长按文件 → MT 动作菜单（`复制 ->` / `移动 ->`，**箭头指向另一窗口**；带 ● 支持长按单窗口操作）
 */
@Composable
fun DualPaneScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenLanScan: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenRemote: () -> Unit,
    onAddConnection: (ConnectionType?) -> Unit,
    onEditConnection: (Long) -> Unit,
    onOpenPreview: (VfsUri) -> Unit,
    onOpenEditor: (VfsUri) -> Unit,
    onOpenDiff: (VfsUri, VfsUri) -> Unit,
) {
    val ui by container.browser.state.collectAsState()
    val controller = container.browser
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var rowAction by remember { mutableStateOf<FileMetadata?>(null) }
    var toolsFor by remember { mutableStateOf<FileMetadata?>(null) }
    var renaming by remember { mutableStateOf<FileMetadata?>(null) }
    var deleting by remember { mutableStateOf<FileMetadata?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var hiddenSub by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var sortManage by remember { mutableStateOf(false) }
    var gotoPath by remember { mutableStateOf(false) }
    var filterInput by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FileMetadata>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var singleWindowOp by remember { mutableStateOf<TransferOp?>(null) }
    var permissionFor by remember { mutableStateOf<FileMetadata?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openWithFor by remember { mutableStateOf<FileMetadata?>(null) }
    var openWithManage by remember { mutableStateOf(false) }
    var batchRenameFor by remember { mutableStateOf<List<FileMetadata>?>(null) }
    var compressFormatPicker by remember { mutableStateOf(false) }
    var extractDirPicker by remember { mutableStateOf(false) }
    var archiveRename by remember { mutableStateOf<FileMetadata?>(null) }
    var showTypeFilter by remember { mutableStateOf(false) }
    /** 双列区域的总宽度（分隔条拖动换算用；旧实现用分隔条自身宽度 10dp → 拖不动） */
    var panesWidthPx by remember { mutableStateOf(0f) }

    // ---------------- 侧边栏（MT：≡ 打开抽屉）
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val volumes = remember { LocalVolumes.volumes(context) }
    var spaces by remember { mutableStateOf<Map<String, SpaceInfo>>(emptyMap()) }
    var connecting by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { container.reloadConnections() }
    LaunchedEffect(volumes) {
        val map = mutableMapOf<String, SpaceInfo>()
        volumes.forEach { volume ->
            runCatching { container.localVfs.space(LocalVolumes.uri(volume, "/")) }
                .getOrNull()?.let { map[volume.authority] = it }
        }
        spaces = map
    }

    val focused = ui.focusedPane
    val focusSide = ui.focused

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    /** MT：点击侧边栏本地 / 网络节点 → 在**活动窗口**打开 */
    fun openVolumeInActivePane(volume: LocalVolume) {
        controller.open(focusSide, VfsUri.of("local", volume.authority, "/"), null, volume.label)
        closeDrawer()
    }

    fun openConnectionInActivePane(config: ConnectionConfig) {
        if (connecting != null) return
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
                controller.open(controller.state.value.focused, uri, config.id, config.name)
                connecting = null
                drawerState.close()
            } catch (e: Exception) {
                connecting = null
                controller.showStatus((e as? VfsException)?.userMessage ?: (e.message ?: "连接失败"))
            }
        }
    }

    // 返回手势：加载中 → 取消加载；多选 → 取消选择；否则返回上一级；已在根目录则交给外层（主页/退出）
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> closeDrawer()
            focused.loading -> controller.cancelLoad(focusSide)
            focused.hasSelection -> controller.clearSelection(focusSide)
            focused.uri.parent != null || focused.uri.scheme == "archive" -> controller.up(focusSide)
            else -> onOpenHome()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.86f)) {
                // 抽屉内容顶部加安全区（状态栏），否则「PanelFM」标题会顶到状态栏下面
                Column(Modifier.safeAreaPadding()) {
                    MtSideDrawer(
                        container = container,
                    volumes = volumes,
                    spaces = spaces,
                    connectingId = connecting,
                    onOpenVolume = { openVolumeInActivePane(it) },
                    onOpenConnection = { openConnectionInActivePane(it) },
                    onOpenRecentPath = { uri ->
                        closeDrawer()
                        val conn = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                        controller.open(focusSide, uri, conn?.id, conn?.name)
                    },
                    onEditConnection = { id -> closeDrawer(); onEditConnection(id) },
                    onOpenTrash = { closeDrawer(); onOpenTrash() },
                    onOpenApps = { closeDrawer(); onOpenApps() },
                    onOpenEditor = {
                        closeDrawer()
                        val item = focused.selectedItems.firstOrNull { !it.isDirectory }
                        if (item != null) onOpenEditor(item.uri)
                        else controller.showStatus("在列表中点击文本文件即可用内置编辑器打开（或先选中一个文件）")
                    },
                    onOpenRemote = { closeDrawer(); onOpenRemote() },
                    onOpenBookmarks = { closeDrawer(); onOpenBookmarks() },
                    onOpenTasks = { closeDrawer(); onOpenTasks() },
                    onOpenLanScan = { closeDrawer(); onOpenLanScan() },
                    onAddConnection = { type -> closeDrawer(); onAddConnection(type) },
                    onOpenSettings = { closeDrawer(); onOpenSettings() },
                        showStatus = { controller.showStatus(it) },
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().safeAreaPadding()) {
            // ---------------- 顶部栏（复刻 MT 0x7f0c0033 的 09046B）
            //   MT 的顶栏**始终是深色**（浅色主题 #151515 / 深色 #303030），
            //   左 ☰、右 ⋮，中间两行**居中**：路径（18sp）+ 统计（13sp）。
            val topBarBg = if (isSystemInDarkTheme()) MtSpec.TopBarDark else MtSpec.TopBarLight
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(topBarBg)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ☰ 侧边栏
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple { scope.launch { drawerState.open() } }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "打开侧边栏"
                            },
                    ) {
                        MtVectorIcon(icon = MtIcon.MENU, size = 24.dp, tint = MtSpec.TopBarText)
                    }
                    // 中间两行居中（MT：路径 + 统计）
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            middleEllipsis(focused.uri.displayPath.ifEmpty { "/" }, maxChars = 30),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MtSpec.TopBarTitleSize,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MtSpec.TopBarText,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        Text(
                            buildString {
                                append("文件夹: ").append(focused.dirCount)
                                append("  文件: ").append(focused.fileCount)
                                focused.space?.let {
                                    append("  储存: ").append(Fmt.size(it.total - it.free)).append("/").append(Fmt.size(it.total))
                                }
                                if (focused.hasSelection) append("  已选: ").append(focused.selection.size)
                                if (focused.filtered) append("  ·  已过滤")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.TopBarSubSize),
                            color = MtSpec.TopBarSubText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // ⋮ 更多菜单
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple { showMoreMenu = true; hiddenSub = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "更多菜单"
                            },
                    ) {
                        MtVectorIcon(icon = MtIcon.MORE, size = 24.dp, tint = MtSpec.TopBarText)
                    }
                }
                // 面包屑（点任意一级跳转；长按复制完整路径）—— 深底上用小号亮字
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val crumbs: List<Pair<String, VfsUri>> = remember(focused.uri) {
                        buildList {
                            if (focused.uri.scheme == "archive") {
                                val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
                                val host = encoded?.let { runCatching { VfsUri.parse(VfsUri.decodeHost(it)) }.getOrNull() }
                                val kind = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.entries
                                    .firstOrNull { it.id == focused.uri.authority }
                                if (host != null && kind != null) {
                                    add((host.name.ifEmpty { "压缩包" }) + "!/" to
                                        com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(host, kind, ""))
                                    val innerSegs = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(focused.uri.path)
                                        .split('/').filter { it.isNotEmpty() }
                                    innerSegs.forEachIndexed { i, seg ->
                                        add("$seg/" to com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(
                                            host, kind, innerSegs.take(i + 1).joinToString("/")))
                                    }
                                }
                            } else {
                                val full = focused.uri.displayPath.ifEmpty { "/" }
                                val segments = full.trim('/').split('/').filter { it.isNotEmpty() }
                                if (segments.isEmpty()) {
                                    add("/" to focused.uri.withPath("/"))
                                } else {
                                    segments.forEachIndexed { i, seg ->
                                        add("$seg/" to focused.uri.withPath("/" + segments.take(i + 1).joinToString("/")))
                                    }
                                }
                            }
                        }
                    }
                    val shown = if (crumbs.size > 4) crumbs.takeLast(4) else crumbs
                    if (crumbs.size > shown.size) {
                        Text(
                            "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MtSpec.TopBarSubText,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                    shown.forEachIndexed { index, (label, target) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == shown.lastIndex) MtSpec.TopBarText else MtSpec.TopBarSubText,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 1.dp)
                                .combinedClickable(
                                    onClick = {
                                        controller.open(
                                            focusSide,
                                            target,
                                            focused.tab.connectionId,
                                            focused.tab.label,
                                        )
                                    },
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(focused.uri.toString()))
                                        controller.showStatus("已复制路径：${focused.uri.displayPath}")
                                    },
                                ),
                        )
                    }
                }
            }

            HSeparator()

            // ---------------- MT 顶栏动作条（复刻 0x7f0c0034）：
            //   选中项后出现「复制 / 移动 / 删除」三连（横向可滚动），未选中时整行隐藏。
            //   源在左窗格 → `复制 ->`；源在右窗格 → `<- 复制`（箭头始终指向目标窗口）。
            if (focused.hasSelection) {
                val picked = focused.selectedItems
                val twoFiles = picked.size == 2 && picked.none { it.isDirectory }
                val anyDirectory = picked.any { it.isDirectory }
                val inArchive = focused.uri.scheme == "archive"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionBarItem("⧉", crossPaneLabel("复制", focusSide)) { controller.copyToOther(focusSide) }
                    ActionBarItem("✂", crossPaneLabel("移动", focusSide)) { controller.moveToOther(focusSide) }
                    ActionBarItem("🗑", "删除") {
                        if (picked.isEmpty()) return@ActionBarItem
                        if (inArchive) controller.deleteInsideArchive(focusSide, picked)
                        else deleting = picked.first()
                    }
                    ActionBarItem("✎", "重命名", enabled = picked.isNotEmpty()) {
                        when {
                            inArchive -> archiveRename = picked.firstOrNull()
                            picked.size > 1 -> batchRenameFor = picked
                            picked.size == 1 -> renaming = picked.first()
                        }
                    }
                    ActionBarItem("⬇", "压缩", enabled = picked.isNotEmpty() && !inArchive) {
                        if (picked.isNotEmpty()) compressFormatPicker = true
                    }
                    ActionBarItem("⇆", "文件对比", enabled = twoFiles) { controller.startFileDiff(focusSide) }
                    ActionBarItem("📋", "复制到剪贴板") { controller.copySelectionToClipboard(focusSide) }
                    ActionBarItem("🔖", "添加书签") { controller.addBookmark(focusSide) }
                    ActionBarItem("ⓘ", "属性", enabled = picked.size == 1) {
                        picked.firstOrNull()?.let { controller.showProperties(it) }
                    }
                    ActionBarItem("⇪", "分享", enabled = !anyDirectory && !inArchive) {
                        picked.firstOrNull()?.let { item ->
                            shareItem(container, context, item) { msg -> controller.showStatus(msg) }
                        }
                    }
                }
                HSeparator()
            }

            // ---------------- 两个窗格
            Row(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { panesWidthPx = it.size.width.toFloat() },
            ) {
                val showLeft = !ui.singlePane || ui.focused == PaneSide.LEFT
                val showRight = !ui.singlePane || ui.focused == PaneSide.RIGHT
                if (showLeft) {
                    PaneView(
                        container = container,
                        side = PaneSide.LEFT,
                        pane = ui.left,
                        focused = ui.focused == PaneSide.LEFT,
                        // 操作前两侧路径栏**同时**高亮（源与目标都要看得见；旧实现只高亮 focused 一侧）
                        highlight = ui.highlight,
                        controller = controller,
                        modifier = Modifier.weight(ui.splitRatio),
                        onRowAction = { rowAction = it },
                    )
                }
                if (showLeft && showRight) {
                    // 可拖动分隔条（复刻 MT 0x7f0c0033 的 0900B2 Guideline + 090111/090112 1px 线；
                    // MT 的分隔线只有 1px，但触摸区给足 10dp 便于拖动）
                    Box(
                        Modifier
                            .width(10.dp)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                // 读取 controller 实时比例（pointerInput(Unit) 不会随重组重启，
                                // 闭包里捕获的 ui.splitRatio 会过期，导致拖动回拉无效）
                                detectDragGestures(
                                    onDragEnd = { controller.persistSplitRatio() },
                                    onDragCancel = { controller.persistSplitRatio() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    val total = panesWidthPx.coerceAtLeast(1f)
                                    controller.setSplitRatio(controller.state.value.splitRatio + dragAmount.x / total)
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    controller.setSplitRatio(0.5f)
                                    controller.persistSplitRatio()
                                })
                            }
                            .semantics { contentDescription = "左右窗口分隔条（拖动调整比例，双击恢复等分）" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                }
                if (showRight) {
                    PaneView(
                        container = container,
                        side = PaneSide.RIGHT,
                        pane = ui.right,
                        focused = ui.focused == PaneSide.RIGHT,
                        highlight = ui.highlight,
                        controller = controller,
                        modifier = Modifier.weight(1f - ui.splitRatio),
                        onRowAction = { rowAction = it },
                    )
                }
            }

            // ---------------- 任务条
            if (ui.tasks.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ui.tasks.take(2).forEach { snapshot -> TaskRow(snapshot, controller, onOpenTasks) }
                    if (ui.tasks.size > 2) {
                        Text(
                            "还有 ${ui.tasks.size - 2} 个任务…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------- 底部：多选工具栏 或 命令栏
            if (focused.hasSelection) {
                // MT：多选模式下出现「全选 / 反选 / 类选 / 同步」动态按钮
                val bottomExtraSel = container.settings.value.bottomBarPaddingDp.dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(MtSpec.BottomBarHeight + bottomExtraSel)
                        .padding(bottom = bottomExtraSel)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BottomTextCommand("全选") { controller.selectAll(focusSide) }
                    BottomTextCommand("反选") { controller.invertSelection(focusSide) }
                    BottomTextCommand("类选") { controller.selectSameType(focusSide) }
                    BottomTextCommand("同步", onLongClick = { filterInput = true }) { controller.syncPath() }
                    BottomTextCommand("取消") { controller.clearSelection(focusSide) }
                }
            } else {
                val bottomExtra = container.settings.value.bottomBarPaddingDp.dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(MtSpec.BottomBarHeight + bottomExtra)
                        .padding(bottom = bottomExtra)
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            // 底栏上滑 → 书签（MT 手册：从底栏上滑调出书签）
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -12f && container.settings.value.bookmarkSwipe) onOpenBookmarks()
                            }
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BottomCommand(MtIcon.BACK, "后退", enabled = focused.tab.back.isNotEmpty()) { controller.back(focusSide) }
                    BottomCommand(MtIcon.FORWARD, "前进", enabled = focused.tab.forward.isNotEmpty()) { controller.forward(focusSide) }
                    Box {
                        BottomCommand(MtIcon.PLUS, "新建（长按新建文件）", onLongClick = { creatingFile = true }) { showCreateMenu = true }
                        // MT：新建（＋）弹出菜单
                        DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("📁  新建文件夹") },
                                onClick = { showCreateMenu = false; creatingFolder = true },
                            )
                            DropdownMenuItem(
                                text = { Text("📄  新建文件") },
                                onClick = { showCreateMenu = false; creatingFile = true },
                            )
                        }
                    }
                    // MT 底栏第三个按钮是「同步」（0x7f11069b「同步」）：
                    // 点击 = 另一窗格跟随本窗格路径；长按 = 过滤（MT 0x7f11028f「长按底部的「同步」按钮也可以进行过滤」）。
                    // （旧实现把这个位置做成「交换窗口」→ 用户误触会整列对调，是误触投诉的主因）
                    BottomCommand(MtIcon.SYNC, "同步路径到另一窗口（长按过滤）", onLongClick = { filterInput = true }) {
                        controller.syncPath()
                    }
                    // 压缩包内部也能「↑」（回到压缩包所在目录），与 PaneView 的 canGoUp 一致
                    BottomCommand(
                        MtIcon.UP,
                        "上级目录（长按输入路径）",
                        enabled = focused.uri.parent != null || focused.uri.scheme == "archive",
                        onLongClick = { gotoPath = true },
                    ) { controller.up(focusSide) }
                }
            }
        }
    }

    // ---------------- ⋮ 菜单（MT 截图3 顺序 + 图标）
    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
        if (!hiddenSub) {
            MtMenuItem("⟳", "刷新") { showMoreMenu = false; controller.refresh(focusSide) }
            // MT：⋮ 菜单里的「输入路径」（现在长按 ↑ 也能调出，这里补上入口便于发现）
            MtMenuItem("⌨", "输入路径") { showMoreMenu = false; gotoPath = true }
            MtMenuItem("🔍", "搜索") { showMoreMenu = false; showSearch = true }
            MtMenuItem("▣", "全选") { showMoreMenu = false; controller.selectAll(focusSide) }
            MtMenuItem("▽", "过滤") { showMoreMenu = false; filterInput = true }
            MtMenuItem("⇅", "排序方式") { showMoreMenu = false; showSortDialog = true }
            MtMenuItem("👁", "隐藏文件", trailing = "▶") { hiddenSub = true }
            MtMenuItem("📋", "复制到剪贴板") { showMoreMenu = false; controller.copySelectionToClipboard(focusSide) }
            if (controller.hasClipboard) {
                MtMenuItem("📥", "粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide) }
                MtMenuItem("✂", "移动粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide, move = true) }
            }
            MtMenuItem("🔖", "添加书签") { showMoreMenu = false; controller.addBookmark(focusSide) }
            MtMenuItem("🏠", "设为首页") { showMoreMenu = false; controller.setAsHome(focusSide) }
            // MT 0x7f1104ab「已设置为该网络存储的初始路径」：把当前路径写回连接的初始路径
            if (focused.uri.scheme != "local" && focused.uri.scheme != "archive") {
                MtMenuItem("📍", "设为该网络存储的初始路径") {
                    showMoreMenu = false
                    controller.setAsConnectionInitialPath(focusSide)
                }
            }
            MtMenuItem("🔄", "同步（另一窗格跟随本窗格）") { showMoreMenu = false; controller.syncPath() }
            MtMenuItem("⇄", "交换窗口") {
                showMoreMenu = false
                controller.swapPanes()
                controller.showStatus("已交换窗口")
            }
            // 标签页：单标签时标签条隐藏（省空间）→ 这里补「新建标签页」入口，
            // 保证任何时候都能开第二个标签（开完标签条自动出现）
            MtMenuItem("🗂", "新建标签页") { showMoreMenu = false; controller.newTab(focusSide) }
            if (focused.uri.scheme == "archive") {
                // MT：压缩包内时，右上角菜单提供「测试压缩包完整性」与解压
                MtMenuItem("✓", "测试压缩包完整性") { showMoreMenu = false; controller.testArchive(focusSide) }
                MtMenuItem("⬆", "解压到对面窗格") {
                    showMoreMenu = false
                    controller.extractTo(focusSide, ui.pane(focusSide.other).uri)
                }
                MtMenuItem("📂", "解压到压缩包所在目录") {
                    showMoreMenu = false
                    val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
                    val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                    val dir = host?.parent
                    if (dir != null) controller.extractTo(focusSide, dir)
                    else controller.showStatus("无法确定压缩包所在目录")
                }
                // MT 0x7f0c00ce「解压」对话框：三个单选（单独的文件夹 / 当前目录 / 文件夹…）+ 基于另一窗口路径
                MtMenuItem("🗂", "解压…") {
                    showMoreMenu = false
                    extractDirPicker = true
                }
                MtMenuItem("📥", "添加对面选中项到压缩包") { showMoreMenu = false; controller.addToArchive(focusSide) }
            }
            MtMenuItem("⇆", "比较两个目录") { showMoreMenu = false; controller.compareDirectories() }
            // 视图：单/双窗格切换（设置里有「默认单列」，但运行期没有入口 → 补上）
            MtMenuItem("◫", if (ui.singlePane) "切换到双窗口" else "切换到单窗口") {
                showMoreMenu = false
                controller.toggleSinglePane()
            }
            // 类型过滤（MT 的「过滤」下拉：文件夹 / 图片 / 视频 …；此前 filterKind 有状态无入口）
            MtMenuItem("▽", "类型过滤" + focused.filterKind?.let { "（已过滤）" } ?: "") { showMoreMenu = false; showTypeFilter = true }
            MtMenuItem("⚙", "设置") { showMoreMenu = false; onOpenSettings() }
            MtMenuItem("➡", "退出") {
                showMoreMenu = false
                (context as? Activity)?.finishAffinity()
            }
        } else {
            // 隐藏文件 ▶ 子菜单（MT：带勾选态）
            MtMenuItem("‹", "隐藏文件") { hiddenSub = false }
            MtMenuItem(if (focused.showHidden) "☑" else "☐", "显示隐藏文件") {
                showMoreMenu = false
                if (!focused.showHidden) controller.toggleHidden(focusSide)
            }
            MtMenuItem(if (!focused.showHidden) "☑" else "☐", "不显示隐藏文件") {
                showMoreMenu = false
                if (focused.showHidden) controller.toggleHidden(focusSide)
            }
        }
    }

    // ---------------- MT 动作菜单（长按文件，截图2 布局）
    rowAction?.let { item ->
        val multi = focused.selection.size
        val picked = focused.selectedItems.ifEmpty { listOf(item) }
        // MT 置灰规则：选中项含文件夹时，分享 / 打开方式 不可用（系统不支持分享文件夹）
        val anyDirectory = picked.any { it.isDirectory }
        // MT：同时选中两个文件时长按出现「文件对比」
        val twoFiles = picked.size == 2 && picked.none { it.isDirectory }
        MtActionSheet(
            actions = buildList {
                // MT：箭头跟随目标窗口方向 —— 选中项在左窗格 → `复制 ->`；在右窗格 → `<- 复制`
                add(MtAction("copy_to", crossPaneLabel("复制", focusSide), "⧉", singleWindow = true))
                add(MtAction("move_to", crossPaneLabel("移动", focusSide), "✂", singleWindow = true))
                add(MtAction("delete", "删除", "🗑"))
                add(MtAction("rename", "重命名", "✎"))
                add(MtAction("tools", "工具", "🔧"))
                add(MtAction("compress", "压缩", "⬇"))
                if (twoFiles) add(MtAction("diff", "文件对比", "⇆"))
                add(MtAction("properties", "属性", "ⓘ", enabled = multi <= 1))
                add(MtAction("share", "分享", "⇪", enabled = !anyDirectory))
                add(MtAction("open_with", "打开方式…", "✓", enabled = !anyDirectory))
                add(MtAction("clipboard", "复制到剪贴板", "📋"))
                add(MtAction("bookmark", "添加书签", "🔖"))
            },
            onAction = { id ->
                rowAction = null
                when (id) {
                    "copy_to" -> controller.copyToOther(focusSide)
                    "move_to" -> controller.moveToOther(focusSide)
                    "delete" -> {
                        if (focused.uri.scheme == "archive") {
                            controller.deleteInsideArchive(focusSide, picked)
                        } else {
                            deleting = item
                        }
                    }
                    "rename" -> {
                        when {
                            focused.uri.scheme == "archive" -> archiveRename = item
                            picked.size > 1 -> batchRenameFor = picked
                            else -> renaming = item
                        }
                    }
                    "diff" -> controller.startFileDiff(focusSide)
                    "tools" -> toolsFor = item
                    "compress" -> compressFormatPicker = true
                    "properties" -> controller.showProperties(item)
                    "share" -> shareItem(container, context, item) { msg -> controller.showStatus(msg) }
                    "open_with" -> openWithFor = item
                    "clipboard" -> controller.copySelectionToClipboard(focusSide)

                    "bookmark" -> {
                        controller.addBookmark(focusSide)
                    }
                }
            },
            onLongAction = { id ->
                rowAction = null
                when (id) {
                    "copy_to" -> singleWindowOp = TransferOp.COPY
                    "move_to" -> singleWindowOp = TransferOp.MOVE
                }
            },
            onDismiss = { rowAction = null },
        )
    }

    // ---------------- 工具子菜单（MT 的「工具」）
    toolsFor?.let { item ->
        MtActionSheet(
            title = "工具 · ${item.name}",
            actions = listOf(
                MtAction("copy_path", "复制路径", "⧉"),
                MtAction("crc32", "校验值 CRC32", "#"),
                MtAction("md5", "校验值 MD5", "#"),
                MtAction("sha1", "校验值 SHA-1", "#"),
                MtAction("sha256", "校验值 SHA-256", "#"),
                MtAction("chmod", "修改权限", "🔒"),
                MtAction(
                    "swap_name",
                    "交换文件名",
                    "⇄",
                    enabled = focused.selection.size == 2,
                ),
                MtAction("select_all", "全选", "☑"),
                MtAction("invert", "反选", "☐"),
                MtAction("same_type", "类选", "▣"),
                MtAction("exit", "退出多选", "✕"),
            ),
            onAction = { id ->
                toolsFor = null
                when (id) {
                    "copy_path" -> {
                        clipboard.setText(AnnotatedString(item.uri.toString()))
                        controller.showStatus("路径已复制：${item.uri.displayPath}")
                    }
                    "crc32" -> controller.checksum(item.uri, "CRC32") { r ->
                        message = "CRC32" to (r ?: "计算失败")
                    }
                    "sha1" -> controller.checksum(item.uri, "SHA-1") { r ->
                        message = "SHA-1" to (r ?: "计算失败")
                    }
                    "md5" -> controller.checksum(item.uri, "MD5") { r ->
                        message = "MD5" to (r ?: "计算失败")
                    }
                    "sha256" -> controller.checksum(item.uri, "SHA-256") { r ->
                        message = "SHA-256" to (r ?: "计算失败")
                    }
                    "chmod" -> permissionFor = item
                    "swap_name" -> controller.swapSelectedNames(focusSide)
                    "select_all" -> controller.selectAll(focusSide)
                    "invert" -> controller.invertSelection(focusSide)
                    "same_type" -> controller.selectSameType(focusSide)
                    "exit" -> controller.clearSelection(focusSide)
                }
            },
            onLongAction = {},
            onDismiss = { toolsFor = null },
        )
    }

    // ---------------- 各种输入框
    if (gotoPath) {
        TextInputDialog(
            title = "跳转路径",
            initial = focused.uri.displayPath,
            label = "路径",
            hint = "可写完整 URI（s3://bucket/dir、/share/sub），也可写相对路径",
            onConfirm = { input ->
                val text = input.trim()
                val dest = if (text.contains("://")) runCatching { VfsUri.parse(text) }.getOrNull()
                else focused.uri.withPath(if (text.startsWith("/")) text else "/$text")
                if (dest != null) controller.open(focusSide, dest, focused.tab.connectionId, focused.tab.label)
                else controller.showStatus("路径格式不正确")
            },
            onDismiss = { gotoPath = false },
        )
    }
    if (filterInput) {
        TextInputDialog(
            title = "过滤",
            initial = focused.search,
            label = "关键字",
            hint = "普通文本=包含；!文本=不包含；/正则；!/正则=正则否定。留空清除。",
            onConfirm = { q -> controller.setSearch(focusSide, q) },
            onDismiss = { filterInput = false },
        )
    }
    // MT 的「过滤」类型下拉（文件夹 / 图片 / 视频 / 音频 / 压缩包 / 文档…）：
    // 控制器与状态（PaneState.filterKind）早已支持，此前 UI 没有任何入口 → 补齐
    if (showTypeFilter) {
        val kinds = listOf(
            null to "全部类型",
            "dir" to "文件夹",
            com.u707t.panelfm.core.common.MimeTypes.Kind.IMAGE.name to "图片",
            com.u707t.panelfm.core.common.MimeTypes.Kind.VIDEO.name to "视频",
            com.u707t.panelfm.core.common.MimeTypes.Kind.AUDIO.name to "音频",
            com.u707t.panelfm.core.common.MimeTypes.Kind.ARCHIVE.name to "压缩包",
            com.u707t.panelfm.core.common.MimeTypes.Kind.APK.name to "APK",
            com.u707t.panelfm.core.common.MimeTypes.Kind.PDF.name to "PDF",
            com.u707t.panelfm.core.common.MimeTypes.Kind.FONT.name to "字体",
            com.u707t.panelfm.core.common.MimeTypes.Kind.CODE.name to "代码",
            com.u707t.panelfm.core.common.MimeTypes.Kind.TEXT.name to "文本",
        )
        AlertDialog(
            onDismissRequest = { showTypeFilter = false },
            title = { Text("类型过滤") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    kinds.forEach { (kind, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple {
                                    showTypeFilter = false
                                    controller.setFilter(focusSide, kind)
                                    controller.refresh(focusSide)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = focused.filterKind == kind, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTypeFilter = false }) { Text("关闭") } },
        )
    }
    // MT 的搜索：文件名 + 搜索子目录 + 高级搜索（内容 / 大小范围）
    if (showSearch) {
        MtSearchDialog(
            initialQuery = focused.search,
            history = container.settings.value.searchHistory,
            onSearch = { q, field, recursive, minSize, maxSize ->
                val hasSizeFilter = minSize >= 0 || maxSize >= 0
                when {
                    // 仅当前目录 + 无大小条件：等价于目录内过滤（沿用 /regex、!text 语法）
                    !recursive && !hasSizeFilter && field == SearchField.NAME -> controller.setSearch(focusSide, q)
                    !recursive && !hasSizeFilter && field == SearchField.REGEX -> controller.setSearch(focusSide, "/$q")
                    else -> {
                        searching = true
                        searchResults = emptyList()
                        scope.launch {
                            container.prefs.addSearchQuery(q)
                            val r = runCatching {
                                controller.searchTree(
                                    side = focusSide,
                                    nameQuery = if (field == SearchField.CONTENT) "" else q,
                                    recursive = recursive,
                                    contentQuery = if (field == SearchField.CONTENT) q else "",
                                    minSize = minSize,
                                    maxSize = maxSize,
                                    nameRegex = field == SearchField.REGEX,
                                )
                            }.onFailure {
                                controller.showStatus((it as? VfsException)?.userMessage ?: "搜索失败：${it.message}")
                            }.getOrDefault(emptyList())
                            searching = false
                            searchResults = r
                        }
                    }
                }
            },
            onDismiss = { showSearch = false },
        )
    }
    searchResults?.let { results ->
        MtSearchResultsDialog(
            results = results,
            searching = searching,
            onPick = { item ->
                searchResults = null
                controller.reveal(focusSide, item.uri)
            },
            onDismiss = { searchResults = null },
        )
    }
    if (singleWindowOp != null) {
        val op = singleWindowOp!!
        TextInputDialog(
            title = if (op == TransferOp.COPY) "复制到（本窗格内）" else "移动到（本窗格内）",
            initial = focused.uri.displayPath,
            label = "目标目录",
            hint = "单窗口操作：目标仍在当前窗格内，输入目录后立即执行",
            onConfirm = { path ->
                val dest = focused.uri.withPath(if (path.startsWith("/")) path else "/$path")
                if (op == TransferOp.COPY) controller.copyWithinPane(focusSide, dest)
                else controller.moveWithinPane(focusSide, dest)
            },
            onDismiss = { singleWindowOp = null },
        )
    }
    permissionFor?.let { item ->
        MtPermissionDialog(
            fileName = item.name,
            isDirectory = item.isDirectory,
            initialMode = item.permissions,
            canRecurse = item.isDirectory,
            onDismiss = { permissionFor = null },
            onConfirm = { mode, recurseFiles, recurseDirs ->
                permissionFor = null
                controller.changePermissions(item.uri, mode, recurseFiles, recurseDirs)
            },
        )
    }
    // 解压（复刻 MT 0x7f0c00ce「解压」）
    if (extractDirPicker) {
        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
        val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
        val archiveParent = host?.parent
        val archiveName = host?.name ?: "压缩包"
        MtExtractDialog(
            archiveName = archiveName.substringBeforeLast('.', archiveName),
            currentDirPath = focused.uri.displayPath,
            otherPanePath = ui.pane(focusSide.other).uri.takeIf { it.scheme != "archive" }?.displayPath,
            onDismiss = { extractDirPicker = false },
            onConfirm = { target, customPath ->
                extractDirPicker = false
                when (target) {
                    ExtractTarget.OWN_FOLDER ->
                        if (archiveParent != null) controller.extractToOwnFolder(focusSide, archiveParent)
                        else controller.showStatus("无法确定压缩包所在目录")
                    ExtractTarget.HERE -> controller.extractTo(focusSide, focused.uri)
                    ExtractTarget.PICK_FOLDER -> {
                        val path = customPath ?: return@MtExtractDialog
                        val parsed = runCatching { VfsUri.parse(path) }.getOrNull()
                        if (parsed != null) controller.extractTo(focusSide, parsed)
                        else controller.showStatus("路径格式无法识别：$path")
                    }
                }
            },
        )
    }

    // 压缩（复刻 MT 0x7f0c0080「创建压缩文件」：文件名 / 格式 / 压缩级别 / 密码 / 同时加密文件名）
    if (compressFormatPicker) {
        MtCompressDialog(
            itemCount = focused.selectedItems.size,
            onDismiss = { compressFormatPicker = false },
            onConfirm = { toOther, fmt, fileName, level, pwd, encNames ->
                compressFormatPicker = false
                if (toOther) {
                    controller.compressToOther(focusSide, fmt, fileName, level, pwd, encNames)
                } else {
                    controller.compressHere(focusSide, fmt, fileName, level, pwd, encNames)
                }
            },
        )
    }
    // 压缩包内重命名（完整路径，可改父目录 = 移动）
    archiveRename?.let { item ->
        TextInputDialog(
            title = "重命名（压缩包内）",
            initial = item.name,
            label = "完整路径（可含目录）",
            hint = "MT 语义：重命名的是完整路径，改父目录即为移动",
            onConfirm = { newPath -> controller.renameInsideArchive(focusSide, item, newPath) },
            onDismiss = { archiveRename = null },
        )
    }
    message?.let { (title, body) ->
        MessageDialog(title, body) { message = null }
    }
    // 打开方式（MT：内置打开方式列表 + 长按设为默认 + 管理）
    openWithFor?.let { item ->
        val kind = MimeTypes.kindOf(item.extension)
        OpenWithDialog(
            fileName = item.name,
            mimeType = item.mimeType,
            localFile = item.uri.scheme == "local",
            options = listOf(
                OpenWithOption(PreviewMode.TEXT, available = kind == MimeTypes.Kind.TEXT || kind == MimeTypes.Kind.CODE || kind == MimeTypes.Kind.OTHER),
                OpenWithOption(PreviewMode.EDITOR, available = kind != MimeTypes.Kind.IMAGE && kind != MimeTypes.Kind.AUDIO && kind != MimeTypes.Kind.VIDEO),
                OpenWithOption(PreviewMode.HEX, available = true),
                OpenWithOption(PreviewMode.IMAGE, available = kind == MimeTypes.Kind.IMAGE),
                OpenWithOption(PreviewMode.MEDIA, available = kind == MimeTypes.Kind.AUDIO || kind == MimeTypes.Kind.VIDEO),
                OpenWithOption(
                    PreviewMode.ARCHIVE,
                    available = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name) != null,
                ),
                OpenWithOption(PreviewMode.FONT, available = kind == MimeTypes.Kind.FONT),
                OpenWithOption(PreviewMode.PDF, available = kind == MimeTypes.Kind.PDF),
                OpenWithOption(PreviewMode.APK_INFO, available = kind == MimeTypes.Kind.APK),
                OpenWithOption(PreviewMode.SYSTEM, available = item.uri.scheme == "local"),
            ),
            defaultMode = controller.defaultOpenMode(item),
            onPick = { mode ->
                openWithFor = null
                if (mode == PreviewMode.SYSTEM) {
                    openWithSystem(container, context, item) { msg -> controller.showStatus(msg) }
                } else {
                    controller.openWith(item, mode)
                }
            },
            onPickSystem = { app ->
                openWithFor = null
                openWithSystemApp(container, context, item, app) { msg -> controller.showStatus(msg) }
            },
            onSetDefault = { mode ->
                controller.setDefaultOpenMode(item, mode)
            },
            onManage = { openWithManage = true },
            onDismiss = { openWithFor = null },
        )
    }
    if (openWithManage) {
        var entries by remember { mutableStateOf(controller.openModes()) }
        OpenWithManageDialog(
            entries = entries,
            onDelete = { ext -> controller.clearOpenMode(ext); entries = controller.openModes() },
            onDismiss = { openWithManage = false },
        )
    }
    // 批量重命名（MT 表达式）
    batchRenameFor?.let { items ->
        BatchRenameDialog(
            items = items,
            onConfirm = { expression, find, replace, useRegex ->
                scope.launch {
                    var ok = 0
                    items.forEachIndexed { index, fm ->
                        val newName = BatchRename.newName(expression, fm, index, find, replace, useRegex)
                        if (newName != fm.name && newName.isNotBlank()) {
                            val target = fm.uri.parent?.child(newName)
                            val vfs = container.locator.find(fm.uri)
                            if (target != null && vfs != null) {
                                // 走 VFS 调度器（旧实现直接在 UI 协程里同步调用网络重命名 → 主线程卡顿）
                                val done = kotlinx.coroutines.withContext(container.dispatchers.vfs) {
                                    runCatching { vfs.rename(fm.uri, target) }.getOrDefault(false)
                                }
                                if (done) ok++
                            }
                        }
                    }
                    controller.showStatus("批量重命名完成：$ok / ${items.size}")
                    controller.clearSelection(focusSide)
                    controller.refreshAll()
                }
            },
            onDismiss = { batchRenameFor = null },
        )
    }
    renaming?.let { item ->
        TextInputDialog(
            title = "重命名",
            initial = item.name,
            onConfirm = { newName -> controller.rename(item.uri, newName, focusSide) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { item ->
        var bigCount by remember { mutableStateOf(-1) }
        LaunchedEffect(item.uri) {
            bigCount = if (item.isDirectory && item.uri.scheme == "local") controller.countLocalEntries(item.uri) else -1
        }
        // 多选时删除的是整个选择集（顶栏动作条 / 底栏「删除」都走这里）
        val delCount = focused.selection.size
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除") },
            text = {
                Column {
                    Text(
                        if (delCount > 1) "确定删除已选中的 $delCount 项？"
                        else "确定删除「${item.name}」？" + if (item.isDirectory) "（含目录内容）" else ""
                    )
                    if (bigCount > 1000) {
                        Text(
                            "该目录含 $bigCount+ 个文件：可用「极速删除」直接清理（不进回收站，秒级完成）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (bigCount > 1000) {
                        TextButton(onClick = {
                            controller.deleteSelected(focusSide, fastDelete = true)
                            deleting = null
                        }) { Text("极速删除") }
                    }
                    TextButton(onClick = {
                        controller.deleteSelected(focusSide)
                        deleting = null
                    }) { Text("删除") }
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (creatingFolder) {
        TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onConfirm = { name -> controller.createFolder(focusSide, name) },
            onDismiss = { creatingFolder = false },
        )
    }
    if (creatingFile) {
        TextInputDialog(
            title = "新建文件",
            label = "文件名",
            onConfirm = { name -> controller.createFile(focusSide, name) },
            onDismiss = { creatingFile = false },
        )
    }

    // ---------------- 排序对话框（复刻 MT：单选项 + 仅应用于此文件夹 / 逆向排序 + 管理/取消/确定）
    if (showSortDialog) {
        MtSortDialog(
            paneLabel = if (focusSide == PaneSide.LEFT) "左窗口" else "右窗口",
            initial = focused.sort,
            folderRuleExists = controller.hasFolderSortRule(focused.uri),
            onManage = { showSortDialog = false; sortManage = true },
            onConfirm = { spec, folderOnly ->
                showSortDialog = false
                controller.applySort(focusSide, spec, folderOnly)
            },
            onDismiss = { showSortDialog = false },
        )
    }
    if (sortManage) {
        MtSortManageDialog(
            rules = controller.folderSortRules(),
            onClear = { controller.clearFolderSorts(); sortManage = false },
            onDismiss = { sortManage = false },
        )
    }

    // ---------------- 状态提示
    ui.status?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.padding(bottom = 66.dp),
                action = { TextButton(onClick = { controller.consumeStatus() }) { Text("知道了") } },
            ) { Text(msg) }
        }
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            controller.consumeStatus()
        }
    }

    // ---------------- 冲突 / 移动确认 / 属性
    ui.renameConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { controller.dismissRenameConflict() },
            title = { Text("目标名称已存在") },
            text = { Text("「${conflict.target.name}」已存在，与源文件同名（都不是文件夹）。请选择处理方式：") },
            confirmButton = { TextButton(onClick = { controller.resolveRenameConflict("swap") }) { Text("交换") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.resolveRenameConflict("delete") }) { Text("删除目标") }
                    TextButton(onClick = { controller.resolveRenameConflict("backup") }) { Text("备份(.bak)") }
                    TextButton(onClick = { controller.dismissRenameConflict() }) { Text("取消") }
                }
            },
        )
    }
    ui.pendingMove?.let { pending ->
        MoveConfirmDialog(pending, onConfirm = { controller.confirmMove() }, onCancel = { controller.cancelMove() })
    }
    ui.conflict?.let { info ->
        ConflictDialog(info) { policy, applyAll -> controller.resolveConflict(policy, applyAll) }
    }
    ui.diff?.let { diff ->
        FolderDiffDialog(
            result = diff,
            onCopyOnlyLeft = { controller.copyDiffOnlyLeft() },
            onCopyNewer = { controller.copyDiffNewer() },
            onDismiss = { controller.dismissDiff() },
        )
    }
    ui.property?.let { item ->
        PropertiesDialog(container, item) {
            controller.dismissProperties()
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun BottomCommand(
    icon: MtIcon,
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val base = Modifier
        // MT 底栏：每个按钮是整高点击区（0x7f070031 = 64dp），24dp 线性图标居中
        .fillMaxHeight()
        .widthIn(min = 56.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(if (highlighted) MtSpec.AccentLight.copy(alpha = 0.14f) else Color.Transparent)
    val tapAction = onClick
    val longAction = onLongClick
    val modifier = if (longAction != null) {
        base
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = null,
                    onLongPress = { longAction() },
                    onTap = { if (enabled) tapAction() },
                )
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label = "点击") { if (enabled) tapAction(); true }
                onLongClick(label = "长按") { longAction(); true }
            }
    } else {
        base
            .clickableNoRipple(enabled, tapAction)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
    }
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(
            icon = icon,
            size = MtSpec.BottomBarIcon,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                highlighted -> MtSpec.AccentLight
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f)
            },
        )
    }
}

/** 底栏文字按钮（多选工具栏用） */
@Composable
private fun BottomTextCommand(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val tapAction = onClick
    val longAction = onLongClick
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (longAction != null) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { longAction() },
                                onTap = { tapAction() },
                            )
                        }
                        .semantics {
                            role = Role.Button
                            onClick { tapAction(); true }
                            onLongClick { longAction(); true }
                        }
                } else Modifier.clickableNoRipple(onClick = tapAction),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** MT 顶栏动作条按钮（复刻 0x7f0c0034：图标 22dp + 文字 14sp，左右 padding 15dp） */
@Composable
private fun ActionBarItem(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxHeight()
            .clickableNoRipple(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled) stateDescription = "不可用"
                onClick(label = label) { if (enabled) { onClick(); true } else false }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        Text(icon, style = MaterialTheme.typography.titleMedium, color = tint)
        Text(
            label,
            // MT 0x7f0c0034：文字 14sp
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            maxLines = 1,
            color = tint,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** ⋮ 菜单项（MT 截图3：左图标 + 文字 + 可选右侧箭头） */
@Composable
private fun MtMenuItem(icon: String, label: String, trailing: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    icon,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(30.dp),
                )
                Text(label, modifier = Modifier.weight(1f))
                trailing?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        onClick = onClick,
    )
}

private fun sortLabel(by: SortBy): String = when (by) {
    SortBy.NAME -> "按名称"
    SortBy.SIZE -> "按大小"
    SortBy.TIME -> "按日期"
    SortBy.TYPE -> "按类型"
}

// --------------------------------------------------------------------------- MT 排序对话框

/** 复刻 MT「排序方式 - 窗口名」：2×2 单选项 + 仅应用于此文件夹 / 逆向排序 + 管理/取消/确定 */
@Composable
private fun MtSortDialog(
    paneLabel: String,
    initial: SortSpec,
    folderRuleExists: Boolean,
    onManage: () -> Unit,
    onConfirm: (SortSpec, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var by by remember { mutableStateOf(initial.by) }
    var reverse by remember { mutableStateOf(!initial.ascending) }
    var folderOnly by remember { mutableStateOf(folderRuleExists) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式 - $paneLabel") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    SortChoice("按名称", SortBy.NAME, by) { by = it }
                    SortChoice("按大小", SortBy.SIZE, by) { by = it }
                }
                Row(Modifier.fillMaxWidth()) {
                    SortChoice("按日期", SortBy.TIME, by) { by = it }
                    SortChoice("按类型", SortBy.TYPE, by) { by = it }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { folderOnly = !folderOnly }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = folderOnly, onCheckedChange = { folderOnly = it })
                    Text("仅应用于此文件夹", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { reverse = !reverse }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = reverse, onCheckedChange = { reverse = it })
                    Text("逆向排序", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { TextButton(onClick = onManage) { Text("管理") } },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    onConfirm(initial.copy(by = by, ascending = !reverse), folderOnly)
                }) { Text("确定") }
            }
        },
    )
}

@Composable
private fun RowScope.SortChoice(label: String, value: SortBy, current: SortBy, onPick: (SortBy) -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .clickableNoRipple { onPick(value) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == value, onClick = { onPick(value) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** MT「排序 - 管理」：查看/清除「仅应用于此文件夹」记住的规则 */
@Composable
private fun MtSortManageDialog(
    rules: Map<String, String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rules.isEmpty()) {
                    Text("还没有「仅应用于此文件夹」的排序记录。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("已记住 ${rules.size} 个文件夹的排序：", style = MaterialTheme.typography.bodySmall)
                    rules.entries.take(12).forEach { (key, value) ->
                        val path = runCatching { VfsUri.parse(key).displayPath }.getOrDefault(key)
                        val desc = decodeSortSpec(value)?.let { s ->
                            sortLabel(s.by) + if (s.ascending) "·升序" else "·降序"
                        } ?: value
                        Text(
                            "• $path — $desc",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (rules.size > 12) Text("…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear, enabled = rules.isNotEmpty()) { Text("清除全部") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 分享：本地文件走 FileProvider（可分享给任何应用） */
internal fun shareItem(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再分享")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    if (!file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        onMessage("无法生成分享链接")
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享 ${item.name}")) }
        .onFailure { onMessage("没有可用的分享目标") }
}

/** 打开方式：直接交给指定的系统应用（MT 网格里点具体某个应用） */
internal fun openWithSystemApp(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    app: com.u707t.panelfm.ui.preview.SystemOpenApp,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再打开")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    if (!file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return onMessage("无法生成打开链接")
    val intent = Intent(app.action).apply {
        setClassName(app.packageName, app.activityName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (app.action == Intent.ACTION_VIEW) {
            setDataAndType(uri, item.mimeType ?: "*/*")
        } else {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { onMessage("打开失败：${it.message}") }
}

/** 打开方式：交给系统选择器 */
internal fun openWithSystem(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再打开")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return onMessage("无法生成打开链接")
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "打开方式")) }
        .onFailure { onMessage("没有可用的应用") }
}

@Composable
private fun TaskRow(snapshot: TransferTaskSnapshot, controller: BrowserController, onOpenTasks: () -> Unit) {
    val state = snapshot.state
    val progress = when (state) {
        is TaskState.Running -> if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
        is TaskState.Done -> 1f
        else -> 0f
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (snapshot.op == TransferOp.COPY) "复制" else "移动"} · ${snapshot.subtitle}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                is TaskState.Running, TaskState.Queued ->
                    TextButton(onClick = { controller.pauseTask(snapshot.id) }) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                TaskState.Paused ->
                    TextButton(onClick = { controller.resumeTask(snapshot.id) }) { Text("继续", style = MaterialTheme.typography.labelSmall) }
                else -> {}
            }
            TextButton(onClick = { controller.cancelTask(snapshot.id) }) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = onOpenTasks) { Text("详情", style = MaterialTheme.typography.labelSmall) }
        }
        ThinProgressBar(progress)
    }
}
