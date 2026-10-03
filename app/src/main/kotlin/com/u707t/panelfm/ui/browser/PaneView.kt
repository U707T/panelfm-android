package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlin.math.abs

private val ROW_HEIGHT = 56.dp

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
    val onDragStart: (Offset) -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
)

/**
 * 单个窗格（对齐 MT 管理器）：
 *  - 顶部一行：路径（中间省略）+ 统计
 *  - 列表首行 `..`；行高固定
 *  - **左右滑动任意项 = 进入多选**（继续滑过行间 = 连续区间选择，MT 同款）
 *  - **长按后松手 = MT 动作菜单**（带 ● 的项支持长按触发单窗口操作）；**长按后拖动 = 跨窗格拖拽**
 *  - 单击 = 打开（目录）/ 预览（文件）；多选状态下单击 = 切换选中
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
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
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

    Column(modifier.fillMaxSize()) {
        // ---- 窗格信息行
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        highlight -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
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
                        "${pane.selection.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pane.summary(),
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
        Box(
            Modifier
                .weight(1f)
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInRoot()
                    controller.setGeometry(
                        side,
                        PaneGeometry(
                            left = bounds.left,
                            top = bounds.top,
                            width = bounds.width,
                            height = bounds.height,
                            listTop = bounds.top,
                            rowHeightPx = rowHeightPx,
                            hasParentRow = canGoUp,
                            itemCount = pane.items.size,
                        )
                    )
                }
        ) {
            when {
                pane.loading && pane.items.isEmpty() -> LoadingState()
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
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            listTopRoot = b.top
                            controller.setGeometry(
                                side,
                                PaneGeometry(
                                    left = b.left,
                                    top = b.top,
                                    width = b.width,
                                    height = b.height,
                                    listTop = b.top,
                                    rowHeightPx = rowHeightPx,
                                    hasParentRow = canGoUp,
                                    itemCount = pane.items.size,
                                )
                            )
                        },
                ) {
                    if (canGoUp) {
                        item(key = "__parent__") { ParentRow(onClick = { controller.up(side) }) }
                    }
                    items(pane.items, key = { it.uri.toString() }) { item ->
                        MtFileRow(
                            container = container,
                            skipThumb = listState.isScrollInProgress,
                            item = item,
                            selected = pane.selection.contains(item.uri.toString()),
                            dimmed = !focused,
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
                                // MT：左右滑动 = 进入多选（该项单选；继续滑过行间变成区间选择）
                                controller.focus(side)
                                swipeAnchor = index
                                if (!pane.hasSelection) controller.startSelectionDrag(side, index)
                            },
                            onSweepTo = { index ->
                                val anchor = swipeAnchor
                                if (anchor >= 0 && index != anchor) controller.setSelectionRange(side, anchor, index)
                            },
                            onLongPress = {
                                // MT：长按（松手未拖动）= 动作菜单；该项自动进入选择
                                controller.focus(side)
                                if (!pane.hasSelection) controller.enterSelectionMode(side, item)
                                onRowAction(item)
                            },
                            onDragStart = { rootPosition ->
                                val sources = if (pane.hasSelection) pane.selectedItems.map { it.uri }
                                else listOf(item.uri)
                                controller.startDrag(
                                    side = side,
                                    sources = sources,
                                    label = pane.uri.displayPath,
                                    x = rootPosition.x,
                                    y = rootPosition.y,
                                )
                            },
                            onDrag = { x, y -> controller.updateDrag(x, y) },
                            onDragEnd = { controller.endDrag() },
                            onDragCancel = { controller.cancelDrag() },
                        )
                    }
                }
            }
        }
    }
}

/** `..` 返回上级（MT 列表首行） */
@Composable
private fun ParentRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onClick)
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
    indexAtRoot: (Float) -> Int,
    onTap: () -> Unit,
    onSwipeSelect: (Int) -> Unit,
    onSweepTo: (Int) -> Unit,
    onLongPress: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val alpha = if (dimmed) 0.62f else 1f
    val thumb = rememberThumb(container, item, targetPx = 96, skip = skipThumb)
    val haptic = LocalHapticFeedback.current
    // 行在根坐标系中的位置（滑动选择 / 拖拽落点判定需要绝对坐标）
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val gestures by rememberUpdatedState(
        RowGestures(
            rowTop = { rootOffset },
            indexAtRoot = indexAtRoot,
            onTap = onTap,
            onSwipeSelect = onSwipeSelect,
            onSweepTo = onSweepTo,
            onLongPress = onLongPress,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
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
            //   · 左右滑动          = 进入多选；滑过行间 = 连续区间选择（替换语义）
            //   · 长按后松手        = 动作菜单（带 ● 的项可长按触发单窗口操作）
            //   · 长按后拖动        = 跨窗格拖拽（落在对面行 = 复制，落在对面空白 = 复制到该目录）
            //   · 纵向拖动（未越阈值）= 交给列表滚动（不消费事件）
            // ------------------------------------------------------------------
            .pointerInput(item.uri.toString()) {
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val downTime = down.uptimeMillis
                    val downIndex = gestures.indexAtRoot(gestures.rowTop().y + downPos.y)
                    var mode = RowGestureMode.UNDECIDED
                    var dragStarted = false
                    var dragEnded = false
                    var dragPos = Offset.Zero
                    var lastIndex = downIndex

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val pos = change.position
                            val dx = pos.x - downPos.x
                            val dy = pos.y - downPos.y
                            val elapsed = change.uptimeMillis - downTime

                            if (mode == RowGestureMode.UNDECIDED) {
                                when {
                                    // 松手：短按 = 点击；超过长按阈值 = 动作菜单
                                    !change.pressed -> {
                                        if (elapsed >= longPressTimeout) gestures.onLongPress() else gestures.onTap()
                                        change.consume()
                                        break
                                    }
                                    // 长按阈值到：进入「长按」态（震动提示；继续看是拖动还是松手）
                                    elapsed >= longPressTimeout -> {
                                        mode = RowGestureMode.LONG_PRESS
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    // 横向为主且越过阈值 → 进入滑动多选
                                    downIndex >= 0 && abs(dx) > touchSlop && abs(dx) > abs(dy) -> {
                                        mode = RowGestureMode.SWEEP
                                        gestures.onSwipeSelect(downIndex)
                                        change.consume()
                                    }
                                    // 纵向为主 → 列表滚动，不消费事件
                                    abs(dy) > touchSlop && abs(dy) >= abs(dx) -> break
                                }
                            }

                            when (mode) {
                                RowGestureMode.LONG_PRESS -> {
                                    if (!change.pressed) {
                                        if (dragStarted) {
                                            dragEnded = true
                                            gestures.onDragEnd()
                                        } else {
                                            gestures.onLongPress()
                                        }
                                        change.consume()
                                        break
                                    }
                                    if (!dragStarted && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                        dragStarted = true
                                        dragPos = Offset(gestures.rowTop().x + pos.x, gestures.rowTop().y + pos.y)
                                        gestures.onDragStart(dragPos)
                                    }
                                    if (dragStarted) {
                                        dragPos += change.positionChange()
                                        gestures.onDrag(dragPos.x, dragPos.y)
                                        change.consume()
                                    }
                                }
                                RowGestureMode.SWEEP -> {
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
                    } finally {
                        // 行被回收 / 手势被取消：兜底取消拖拽，避免拖拽幽灵卡住
                        if (dragStarted && !dragEnded) gestures.onDragCancel()
                    }
                }
            }
            .onGloballyPositioned { coords -> rootOffset = coords.boundsInRoot().topLeft }
            .semantics {
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
