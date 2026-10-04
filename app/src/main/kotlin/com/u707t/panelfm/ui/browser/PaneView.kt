package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlin.math.abs

private val ROW_HEIGHT = 56.dp

/** 右滑进入多选的最小距离（超过系统 touchSlop，保证「滑动一段距离才触发」） */
private val SWIPE_ENTRY = 24.dp

/** 右滑「到底」呼出更多操作（MT 0x7f110697「右滑列表项可进行更多操作」）的距离 */
private val SWIPE_MENU = 96.dp

/** 行手势的判定阶段 */
private enum class RowGestureMode { UNDECIDED, SWEEP, LONG_PRESS }

/**
 * 行手势回调集合（用 [rememberUpdatedState] 包裹后交给 pointerInput，
 * 避免长手势过程中捕获到过期的 lambda / 布局坐标）。
 */
private class RowGestures(
    val rowTop: () -> Offset,
    val indexAtRoot: (Float) -> Int,
    val onTap: () -> Unit,
    val onSwipeSelect: (Int) -> Unit,
    val onSweepTo: (Int) -> Unit,
    val onLongPress: () -> Unit,
    val onSwipeMenu: (Int) -> Unit,
)

/**
 * 单个窗格（对齐 MT 管理器）：
 *  - 顶部一行：路径（中间省略）+ 统计
 *  - 列表首行 `..`；行高固定
 *  - **左右滑动一段距离（≥ 24dp 且横向占优）= 进入多选**（MT 0x7f1106f3）；继续滑过行间 = 区间选择（替换语义）
 *  - **右滑到底（≥ 96dp）= 呼出更多操作**（MT 0x7f110697「右滑列表项可进行更多操作」）
 *  - **长按后松手 = MT 动作菜单**（该项自动选中；带 ● 的项支持长按触发单窗口操作）
 *  - 单击 = 打开（目录）/ 预览（文件）；多选状态下单击 = 切换选中
 *  - 任何触摸都会先把本窗格设为活动窗口（同一时间只有一个窗口激活）
 *  - 跨窗格操作用动作菜单「复制 -> / 移动 ->」或底栏 `⇄`（长按拖动跨窗格已按需求移除）
 */
@Composable
fun PaneView(
    container: com.u707t.panelfm.AppContainer,
    side: PaneSide,
    pane: PaneState,
    focused: Boolean,
    highlight: Boolean,
    controller: BrowserController,
    modifier: Modifier = Modifier,
    onRowAction: (FileMetadata) -> Unit,
) {
    val listState = rememberLazyListState()
    // 剪贴板里有没有内容（决定粘贴 FAB 是否出现）
    val clipboardReady = controller.hasClipboard
    val canGoUp = pane.uri.parent != null || pane.uri.scheme == "archive"

    /** 列表在根坐标系中的顶部（把行内局部坐标换算成列表坐标） */
    var listTopRoot by remember { mutableStateOf(0f) }

    /** 手指 Y（根坐标）→ 列表项下标（-1 = 未命中） */
    fun indexAtRoot(rootY: Float): Int {
        val y = rootY - listTopRoot
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y <= it.offset + it.size }
            ?: return -1
        val logical = info.index - (if (canGoUp) 1 else 0)
        return if (logical in pane.items.indices) logical else -1
    }

    /** 本次滑动选择的锚点（按下的那一行）；-1 = 未开始 */
    var swipeAnchor by remember { mutableStateOf(-1) }

    // 定位到指定项（搜索结果点进来 / 「打开所在目录」）：列表就绪后滚动到该项并清空请求
    LaunchedEffect(pane.scrollToUri, pane.items) {
        val target = pane.scrollToUri ?: return@LaunchedEffect
        val index = pane.items.indexOfFirst { it.uri.toString() == target }
        if (index >= 0) {
            val listIndex = index + (if (canGoUp) 1 else 0)
            listState.animateScrollToItem(listIndex.coerceAtLeast(0))
        }
        controller.consumeScrollTo(side)
    }

    Column(
        modifier
            .fillMaxSize()
            // 任何触摸都先激活本窗格（同一时间只有一个窗口是活动窗口；不消费事件、不影响子组件）
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    controller.focus(side)
                }
            },
    ) {
        // ---- 标签页条（MT：同窗格多标签时显示；单标签不占地方）
        // 标签数据与开关早就在控制器里（newTab / switchTab / closeTab），此前没有任何入口，
        // 属于「功能有、界面缺」——这里补齐：点击切换、✕ 关闭、＋ 新建。
        if (pane.tabs.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pane.tabs.forEachIndexed { index, tab ->
                    val active = index == pane.activeTab
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent
                            )
                            .clickableNoRipple { controller.switchTab(side, index) }
                            .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                            .semantics {
                                role = Role.Tab
                                contentDescription = "标签页 ${tabLabel(tab)}" + if (active) "，当前" else ""
                                onClick(label = "切换") { controller.switchTab(side, index); true }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tabLabel(tab),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 88.dp),
                        )
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickableNoRipple { controller.closeTab(side, index) }
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "关闭标签页 ${tabLabel(tab)}"
                                },
                        )
                    }
                }
                Text(
                    "＋",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickableNoRipple { controller.newTab(side) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "新建标签页"
                        },
                )
            }
        }

        // ---- 窗格信息行
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        highlight -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    middleEllipsis(pane.uri.displayPath.ifEmpty { "/" }),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
                if (pane.hasSelection) {
                    Text(
                        "已选: ${pane.selection.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pane.summaryFor(container.settings.value.listDisplayMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                pane.space?.let {
                    Text(
                        "${(if (it.total > 0) (it.total - it.free) * 100 / it.total else 0)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 列表
        Box(Modifier.weight(1f)) {
            when {
                // 首次加载（无任何内容）：底层留空，由遮罩层（转圈 + 取消 + 百分比）覆盖
                pane.loading && pane.items.isEmpty() -> Unit
                pane.error != null -> ErrorState(
                    message = pane.error,
                    actionLabel = "重试",
                    onAction = { controller.refresh(side) },
                )
                pane.items.isEmpty() && !canGoUp -> EmptyState(
                    if (pane.filtered) "没有匹配的项" else "空目录",
                    if (pane.filtered) "试试清除搜索或过滤条件" else "底部 ＋ 新建，或 ⇄ 从对面复制进来",
                )
                else -> LazyColumn(
                    state = listState,
                    // 加载中禁止滚动（MT 的遮罩会吞掉全部触摸；这里用开关保证「确定性」，
                    // 不依赖 Compose 指针分发顺序）
                    userScrollEnabled = !pane.loading,
                    modifier = Modifier
                        .fillMaxSize()
                        // 列表顶部（根坐标）：把触摸位置换算成行下标（滑动多选用）
                        .onGloballyPositioned { coords -> listTopRoot = coords.boundsInRoot().top },
                ) {
                    if (canGoUp) {
                        item(key = "__parent__") { ParentRow(onClick = { controller.up(side) }) }
                    }
                    items(
                        pane.items,
                        key = { it.uri.toString() },
                        contentType = { if (it.isDirectory) "dir" else "file" },
                    ) { item ->
                        MtFileRow(
                            container = container,
                            skipThumb = listState.isScrollInProgress && container.settings.value.skipThumbsWhileScrolling,
                            item = item,
                            selected = pane.selection.contains(item.uri.toString()),
                            dimmed = !focused,
                            // 加载中（遮罩可见）时行手势整体关闭：避免遮罩期间误开文件 / 误多选
                            gesturesEnabled = !pane.loading,
                            indexAtRoot = { rootY -> indexAtRoot(rootY) },
                            onTap = {
                                // MT：多选态单击 = 切换选中；否则单击 = 打开 / 预览
                                if (pane.hasSelection) controller.toggleSelection(side, item.uri)
                                else {
                                    controller.focus(side)
                                    controller.openItem(side, item)
                                }
                            },
                            onSwipeSelect = { index ->
                                // MT：右滑 = 进入多选（该项单选）；已有多选时右滑该行 = 加选该行
                                controller.focus(side)
                                swipeAnchor = index
                                val item = pane.items.getOrNull(index)
                                if (item != null) {
                                    if (!pane.hasSelection) controller.startSelectionDrag(side, index)
                                    else controller.addToSelection(side, item.uri)
                                }
                            },
                            onSweepTo = { index ->
                                val anchor = swipeAnchor
                                if (anchor >= 0 && index != anchor) controller.setSelectionRange(side, anchor, index)
                            },
                            onLongPress = {
                                // MT：长按松手 = 动作菜单；该项自动进入选择。
                                // 若上一次长按留下了锚点且本次是**另一项**，则先做「连选区间」
                                // （MT 0x7f110631「可通过分别长按两个项目来进行连选」）。
                                controller.longPressSelect(side, item)
                                onRowAction(item)
                            },
                            // MT 0x7f110697「右滑列表项可进行更多操作」：右滑到底 → 该项进入选择并弹动作菜单
                            onSwipeMenu = { index ->
                                controller.focus(side)
                                swipeAnchor = -1
                                val target = pane.items.getOrNull(index) ?: item
                                // 已有多选且包含该项 → 保留多选（菜单作用于整个选择集）；
                                // 否则只选该项（与长按菜单语义一致）。
                                if (!pane.selection.contains(target.uri.toString())) {
                                    controller.enterSelectionMode(side, target)
                                }
                                onRowAction(target)
                            },
                        )
                    }
                }
            }
        

            // ---- 每窗格 FAB（复刻 MT 0x7f0c0033 的 090166/09016A）：
            //   剪贴板（粘贴，bottom|end 12dp）/ 取消（✕，bottom|end 74dp；多选态才出现）
            if (clipboardReady) {
                SmallFab(
                    icon = "📋",
                    contentDesc = "粘贴剪贴板中的项到当前目录",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                ) { controller.pasteFromClipboard(side) }
            }
            if (pane.hasSelection) {
                SmallFab(
                    icon = "✕",
                    contentDesc = "取消选择",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 74.dp),
                ) { controller.clearSelection(side) }
            }

            // ---- MT 加载遮罩（复刻 0x7f0c0033 的 09020D/09020E）：
            //   #66222222 半透明黑 + 转圈 + 「取消」按钮 + 10sp 百分比文字；
            //   刷新已有内容时也盖一层（可取消），与 MT 的大目录/网络目录加载一致。
            //   本地小目录瞬间加载完 → 延迟 160ms 再显示，避免每次进目录都闪一下。
            var overlayVisible by remember { mutableStateOf(false) }
            LaunchedEffect(pane.loading) {
                if (pane.loading) {
                    kotlinx.coroutines.delay(160)
                    overlayVisible = true
                } else {
                    overlayVisible = false
                }
            }
            if (pane.loading && overlayVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66222222))
                        // MT 的遮罩是 clickable + focusable：吞掉**全部**触摸（点击与滚动），
                        // 避免加载中误操作下层列表 / 误触 FAB（已被子组件消费的事件不动，保证「取消」可点）
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { if (!it.isConsumed) it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(42.dp),
                                strokeWidth = 3.dp,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                            Text(
                                pane.loadProgress?.let { "${(it * 100).toInt()}%" } ?: "",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                        TextButton(
                            onClick = { controller.cancelLoad(side) },
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Text("取消", color = Color.White.copy(alpha = 0.9f))
                        }
                    }
                }
            }
        }
    }
}

/** `..` 返回上级（MT 列表首行） */
@Composable
private fun ParentRow(onClick: () -> Unit) {
    val tapAction = onClick
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "返回上级目录"
                role = Role.Button
                onClick(label = "返回上级") { tapAction(); true }
            }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(
            name = "",
            isDirectory = true,
            size = 40.dp,
            folderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "..",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun MtFileRow(
    container: com.u707t.panelfm.AppContainer,
    skipThumb: Boolean,
    item: FileMetadata,
    selected: Boolean,
    dimmed: Boolean,
    gesturesEnabled: Boolean = true,
    indexAtRoot: (Float) -> Int,
    onTap: () -> Unit,
    onSwipeSelect: (Int) -> Unit,
    onSweepTo: (Int) -> Unit,
    onLongPress: () -> Unit,
    onSwipeMenu: (Int) -> Unit,
) {
    val alpha = if (dimmed) 0.55f else 1f
    val swipeEntrySlop = with(LocalDensity.current) { SWIPE_ENTRY.toPx() }
    val swipeMenuSlop = with(LocalDensity.current) { SWIPE_MENU.toPx() }
    val thumb = rememberThumb(container, item, targetPx = 96, skip = skipThumb)
    val haptic = LocalHapticFeedback.current
    // 行在根坐标系中的位置（滑动选择的坐标换算需要绝对坐标）
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val gestures by rememberUpdatedState(
        RowGestures(
            rowTop = { rootOffset },
            indexAtRoot = indexAtRoot,
            onTap = onTap,
            onSwipeSelect = onSwipeSelect,
            onSweepTo = onSweepTo,
            onLongPress = onLongPress,
            onSwipeMenu = onSwipeMenu,
        )
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
            // ------------------------------------------------------------------
            // 行手势（MT 语义，统一由单个识别器处理，避免多个识别器互相抢事件）：
            //   · 单击              = 打开 / 预览（多选态 = 切换选中）
            //   · 向右滑动 ≥ 24dp    = 进入多选（震动确认）；继续滑过行间 = 区间选择（替换语义）
            //   · 长按后松手        = 动作菜单（该项自动选中；带 ● 的项可长按触发单窗口操作）
            //   · 纵向拖动          = 交给列表滚动（不消费事件）
            //   · 小幅度拖动后松手  = 不触发点击（避免滑动误开文件）
            //   （跨窗格复制用动作菜单「复制 -> / 移动 ->」或 ⇄；长按拖动已按需求移除）
            // ------------------------------------------------------------------
            .pointerInput(item.uri.toString(), gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val entrySlop = swipeEntrySlop
                val menuSlop = swipeMenuSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val downTime = down.uptimeMillis
                    val downIndex = gestures.indexAtRoot(gestures.rowTop().y + downPos.y)
                    var mode = RowGestureMode.UNDECIDED
                    var lastIndex = downIndex
                    /** 右滑到底呼出菜单：只触发一次 */
                    var menuFired = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = change.position
                        val dx = pos.x - downPos.x
                        val dy = pos.y - downPos.y
                        val elapsed = change.uptimeMillis - downTime

                        if (mode == RowGestureMode.UNDECIDED) {
                            when {
                                // 松手：真正的轻点 = 点击；长按 = 动作菜单；拖过一段距离后松手 = 什么都不做
                                !change.pressed -> {
                                    val dragged = abs(dx) > touchSlop || abs(dy) > touchSlop
                                    when {
                                        elapsed >= longPressTimeout -> gestures.onLongPress()
                                        !dragged -> gestures.onTap()
                                    }
                                    change.consume()
                                    break
                                }
                                // 长按阈值到：进入「长按」态（震动提示；松手弹动作菜单）
                                elapsed >= longPressTimeout -> {
                                    mode = RowGestureMode.LONG_PRESS
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                // 纵向为主 → 列表滚动，不消费事件（先判断，避免斜向滚动被误判成滑动选择）
                                abs(dy) > touchSlop && abs(dy) >= abs(dx) -> break
                                // 左右滑动一段距离（≥ 24dp 且横向占优）→ 进入多选
                                // （MT 0x7f1106f3「左右滑动文件可直接选择」：两个方向都可进入选择）
                                downIndex >= 0 && abs(dx) > entrySlop && abs(dx) > abs(dy) -> {
                                    mode = RowGestureMode.SWEEP
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    gestures.onSwipeSelect(downIndex)
                                    change.consume()
                                }
                            }
                        }

                        when (mode) {
                            RowGestureMode.LONG_PRESS -> {
                                // 长按态：等待松手后弹出动作菜单（不做拖拽）
                                change.consume()
                                if (!change.pressed) {
                                    gestures.onLongPress()
                                    break
                                }
                            }
                            RowGestureMode.SWEEP -> {
                                // MT 0x7f110697「右滑列表项可进行更多操作」：继续右滑到底 → 呼出动作菜单。
                                // 要求「横向位移足够大 + 纵向位移仍小」（避免向下扫选区间时误弹菜单）。
                                if (!menuFired && dx > menuSlop && dx > abs(dy) * 2f && abs(dy) < menuSlop) {
                                    menuFired = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    gestures.onSwipeMenu(downIndex)
                                    change.consume()
                                    break
                                }
                                val index = gestures.indexAtRoot(gestures.rowTop().y + pos.y)
                                if (index >= 0 && index != lastIndex) {
                                    lastIndex = index
                                    gestures.onSweepTo(index)
                                }
                                change.consume()
                                if (!change.pressed) break
                            }
                            RowGestureMode.UNDECIDED -> Unit
                        }
                    }
                }
            }
            .onGloballyPositioned { coords -> rootOffset = coords.boundsInRoot().topLeft }
            // 无障碍：整行合并为一条朗读（名称 / 类型 / 大小 / 修改时间 + 已选中状态）
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(item.name)
                    append(if (item.isDirectory) "，文件夹" else "，文件")
                    if (!item.isDirectory && item.size >= 0) append("，${Fmt.size(item.size)}")
                    val t = Fmt.time(item.lastModified)
                    if (t.isNotBlank()) append("，修改于 $t")
                }
                if (!gesturesEnabled) {
                    // 加载中：整行不可操作（与手势关闭保持一致）
                    stateDescription = "正在加载"
                    return@clearAndSetSemantics
                }
                if (selected) stateDescription = "已选中"
                onClick(label = "打开") { onTap(); true }
                onLongClick(label = "操作菜单") { onLongPress(); true }
            }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            FileIcon(
                name = item.name,
                isDirectory = item.isDirectory,
                size = 40.dp,
                alpha = alpha,
                folderColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = (if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = alpha),
            )
            Text(
                buildString {
                    val t = Fmt.time(item.lastModified)
                    if (t.isNotBlank()) append(t)
                    if (!item.isDirectory && item.size >= 0) {
                        if (isNotEmpty()) append("  ·  ")
                        append(Fmt.size(item.size))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 1,
            )
        }
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 路径中间省略（MT 的 `/storage/emula.../0/Download/` 观感） */
fun middleEllipsis(path: String, maxChars: Int = 34): String {
    if (path.length <= maxChars) return path
    val head = 6
    val tail = maxChars - head - 3
    return path.take(head) + "..." + path.takeLast(tail)
}

/** 标签页显示名：优先连接/卷名，其次路径末级 */
private fun tabLabel(tab: PaneTab): String =
    tab.label.ifBlank { tab.uri.name.ifBlank { "/" } }

/** 无涟漪点击 */
@Composable
fun Modifier.clickableNoRipple(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}


/** MT 风格的窗格内小悬浮按钮（50dp / 图标 20dp，与 MT 的 fabCustomSize 一致） */
@Composable
private fun SmallFab(
    icon: String,
    contentDesc: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.clearAndSetSemantics {
            this.contentDescription = contentDesc
            role = Role.Button
            onClick(label = contentDesc) { onClick(); true }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(icon, style = MaterialTheme.typography.titleSmall)
    }
}
