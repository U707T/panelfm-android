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
import androidx.compose.material3.CircularProgressIndicator
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
 *  - 列表首行 `..`，行高固定；**左右滑动任意项 = 进入多选并选中该行**（再滑动另一项 = 连选闭区间）
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
    val scope = rememberCoroutineScope()

    val ds = remember { BrowserDialogsState() }
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
            BrowserTopBar(container, controller, ui, ds, onOpenDrawer = { scope.launch { drawerState.open() } })

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
                            onRowAction = { ds.rowAction = it },
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
                            onRowAction = { ds.rowAction = it },
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

            // ---------------- 长操作状态条（审计 U4：压缩 / 校验 / 对比 / 完整性测试 / 远程包下载）
            // 不可消失（没有关闭按钮），一律带「取消」；点过取消后转为「正在取消…」直到作业收尾。
            ui.busy?.let { op ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (op.progress == null) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            op.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        op.progress?.let {
                            Text(
                                "${(it * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (op.cancellable) {
                            TextButton(onClick = { controller.cancelBusy() }) {
                                Text("取消", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Text(
                                "正在取消…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                    op.progress?.let { ThinProgressBar(it) }
                    op.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            BrowserBottomBar(container, controller, ui, ds, onOpenBookmarks = onOpenBookmarks)
        }
    }

    BrowserMoreMenu(controller, ui, ds, onOpenSettings = onOpenSettings)

    BrowserActionSheets(container, controller, ui, ds)

    BrowserDialogHost(container, controller, ui, ds)
}

