package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Spacer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtBottomIconButton
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.PaneEdgeShadow
import com.u707t.panelfm.core.ui.VDividerPx
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
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
import kotlin.math.roundToInt
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
    val settings by container.settings.collectAsState()
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
    var browseSub by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var sortManage by remember { mutableStateOf(false) }
    var gotoPath by remember { mutableStateOf(false) }
    var filterInput by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FileMetadata>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchStopped by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var refineInput by remember { mutableStateOf(false) }
    /** MT 0x7f110430「已搜索到 %s 个结果，你确定继续搜索？」→ 暂停搜索等用户回答 */
    var searchAsk by remember { mutableStateOf<Pair<Int, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null) }
    var singleWindowOp by remember { mutableStateOf<TransferOp?>(null) }
    var permissionFor by remember { mutableStateOf<FileMetadata?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openWithFor by remember { mutableStateOf<FileMetadata?>(null) }
    var openWithManage by remember { mutableStateOf(false) }
    var batchRenameFor by remember { mutableStateOf<List<FileMetadata>?>(null) }
    var compressFormatPicker by remember { mutableStateOf(false) }
    var extractDirPicker by remember { mutableStateOf(false) }
    /** 长按文件列表里的**压缩包文件**时的解压目标选择（null = 未触发） */
    var extractDialogFor by remember { mutableStateOf<FileMetadata?>(null) }
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

    /** 窗格标签页显示名（MT 顶栏 TabLayout 的页签名） */
    fun tabLabelOf(tab: PaneTab): String = tab.label.ifBlank { tab.uri.name.ifBlank { "/" } }

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
                // WebDAV：进入虚拟根（basePath 是挂载点，由协议层拼回）；其余协议进入 basePath / 初始路径。
                // 连接号统一由 AppContainer 注入（避免各处手工拼串漏掉 c=）。
                val uri = container.uriForConnection(config)
                controller.open(ui.focused, uri, config.id, config.name)
                connecting = null
                drawerState.close()
            } catch (e: Exception) {
                connecting = null
                controller.showStatus((e as? VfsException)?.userMessage ?: (e.message ?: "连接失败"))
            }
        }
    }

    // 返回手势：加载中 → 取消加载；多选 → 取消选择；否则返回上一级；已在根目录则交给外层（主页/退出）
    //
    // MT「再按一次」范式（0x7f11055c「再按一次返回上级」/ 0x7f110588「再按一次退出程序」）：
    // 不可逆操作用「连按两次」而不是弹窗，把摩擦降到最低。
    var upArmed by remember { mutableStateOf(false) }
    LaunchedEffect(upArmed) {
        if (upArmed) {
            kotlinx.coroutines.delay(2000)
            upArmed = false
        }
    }
    // 返回键：**只在真有「内部目标」时才拦截**。
    //
    // 旧实现 `enabled = true` 无条件拦截，且 else 分支跳主页 —— 它的组合顺序晚于
    // AppRoot 的退出处理，于是「浏览器 ←→ 主页」来回跳、永远退不出 App，
    // 设置里的「退出前双次确认」也永远轮不到（死设置）。
    // 现在：有内部目标（抽屉开 / 加载中 / 多选 / 能返回上级）才消费；否则把返回键
    // 让给 AppRoot 的退出流程。另外补上 MT 的「已在最上级 → 再按一次返回上级」语义
    // （旧代码里那段是恒 false 的不可达分支，upArmed 也是死状态）。
    val canGoUp = focused.uri.parent != null || focused.uri.scheme == "archive"
    val hasBackTarget = drawerState.isOpen || focused.loading || focused.hasSelection || canGoUp
    androidx.activity.compose.BackHandler(enabled = hasBackTarget) {
        when {
            drawerState.isOpen -> closeDrawer()
            focused.loading -> controller.cancelLoad(focusSide)
            focused.hasSelection -> controller.clearSelection(focusSide)
            canGoUp -> {
                // MT：在目录里直接返回上级（不弹窗）；已在最上级（根目录 / 压缩包顶层）
                // 才需要「再按一次」。注意 canGoUp 对压缩包恒为 true，
                // 所以这里的「最上级」判断要按真实父级来算。
                val atTop = focused.uri.parent == null
                if (atTop) {
                    if (upArmed) controller.up(focusSide) else {
                        upArmed = true
                        controller.showStatus("再按一次返回上级")
                    }
                } else {
                    controller.up(focusSide)
                }
            }
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.84f)) {
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
            // ---------------- 顶部条（复刻 MT 0x7f0c0033 的 09046B + include 0x7f0c0034）
            //   MT 的顶栏**始终是深色**（浅色主题 #151515 / 深色 #303030）；
            //   结构：TabLayout(0903F9) + ⋮(0902B2) + ＋(090116) + 动作条(09022F) + 1px 分割线(0903F8)
            //   NORMAL 态：动作条整行 GONE；SELECTING 态：出现「复制 / 移动 / 删除」三连（可横向滚动）
            val topBarBg = if (LocalPanelDarkTheme.current) MtSpec.TopBarDark else MtSpec.TopBarLight
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(topBarBg),
            ) {
                // ---- 顶栏主体（复刻 MT `0x7f0c0033` 的 `09046B` + 自定义 View `09038A`）
                //   MT 截图实测：**☰、路径/统计（居中）、⋮ 全在同一块里** ——
                //   ☰ 与 ⋮ 在垂直方向跨两行居中，中间是「路径（大字）+ 统计（小字）」。
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 顶栏固定高度（MT `0x7f070002` = 56dp）：
                        // 给整行一个**有界高度**，行内任何 fillMaxHeight 子项都只会填满这一行，
                        // 不会把顶栏撑到整屏（曾经的「黑屏怪页面」根因）
                        .height(MtSpec.TopBarHeight)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ☰ 侧边栏
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple { scope.launch { drawerState.open() } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "打开侧边栏"
                            },
                    ) {
                        MtVectorIcon(icon = MtIcon.MENU, size = 26.dp, tint = MtSpec.TopBarText)
                    }

                    // 中间：路径（大字，居中）+ 统计（小字，居中）
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            // MT：路径完整显示，放不下才省略
                            focused.uri.displayPath.ifEmpty { "/" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MtSpec.TopBarTitleSize,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MtSpec.TopBarText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("文件夹: ").append(focused.dirCount)
                                append("  文件: ").append(focused.fileCount)
                                focused.space?.let {
                                    append("  储存: ")
                                        .append(Fmt.sizeCompact(it.total - it.free))
                                        .append("/")
                                        .append(Fmt.sizeCompact(it.total))
                                }
                                // MT 0x7f11063b「已选: %d」——多选计数必须实时更新
                                if (focused.hasSelection) append("  已选: ").append(focused.selection.size)
                                if (focused.filtered) append("  ·  已过滤")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.TopBarSubSize),
                            color = MtSpec.TopBarSubText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 动作条（09022F）：横向可滚动，选中项后出现（MT 的「复制/移动/删除」三连）
                    if (focused.hasSelection) {
                        Row(
                            Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TopActionItems(
                                focused = focused,
                                focusSide = focusSide,
                                controller = controller,
                                onDelete = { deleting = it },
                                onRename = { item: FileMetadata ->
                                    when {
                                        focused.uri.scheme == "archive" -> archiveRename = item
                                        focused.selection.size > 1 -> batchRenameFor = focused.selectedItems
                                        else -> renaming = item
                                    }
                                },
                                onCompress = { compressFormatPicker = true },
                                onCopyTo = { controller.startPickDir(PickDirPurpose.COPY_TO) },
                                onMoveTo = { controller.startPickDir(PickDirPurpose.MOVE_TO) },
                                onProperties = { item: FileMetadata -> controller.showProperties(item) },
                                onShare = { item: FileMetadata ->
                                    shareItem(container, context, item) { msg -> controller.showStatus(msg) }
                                },
                                onTypedAction = { id, it ->
                                    // 与长按菜单同一套处理（复用同一份实现，避免两处行为漂移）
                                    when (id) {
                                        TypeActions.ACTION_EXTRACT_HERE -> controller.extractTo(focusSide, focused.uri)
                                        TypeActions.ACTION_INSTALL -> installApk(container, context, it) { msg ->
                                            controller.showStatus(msg)
                                        }
                                        TypeActions.ACTION_APK_INFO -> controller.openWith(
                                            it, com.u707t.panelfm.ui.preview.PreviewMode.APK_INFO,
                                        )
                                        TypeActions.ACTION_OPEN_INTERNAL -> controller.openWith(
                                            it, com.u707t.panelfm.ui.preview.PreviewMode.AUTO,
                                        )
                                    }
                                },
                            )
                        }
                    }

                    // TabLayout + ＋：**只有多标签时才出现**（MT 截图：文件浏览态顶栏没有它们）
                    if (focused.tabs.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            focused.tabs.forEachIndexed { index, tab ->
                                val active = index == focused.activeTab
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickableNoRipple { controller.switchTab(focusSide, index) }
                                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                        .semantics {
                                            role = Role.Tab
                                            contentDescription = "标签页 ${tabLabelOf(tab)}" + if (active) "，当前" else ""
                                        },
                                ) {
                                    Text(
                                        tabLabelOf(tab),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) MtSpec.TopBarText else MtSpec.TopBarSubText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 76.dp),
                                    )
                                    MtVectorIcon(
                                        icon = MtIcon.CLOSE,
                                        size = 14.dp,
                                        tint = MtSpec.TopBarSubText,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickableNoRipple { controller.closeTab(focusSide, index) }
                                            .padding(2.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickableNoRipple { controller.newTab(focusSide) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "新建标签页"
                                    },
                            ) {
                                MtVectorIcon(icon = MtIcon.PLUS, size = 22.dp, tint = MtSpec.TopBarText)
                            }
                        }
                    }

                    // ⋮ 更多（0902B2）
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple { showMoreMenu = true; hiddenSub = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "更多菜单"
                            },
                    ) {
                        MtVectorIcon(icon = MtIcon.MORE, size = 26.dp, tint = MtSpec.TopBarText)
                    }
                }
            }

            // MT：顶栏底部分割线 1px（0903F8）
            DividerPx()

            // ---------------- 两个窗格
            // MT「单列 / 双列 / 自动切换」（0x7f1101fb/1fc/1fd/1fe）：
            // 自动切换 = 宽屏（≥600dp）双列、窄屏单列；由 ui.effectiveBrowseMode 展开。
            val wideEnough = LocalConfiguration.current.screenWidthDp >= 600
            LaunchedEffect(wideEnough) { controller.setWideEnough(wideEnough) }
            Row(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { panesWidthPx = it.size.width.toFloat() },
            ) {
                val single = ui.effectiveBrowseMode == BrowseMode.SINGLE
                val showLeft = !single || ui.focused == PaneSide.LEFT
                val showRight = !single || ui.focused == PaneSide.RIGHT
                if (showLeft) {
                    // 复刻 MT 0x7f0c0033：**阴影只亮在活动窗格一侧**
                    //  - 左窗格活动 → shadow_left（0903A1）亮，画在左窗格右缘
                    //  - 右窗格活动 → shadow_right（0903A4）亮，画在右窗格左缘
                    Box(Modifier.weight(ui.splitRatio)) {
                        PaneView(
                            container = container,
                            side = PaneSide.LEFT,
                            pane = ui.left,
                            focused = ui.focused == PaneSide.LEFT,
                            // 操作前两侧路径栏**同时**高亮（源与目标都要看得见）
                            highlight = ui.highlight,
                            controller = controller,
                            modifier = Modifier.fillMaxSize(),
                            onRowAction = { rowAction = it },
                        )
                        PaneEdgeShadow(
                            active = ui.focused == PaneSide.LEFT,
                            isLeftPane = true,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                if (showLeft && showRight) {
                    // 可拖动分隔条（复刻 MT 0x7f0c0033 的 0900B2 Guideline + 090111/090112 1px 线；
                    // MT 的分隔线只有 1px，但触摸区给足 10dp 便于拖动）
                    Box(
                        Modifier
                            .width(10.dp)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = { controller.persistSplitRatio() },
                                    onDragCancel = { controller.persistSplitRatio() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    val total = panesWidthPx.coerceAtLeast(1f)
                                    controller.setSplitRatio(ui.splitRatio + dragAmount.x / total)
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
                        VDividerPx()
                    }
                }
                if (showRight) {
                    Box(Modifier.weight(1f - ui.splitRatio)) {
                        PaneView(
                            container = container,
                            side = PaneSide.RIGHT,
                            pane = ui.right,
                            focused = ui.focused == PaneSide.RIGHT,
                            highlight = ui.highlight,
                            controller = controller,
                            modifier = Modifier.fillMaxSize(),
                            onRowAction = { rowAction = it },
                        )
                        PaneEdgeShadow(
                            active = ui.focused == PaneSide.RIGHT,
                            isLeftPane = false,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
            }

            // ---------------- 任务条（只显示进行中 / 失败；完成、已取消立即消失，不再常驻）
            if (ui.tasks.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ui.tasks.take(2).forEach { snapshot -> TaskRow(snapshot, controller, onOpenTasks) }
                    val rest = ui.tasks.size - 2
                    if (rest > 0) {
                        Text(
                            "还有 $rest 个任务…（点此查看全部）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableNoRipple { onOpenTasks() }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }

            // ---------------- 底部：MT「选择当前目录」模式 / 多选工具栏 / 命令栏
            if (ui.pickDirFor != null) {
                // MT 0x7f0c0025：底栏上方浮出一个全宽按钮「选择当前目录」（090084）+ 取消
                val purpose = ui.pickDirFor!!
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(MtSpec.BottomBarHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "选择当前目录：" + middleEllipsis(focused.uri.displayPath.ifEmpty { "/" }, maxChars = 22),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val picked = controller.confirmPickDir()
                        if (picked != null) {
                            val (purposeNow, dir) = picked
                            when (purposeNow) {
                                PickDirPurpose.EXTRACT -> {
                                    // 两种来源：长按压缩包文件的「解压到文件夹…」 / 压缩包内选中项的解压
                                    val pending = controller.pendingArchiveExtract
                                    if (pending != null) {
                                        controller.clearPendingArchiveExtract()
                                        controller.extractArchiveTo(pending, dir)
                                    } else {
                                        controller.extractTo(focusSide, dir)
                                    }
                                }
                                PickDirPurpose.COPY_TO -> controller.copyTo(side = focusSide, dest = dir)
                                PickDirPurpose.MOVE_TO -> controller.moveTo(side = focusSide, dest = dir)
                            }
                        }
                    }) { Text(purpose.label) }
                    TextButton(onClick = { controller.cancelPickDir() }) { Text("取消") }
                }
            } else if (focused.hasSelection) {
                // MT：多选模式下底栏变成「全选 / 反选 / 类选 / …」动态按钮（0x7f11062b/632/633）
                val bottomExtraSel = settings.bottomBarPaddingDp.dp
                Column {
                    DividerPx()
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
                }
            } else {
                val bottomExtra = settings.bottomBarPaddingDp.dp
                Column {
                    // MT：底栏上方的 1px 分割线（`090110` / `09007D`）
                    DividerPx()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(MtSpec.BottomBarHeight + bottomExtra)
                            .padding(bottom = bottomExtra)
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {
                                // 底栏上滑 → 书签（MT 0x7f1107ca「从底部工具栏上滑即可打开书签」）。
                                // 文档 G.1.3 提醒：热区要在底栏上边缘之上、并与全面屏手势错开；
                                // 阈值取 MtGesture.SwipeBookmarkDp（32dp，文档 G.5），且本次手势只触发一次。
                                val threshold = MtGesture.SwipeBookmarkDp.dp.toPx()
                                var accumulated = 0f
                                var fired = false
                                detectVerticalDragGestures(
                                    onDragStart = { accumulated = 0f; fired = false },
                                    onDragEnd = { accumulated = 0f },
                                    onDragCancel = { accumulated = 0f },
                                ) { _, dragAmount ->
                                    accumulated += dragAmount
                                    if (!fired && accumulated < -threshold && settings.bookmarkSwipe) {
                                        fired = true
                                        onOpenBookmarks()
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        // MT 底栏：后退 / 前进 / 新建 / 同步 / 上级（§1.5 + G.3.3）
                        MtBottomIconButton(
                            icon = MtIcon.CHEVRON_L,
                            label = "后退",
                            enabled = focused.tab.back.isNotEmpty(),
                        ) { controller.back(focusSide) }
                        MtBottomIconButton(
                            icon = MtIcon.CHEVRON_R,
                            label = "前进",
                            enabled = focused.tab.forward.isNotEmpty(),
                        ) { controller.forward(focusSide) }
                        Box {
                            MtBottomIconButton(
                                icon = MtIcon.PLUS,
                                label = "新建（长按直接新建文件）",
                                onLongClick = { creatingFile = true },
                            ) { showCreateMenu = true }
                            // MT：新建（＋）弹出菜单
                            DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            MtVectorIcon(icon = MtIcon.FOLDER, size = 22.dp)
                                            Text("新建文件夹", modifier = Modifier.padding(start = 12.dp))
                                        }
                                    },
                                    onClick = { showCreateMenu = false; creatingFolder = true },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            MtVectorIcon(icon = MtIcon.FILE, size = 22.dp)
                                            Text("新建文件", modifier = Modifier.padding(start = 12.dp))
                                        }
                                    },
                                    onClick = { showCreateMenu = false; creatingFile = true },
                                )
                            }
                        }
                        // MT 底栏第 4 个按钮是「同步」（0x7f11069b）：点击 = 另一窗格跟随本窗格路径；
                        // 长按 = 过滤（MT 0x7f11028f「长按底部的「同步」按钮也可以进行过滤」）。
                        // ⚠️ 图标是 **swap_horiz（⇄）** 而不是刷新箭头 —— MT 截图实测，
                        // 语义是「把两个窗格同步成一样」= 两个相向的箭头（`0x7f0801f8`）。
                        MtBottomIconButton(
                            icon = MtIcon.SWAP,
                            label = "同步路径到另一窗口（长按过滤）",
                            onLongClick = { filterInput = true },
                        ) { controller.syncPath() }
                        // 压缩包内部也能「↑」（回到压缩包所在目录），与 PaneView 的 canGoUp 一致
                        MtBottomIconButton(
                            icon = MtIcon.UP,
                            label = "上级目录（长按输入路径）",
                            enabled = focused.uri.parent != null || focused.uri.scheme == "archive",
                            onLongClick = { gotoPath = true },
                        ) { controller.up(focusSide) }
                    }
                }
            }
        }
    }

    // ---------------- ⋮ 菜单
    //   顺序**逐条对照 MT 截图**（右半屏的菜单）：
    //     刷新 / 搜索 / 全选 / 过滤 / 排序方式 / 隐藏文件 ▶ / 添加书签 / 设为首页 /
    //     交换窗口 / 设置 / 退出
    //   PanelFM 的扩展项（粘贴 / 类型过滤 / 浏览模式 / 比较目录 / 网络初始路径 /
    //   压缩包专属）插在同语义位置，不改变 MT 的前几项顺序。
    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
        if (!hiddenSub) {
            MtMenuRow(MtIcon.SYNC, "刷新") { showMoreMenu = false; controller.refresh(focusSide) }
            MtMenuRow(MtIcon.SEARCH, "搜索") { showMoreMenu = false; showSearch = true }
            MtMenuRow(MtIcon.SELECT_ALL, "全选") { showMoreMenu = false; controller.selectAll(focusSide) }
            MtMenuRow(MtIcon.LOW_PRIORITY, "过滤") { showMoreMenu = false; filterInput = true }
            // 已过滤时给一个显式出口（不必再打开对话框清空）
            if (focused.filtered) {
                MtMenuRow(MtIcon.CLOSE, "清除过滤") {
                    showMoreMenu = false
                    controller.setSearch(focusSide, "")
                    controller.setFilter(focusSide, null)
                    controller.refresh(focusSide)
                    controller.showStatus("已清除过滤")
                }
            }
            MtMenuRow(MtIcon.SORT, "排序方式") { showMoreMenu = false; showSortDialog = true }
            // 隐藏文件 ▶（MT：带勾选态的子菜单）
            MtMenuRow(MtIcon.EYE_OFF, "隐藏文件", trailing = MtIcon.CHEVRON_R) { hiddenSub = true }
            MtMenuRow(MtIcon.BOOKMARK, "添加书签") { showMoreMenu = false; controller.addBookmark(focusSide) }
            MtMenuRow(MtIcon.HOME, "设为首页") { showMoreMenu = false; controller.setAsHome(focusSide) }
            MtMenuRow(MtIcon.SWAP, "交换窗口") {
                showMoreMenu = false
                controller.swapPanes()
                controller.showStatus("已交换窗口")
            }
            // ---- 以下是 PanelFM 的扩展项（MT 的 ⋮ 里没有，但语义上属于同一层）----
            MtMenuRow(MtIcon.KEYBOARD, "输入路径") { showMoreMenu = false; gotoPath = true }
            MtMenuRow(MtIcon.COPY, "复制到剪贴板") { showMoreMenu = false; controller.copySelectionToClipboard(focusSide) }
            if (controller.hasClipboard) {
                MtMenuRow(MtIcon.PASTE, "粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide) }
                MtMenuRow(MtIcon.CUT, "移动粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide, move = true) }
            }
            // MT 0x7f1104ab「已设置为该网络存储的初始路径」：把当前路径写回连接的初始路径
            if (focused.uri.scheme != "local" && focused.uri.scheme != "archive") {
                MtMenuRow(MtIcon.LOCATE, "设为该网络存储的初始路径") {
                    showMoreMenu = false
                    controller.setAsConnectionInitialPath(focusSide)
                }
            }
            MtMenuRow(MtIcon.SYNC, "同步（另一窗格跟随本窗格）") { showMoreMenu = false; controller.syncPath() }
            MtMenuRow(MtIcon.VIEW_SIDEBAR, "新建标签页") { showMoreMenu = false; controller.newTab(focusSide) }
            if (focused.uri.scheme == "archive") {
                // MT：压缩包内时，右上角菜单提供「测试压缩包完整性」与解压
                MtMenuRow(MtIcon.VERIFIED, "测试压缩包完整性") { showMoreMenu = false; controller.testArchive(focusSide) }
                MtMenuRow(MtIcon.ARCHIVE, "解压到对面窗格") {
                    showMoreMenu = false
                    controller.extractTo(focusSide, ui.pane(focusSide.other).uri)
                }
                MtMenuRow(MtIcon.FOLDER, "解压到压缩包所在目录") {
                    showMoreMenu = false
                    val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
                    val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                    val dir = host?.parent
                    if (dir != null) controller.extractTo(focusSide, dir)
                    else controller.showStatus("无法确定压缩包所在目录")
                }
                // MT 0x7f0c00ce「解压」对话框：三个单选（单独的文件夹 / 当前目录 / 文件夹…）+ 基于另一窗口路径
                MtMenuRow(MtIcon.ARCHIVE, "解压…") {
                    showMoreMenu = false
                    extractDirPicker = true
                }
                MtMenuRow(MtIcon.UPLOAD, "添加对面选中项到压缩包") { showMoreMenu = false; controller.addToArchive(focusSide) }
            }
            MtMenuRow(MtIcon.COMPARE, "比较两个目录") { showMoreMenu = false; controller.compareDirectories() }
            // 浏览模式（MT 0x7f1101fb/1fc/1fd/1fe：单列 / 双列 / 自动切换）
            Box {
                MtMenuRow(MtIcon.LAYERS, "浏览模式（${ui.effectiveBrowseMode.label}）", trailing = MtIcon.CHEVRON_R) { browseSub = true }
                DropdownMenu(expanded = browseSub, onDismissRequest = { browseSub = false }) {
                    BrowseMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    mode.label + if (mode == ui.browseMode) "  ✓" else "",
                                    color = if (mode == ui.browseMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { showMoreMenu = false; browseSub = false; controller.setBrowseMode(mode) },
                        )
                    }
                }
            }
            // 类型过滤（MT 的「过滤」下拉：文件夹 / 图片 / 视频 …）
            MtMenuRow(
                MtIcon.FILE,
                "类型过滤" + (focused.filterKind?.let { "（已过滤）" } ?: ""),
            ) { showMoreMenu = false; showTypeFilter = true }
            MtMenuRow(MtIcon.SETTINGS, "设置") { showMoreMenu = false; onOpenSettings() }
            MtMenuRow(MtIcon.EXIT, "退出") {
                showMoreMenu = false
                (context as? Activity)?.finishAffinity()
            }
        } else {
            // 隐藏文件 ▶ 子菜单（MT：带勾选态）
            MtMenuRow(MtIcon.CHEVRON_L, "隐藏文件") { hiddenSub = false }
            MtMenuRow(MtIcon.CHECK, "显示隐藏文件", checked = focused.showHidden) {
                showMoreMenu = false
                if (!focused.showHidden) controller.toggleHidden(focusSide)
            }
            MtMenuRow(MtIcon.CLOSE, "不显示隐藏文件", checked = !focused.showHidden) {
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
                add(MtAction("copy_to", crossPaneLabel("复制", focusSide), MtIcon.COPY, singleWindow = true))
                add(MtAction("move_to", crossPaneLabel("移动", focusSide), MtIcon.CUT, singleWindow = true))
                add(MtAction("delete", "删除", MtIcon.DELETE))
                add(MtAction("rename", "重命名", MtIcon.EDIT))
                add(MtAction("tools", "工具", MtIcon.BUILD))
                add(MtAction("compress", "压缩", MtIcon.ARCHIVE))
                if (twoFiles) add(MtAction("diff", "文件对比", MtIcon.COMPARE))
                add(MtAction("properties", "属性", MtIcon.INFO, enabled = multi <= 1))
                add(MtAction("share", "分享", MtIcon.SHARE, enabled = !anyDirectory))
                add(MtAction("open_with", "打开方式…", MtIcon.CHECK, enabled = !anyDirectory))
                add(MtAction("clipboard", "复制到剪贴板", MtIcon.PASTE))
                add(MtAction("bookmark", "添加书签", MtIcon.BOOKMARK))

                // ---- 按文件类型的二级菜单（MT 语义）：压缩包解压 / APK 安装等
                val inArchive = focused.uri.scheme == "archive"
                val single = picked.singleOrNull()
                if (single != null && !single.isDirectory) {
                    val kind = TypeActions.kindOf(single.extension)
                    val installable = single.uri.scheme == "local"
                    val ids = TypeActions.typedActionIds(
                        extension = single.extension,
                        isDirectory = false,
                        inArchive = inArchive,
                        apkInstallable = installable,
                    )
                    if (ids.isNotEmpty()) {
                        val section = "对「${single.name}」"
                        ids.forEach { id ->
                            val icon = when (id) {
                                TypeActions.ACTION_EXTRACT_HERE -> MtIcon.ARCHIVE
                                TypeActions.ACTION_EXTRACT_OWN_FOLDER -> MtIcon.FOLDER
                                TypeActions.ACTION_EXTRACT_PICK -> MtIcon.FOLDER
                                TypeActions.ACTION_BROWSE_ARCHIVE -> MtIcon.EXPLORE
                                TypeActions.ACTION_INSTALL -> MtIcon.GET_APP
                                TypeActions.ACTION_APK_INFO -> MtIcon.ANDROID
                                TypeActions.ACTION_EXTRACT_APK_ICON -> MtIcon.IMAGE
                                TypeActions.ACTION_OPEN_INTERNAL -> MtIcon.EYE
                                TypeActions.ACTION_EDIT_TEXT -> MtIcon.EDIT
                                else -> MtIcon.CHECK
                            }
                            add(MtAction(id, TypeActions.labelOf(id, kind), icon, section = section))
                        }
                    }
                }
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

                    // ---- 按类型的二级菜单（MT 语义）
                    TypeActions.ACTION_EXTRACT_HERE ->
                        controller.extractTo(focusSide, focused.uri)
                    TypeActions.ACTION_EXTRACT_OWN_FOLDER -> {
                        val parent = focused.uri.parent
                        if (parent != null) controller.extractToOwnFolder(focusSide, parent)
                    }
                    TypeActions.ACTION_EXTRACT_PICK -> {
                        extractDialogFor = item
                    }
                    TypeActions.ACTION_BROWSE_ARCHIVE -> {
                        controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE)
                    }
                    TypeActions.ACTION_INSTALL -> installApk(container, context, item) { msg ->
                        controller.showStatus(msg)
                    }
                    TypeActions.ACTION_APK_INFO -> {
                        controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.APK_INFO)
                    }
                    TypeActions.ACTION_EXTRACT_APK_ICON -> extractApkIcon(container, context, item) { msg ->
                        controller.showStatus(msg)
                    }
                    TypeActions.ACTION_OPEN_INTERNAL -> controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.AUTO)
                    TypeActions.ACTION_EDIT_TEXT -> controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.EDITOR)
                    TypeActions.ACTION_OPEN_WITH -> openWithFor = item
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
                MtAction("copy_path", "复制路径", MtIcon.COPY),
                MtAction("crc32", "校验值 CRC32", MtIcon.TAG),
                MtAction("md5", "校验值 MD5", MtIcon.TAG),
                MtAction("sha1", "校验值 SHA-1", MtIcon.TAG),
                MtAction("sha256", "校验值 SHA-256", MtIcon.TAG),
                MtAction("chmod", "修改权限", MtIcon.LOCK),
                MtAction(
                    "swap_name",
                    "交换文件名",
                    MtIcon.SWAP,
                    enabled = focused.selection.size == 2,
                ),
                MtAction("select_all", "全选", MtIcon.SELECT_ALL),
                MtAction("invert", "反选", MtIcon.SELECT_ALL),
                MtAction("same_type", "类选", MtIcon.LAYERS),
                MtAction("exit", "退出多选", MtIcon.CLOSE),
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
        // MT `app:recordKey="filter_record"`：过滤词带历史
        TextInputDialog(
            title = "过滤",
            initial = focused.search,
            label = "关键字",
            hint = "普通文本=包含；!文本=不包含；/正则；!/正则=正则否定。留空清除。",
            history = settings.inputHistory[PrefsStore.RecordKeys.FILTER].orEmpty(),
            // 允许留空提交 = 清除过滤（旧实现空文本不提交 → 过滤设上就取消不了）
            allowEmpty = true,
            onConfirm = { q ->
                controller.setSearch(focusSide, q)
                // 「清除」时把类型过滤也一并取消，否则列表仍被类型条件卡住
                if (q.isBlank() && focused.filterKind != null) controller.setFilter(focusSide, null)
                if (q.isNotBlank()) scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.FILTER, q) }
                controller.refresh(focusSide)
            },
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
    // v1.0 补：搜索结果上限询问（0x7f110430）、停止搜索（0x7f110686）、在当前结果中搜索（0x7f110619）
    if (showSearch) {
        MtSearchDialog(
            initialQuery = focused.search,
            history = settings.searchHistory,
            onSearch = { q, field, recursive, minSize, maxSize ->
                val hasSizeFilter = minSize >= 0 || maxSize >= 0
                when {
                    // 仅当前目录 + 无大小条件：等价于目录内过滤（沿用 /regex、!text 语法）
                    !recursive && !hasSizeFilter && field == SearchField.NAME -> controller.setSearch(focusSide, q)
                    !recursive && !hasSizeFilter && field == SearchField.REGEX -> controller.setSearch(focusSide, "/$q")
                    else -> {
                        searching = true
                        searchStopped = false
                        searchResults = emptyList()
                        searchJob?.cancel()
                        searchJob = scope.launch {
                            container.prefs.addSearchQuery(q)
                            // 注意：**不能用 runCatching 包 `searchTree`** —— 点「停止搜索」时
                            // `searchJob.cancel()` 会让它抛 CancellationException，而 runCatching
                            // 会把「取消」当成失败：弹一条「搜索失败：Job was cancelled」，
                            // 并把刚才的「已停止搜索（已找到 N 条）」顶掉。
                            val outcome = try {
                                controller.searchTree(
                                    side = focusSide,
                                    nameQuery = if (field == SearchField.CONTENT) "" else q,
                                    recursive = recursive,
                                    contentQuery = if (field == SearchField.CONTENT) q else "",
                                    minSize = minSize,
                                    maxSize = maxSize,
                                    nameRegex = field == SearchField.REGEX,
                                    // MT 0x7f110430：到 300 条先问「你确定继续搜索？」
                                    confirmEvery = 300,
                                    onAskContinue = { n ->
                                        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                        searchAsk = n to gate
                                        gate.await()
                                    },
                                    isCancelled = { !searching },
                                    onPartial = { partial -> searchResults = partial },
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                null        // 取消不是错误；「已停止搜索」已由 onStop 提示过
                            } catch (e: Exception) {
                                controller.showStatus((e as? VfsException)?.userMessage ?: "搜索失败：${e.message}")
                                null
                            }
                            searching = false
                            if (outcome != null) {
                                searchResults = outcome.items
                                searchStopped = outcome.stopped
                                if (outcome.stopped && outcome.items.size >= 300) {
                                    controller.showStatus("搜索结果数量过多，已停止搜索")
                                }
                            }
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
            stopped = searchStopped,
            onStop = {
                // MT 0x7f110686「停止搜索」
                searchJob?.cancel()
                searching = false
                searchStopped = true
                controller.showStatus("已停止搜索（已找到 ${results.size} 条）")
            },
            onRefine = {
                // MT 0x7f110619「在当前结果中搜索」：在现有结果里再筛（复用目录内过滤语法）
                refineInput = true
            },
            onClear = {
                searchResults = null
                searchStopped = false
                controller.showStatus("已清除搜索")
            },
            onPick = { item ->
                searchResults = null
                controller.reveal(focusSide, item.uri)
            },
            onDismiss = { searchResults = null },
        )
    }
    if (refineInput) {
        TextInputDialog(
            title = "在当前结果中搜索",
            initial = "",
            label = "关键字",
            hint = "在当前 ${searchResults?.size ?: 0} 条结果里再筛（支持 /正则、!否定）",
            onConfirm = { keyword ->
                val base = searchResults.orEmpty()
                searchResults = base.filter { controller.matchesSearch(it.name, keyword) }
                refineInput = false
            },
            onDismiss = { refineInput = false },
        )
    }
    // MT 0x7f110430「已搜索到 %s 个结果，你确定继续搜索？」（暂停搜索等回答）
    searchAsk?.let { (count, gate) ->
        AlertDialog(
            onDismissRequest = { searchAsk = null; gate.complete(false) },
            title = { Text("搜索结果较多") },
            text = { Text("已搜索到 $count 个结果，你确定继续搜索？") },
            confirmButton = {
                TextButton(onClick = { searchAsk = null; gate.complete(true) }) { Text("继续搜索") }
            },
            dismissButton = {
                TextButton(onClick = { searchAsk = null; gate.complete(false) }) { Text("停止搜索") }
            },
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
    //  · extractDirPicker = 在压缩包**内部**时打开（目标默认本目录）
    //  · extractDialogFor = 在文件列表里长按**压缩包本身**时打开（MT 的三项：当前目录 / 单独文件夹 / 文件夹…）
    if (extractDirPicker || extractDialogFor != null) {
        val pickedArchive = extractDialogFor
        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
        val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
        val archiveParent = pickedArchive?.uri?.parent ?: host?.parent
        val archiveName = pickedArchive?.name ?: host?.name ?: "压缩包"
        MtExtractDialog(
            archiveName = archiveName.substringBeforeLast('.', archiveName),
            currentDirPath = (pickedArchive?.uri ?: focused.uri).displayPath,
            otherPanePath = ui.pane(focusSide.other).uri.takeIf { it.scheme != "archive" }?.displayPath,
            onDismiss = { extractDirPicker = false; extractDialogFor = null },
            onConfirm = { target, customPath ->
                val archiveItem = pickedArchive
                extractDirPicker = false
                extractDialogFor = null
                if (archiveItem != null) {
                    // 长按文件列表里的压缩包：按三项语义解压该压缩包
                    when (target) {
                        ExtractTarget.OWN_FOLDER -> archiveItem.uri.parent?.let {
                            controller.extractArchiveTo(archiveItem, it, ownFolder = true)
                        } ?: controller.showStatus("无法确定压缩包所在目录")
                        ExtractTarget.HERE -> controller.extractArchiveTo(archiveItem, focused.uri)
                        ExtractTarget.PICK_FOLDER -> controller.startPickArchiveExtract(archiveItem)
                    }
                } else {
                    when (target) {
                        ExtractTarget.OWN_FOLDER ->
                            if (archiveParent != null) controller.extractToOwnFolder(focusSide, archiveParent)
                            else controller.showStatus("无法确定压缩包所在目录")
                        ExtractTarget.HERE -> controller.extractTo(focusSide, focused.uri)
                        ExtractTarget.PICK_FOLDER -> {
                            // MT 0x7f0c0025：进入「选择当前目录」模式，用户浏览到目标后点底栏的确认按钮
                            controller.startPickDir(PickDirPurpose.EXTRACT)
                        }
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
        // 组合期不查库（旧实现直接在参数里调 controller.defaultOpenMode → 每次重组查一次 SQLite）
        val openWithDefaultMode by produceState<com.u707t.panelfm.ui.preview.PreviewMode?>(
            initialValue = null,
            item.uri,
        ) {
            value = controller.defaultOpenModeSuspend(item)
        }
        val kind = MimeTypes.kindOf(item.extension)
        OpenWithDialog(
            fileName = item.name,
            mimeType = item.mimeType,
            localFile = item.uri.scheme == "local",
            options = listOf(
                OpenWithOption(PreviewMode.TEXT, available = kind == MimeTypes.Kind.TEXT || kind == MimeTypes.Kind.CODE || kind == MimeTypes.Kind.OTHER),
                OpenWithOption(PreviewMode.EDITOR, available = kind != MimeTypes.Kind.IMAGE && kind != MimeTypes.Kind.AUDIO && kind != MimeTypes.Kind.VIDEO),
                OpenWithOption(PreviewMode.IMAGE, available = kind == MimeTypes.Kind.IMAGE),
                OpenWithOption(PreviewMode.MEDIA, available = kind == MimeTypes.Kind.AUDIO || kind == MimeTypes.Kind.VIDEO),
                OpenWithOption(
                    PreviewMode.ARCHIVE,
                    available = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name) != null,
                ),
                OpenWithOption(PreviewMode.FONT, available = kind == MimeTypes.Kind.FONT),
                OpenWithOption(PreviewMode.PDF, available = kind == MimeTypes.Kind.PDF),
                OpenWithOption(PreviewMode.APK_INFO, available = kind == MimeTypes.Kind.APK),
                OpenWithOption(PreviewMode.OFFICE, available = kind == MimeTypes.Kind.DOCUMENT),
                OpenWithOption(PreviewMode.SYSTEM, available = item.uri.scheme == "local"),
            ),
            defaultMode = openWithDefaultMode,
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
        // 组合期不查库：进入对话框时异步读一次，增删后再刷新
        var entries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var reloadAt by remember { mutableStateOf(0) }
        LaunchedEffect(reloadAt) { entries = controller.openModesSuspend() }
        OpenWithManageDialog(
            entries = entries,
            onDelete = { ext -> controller.clearOpenMode(ext); reloadAt++ },
            onDismiss = { openWithManage = false },
        )
    }
    // 批量重命名（MT 表达式 + 查找替换；表达式/查找/替换三处都带 `recordKey` 历史）
    batchRenameFor?.let { items ->
        BatchRenameDialog(
            items = items,
            patternHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_PATTERN].orEmpty(),
            findHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_SEARCH].orEmpty(),
            replaceHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_REPLACE].orEmpty(),
            onConfirm = { expression, find, replace, useRegex ->
                scope.launch {
                    container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_PATTERN, expression)
                    if (find.isNotBlank()) container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_SEARCH, find)
                    if (replace.isNotBlank()) container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_REPLACE, replace)
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
        ConflictDialog(
            info = info,
            dialogIconMode = settings.dialogIconMode,
        ) { policy, applyAll -> controller.resolveConflict(policy, applyAll) }
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

/**
 * MT 顶栏动作条（复刻 `0x7f0c0034` 的 `09022F` HorizontalScrollView）：
 * 前三项硬编码为 **复制 / 移动 / 删除**（MT 原文），其余为 PanelFM 的扩展动作。
 * 源在左窗格 → `复制 ->`；源在右窗格 → `<- 复制`（箭头始终指向目标窗口）。
 */
@Composable
private fun TopActionItems(
    focused: PaneState,
    focusSide: PaneSide,
    controller: BrowserController,
    onDelete: (FileMetadata) -> Unit,
    onRename: (FileMetadata) -> Unit,
    onCompress: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit,
    onProperties: (FileMetadata) -> Unit,
    onShare: (FileMetadata) -> Unit,
    /** 按类型的二级动作（压缩包解压 / APK 安装 / APK 信息 / 内置查看） */
    onTypedAction: (String, FileMetadata) -> Unit = { _, _ -> },
) {
    val picked = focused.selectedItems
    val twoFiles = picked.size == 2 && picked.none { it.isDirectory }
    val anyDirectory = picked.any { it.isDirectory }
    val inArchive = focused.uri.scheme == "archive"
    // 单选时的类型化动作（与长按菜单同一套判定）
    val typedSingle = picked.singleOrNull()?.takeIf { !it.isDirectory }
    val typedIds = typedSingle?.let {
        TypeActions.typedActionIds(
            extension = it.extension,
            isDirectory = false,
            inArchive = inArchive,
            apkInstallable = it.uri.scheme == "local",
        )
    }.orEmpty()

    // MT 0x7f0c0034：前三项 = 复制 / 移动 / 删除（图标 22dp + 文字 14sp + 左右 padding 15dp）
    MtActionButton(MtIcon.COPY, crossPaneLabel("复制", focusSide)) { controller.copyToOther(focusSide) }
    MtActionButton(MtIcon.CUT, crossPaneLabel("移动", focusSide)) { controller.moveToOther(focusSide) }
    MtActionButton(MtIcon.DELETE, "删除", enabled = picked.isNotEmpty()) {
        picked.firstOrNull()?.let { item ->
            if (inArchive) controller.deleteInsideArchive(focusSide, picked) else onDelete(item)
        }
    }
    MtActionButton(MtIcon.EDIT, "重命名", enabled = picked.isNotEmpty()) {
        picked.firstOrNull()?.let(onRename)
    }
    MtActionButton(MtIcon.ARCHIVE, "压缩", enabled = picked.isNotEmpty() && !inArchive) {
        if (picked.isNotEmpty()) onCompress()
    }
    // ---- 按类型的动作（MT：选中的是压缩包就给「解压」，是 APK 就给「安装 / APK 信息」）
    if (typedSingle != null && typedIds.isNotEmpty()) {
        if (typedIds.contains(TypeActions.ACTION_EXTRACT_HERE)) {
            MtActionButton(MtIcon.ARCHIVE, "解压") { onTypedAction(TypeActions.ACTION_EXTRACT_HERE, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_INSTALL)) {
            MtActionButton(MtIcon.GET_APP, "安装") { onTypedAction(TypeActions.ACTION_INSTALL, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_APK_INFO)) {
            MtActionButton(MtIcon.ANDROID, "APK 信息") { onTypedAction(TypeActions.ACTION_APK_INFO, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_OPEN_INTERNAL)) {
            MtActionButton(MtIcon.EYE, "查看") { onTypedAction(TypeActions.ACTION_OPEN_INTERNAL, typedSingle) }
        }
    }
    MtActionButton(MtIcon.COMPARE, "文件对比", enabled = twoFiles) { controller.startFileDiff(focusSide) }
    // MT 0x7f0c0025「选择当前目录」：复制 / 移动的目标改成「浏览后确认」
    MtActionButton(MtIcon.FOLDER, "复制到…", enabled = !inArchive, onClick = onCopyTo)
    MtActionButton(MtIcon.FOLDER, "移动到…", enabled = !inArchive, onClick = onMoveTo)
    MtActionButton(MtIcon.PASTE, "复制到剪贴板") { controller.copySelectionToClipboard(focusSide) }
    MtActionButton(MtIcon.BOOKMARK, "添加书签") { controller.addBookmark(focusSide) }
    MtActionButton(MtIcon.INFO, "属性", enabled = picked.size == 1) {
        picked.firstOrNull()?.let(onProperties)
    }
    MtActionButton(MtIcon.SHARE, "分享", enabled = !anyDirectory && !inArchive) {
        picked.firstOrNull()?.let(onShare)
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
internal fun shareItem(    container: AppContainer,
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

/**
 * 任务条行 —— 只显示进行中 / 失败的任务（完成即从任务条消失，引擎稍后把列表项一起收走）。
 * 结构照 MT 的进度块简化：`操作 · 当前文件/状态` + 百分比 + 操作按钮，下面统计行与总进度。
 */
@Composable
private fun TaskRow(snapshot: TransferTaskSnapshot, controller: BrowserController, onOpenTasks: () -> Unit) {
    val state = snapshot.state
    val opLabel = if (snapshot.op == TransferOp.COPY) "复制" else "移动"
    val progress = snapshot.overallProgress()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(opLabel)
                    when (state) {
                        is TaskState.Running -> append(" · ${state.currentName.ifBlank { "…" }}")
                        is TaskState.Cancelling -> append(" · 正在取消…")
                        is TaskState.Paused -> append(" · 已暂停")
                        is TaskState.WaitingConflict -> append(" · 等待冲突处理")
                        is TaskState.Failed -> append(" · 失败")
                        TaskState.Queued -> append(" · 排队中")
                        else -> {}
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state is TaskState.Running) {
                Text(
                    "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (state) {
                is TaskState.Running, TaskState.Queued ->
                    TextButton(onClick = { controller.pauseTask(snapshot.id) }) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                TaskState.Paused ->
                    TextButton(onClick = { controller.resumeTask(snapshot.id) }) { Text("继续", style = MaterialTheme.typography.labelSmall) }
                else -> {}
            }
            when (state) {
                is TaskState.Failed, is TaskState.Done, TaskState.Cancelled ->
                    TextButton(onClick = { controller.removeTask(snapshot.id) }) { Text("移除", style = MaterialTheme.typography.labelSmall) }
                TaskState.Cancelling -> {} // 已在收尾，不再提供操作
                else ->
                    TextButton(onClick = { controller.cancelTask(snapshot.id) }) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = onOpenTasks) { Text("详情", style = MaterialTheme.typography.labelSmall) }
        }
        if (state is TaskState.Running) ThinProgressBar(progress)
        when (state) {
            is TaskState.Running -> Text(
                snapshot.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is TaskState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> {}
        }
    }
}

/**
 * 安装 APK（MT `0x7f110044`「安装」）。
 *
 * 只做「交给系统安装器」这一档（MT 还有 Shizuku / Root 两档，需要额外授权，
 * 本项目不引入）：本地文件走 FileProvider + `ACTION_VIEW(application/vnd.android.package-archive)`；
 * 网络文件提示先复制到本地（与「分享」一致的口径）。
 */
internal fun installApk(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (!item.name.lowercase().endsWith(".apk")) {
        onMessage("只有 APK 文件可以安装")
        return
    }
    if (item.uri.scheme != "local") {
        onMessage("网络 / 压缩包内的 APK 请先复制到本地再安装")
        return
    }
    val file = runCatching { java.io.File(container.localVfs.absolutePath(item.uri)) }.getOrNull()
    if (file == null || !file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        onMessage("无法生成安装链接")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { onMessage("没有可用的安装器") }
}

/**
 * 提取 APK 图标到同目录（MT `0x7f110248`「提取安装包」的轻量版：
 * 只取应用图标存成 PNG，命名 `<apk 名>-icon.png`，重名自动加序号）。
 */
internal fun extractApkIcon(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("请先复制到本地再提取图标")
        return
    }
    container.scope.launch {
        val result: Result<android.graphics.Bitmap> = runCatching {
            val file = java.io.File(container.localVfs.absolutePath(item.uri))
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException("无法解析 APK")
            val appInfo = info.applicationInfo ?: throw IllegalStateException("无法解析应用信息")
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            val drawable = appInfo.loadIcon(pm) ?: throw IllegalStateException("无图标")
            drawable.toBitmap(192, 192)
        }
        result.onSuccess { bitmap ->
            val out = runCatching {
                val parent = item.uri.parent ?: item.uri
                val base = item.name.substringBeforeLast('.', item.name) + "-icon"
                val target = container.uniqueChild(parent, "$base.png")
                val vfs = container.locator.find(target)
                    ?: throw IllegalStateException("目标存储不可用")
                val bos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                val bytes = bos.toByteArray()
                val w = vfs.openWrite(target, bytes.size.toLong(), 0L)
                try {
                    w.write(bytes, 0, bytes.size)
                    w.commit()
                } catch (e: Throwable) {
                    runCatching { w.abort() }
                    throw e
                }
                target.name
            }
            out.onSuccess { name ->
                onMessage("已提取图标：$name")
                container.browser.refreshAll()
            }.onFailure { onMessage("保存图标失败：${it.message}") }
        }.onFailure { onMessage("提取图标失败：${it.message}") }
    }
}
