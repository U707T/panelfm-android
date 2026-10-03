package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlin.math.abs

/**
 * 单个窗格（对齐 MT 管理器）：
 *  - 顶部一行：路径 + 统计（聚焦窗格高亮）
 *  - 列表首行是 `..`（返回上级）
 *  - 行高固定 56dp；**左右滑动任意文件即进入多选**（MT 手册）
 *  - 多选状态下，手指在行间滑动 = 连续选中（区间选择）
 */
@Composable
fun PaneView(
    side: PaneSide,
    pane: PaneState,
    focused: Boolean,
    highlight: Boolean,
    controller: BrowserController,
    modifier: Modifier = Modifier,
    onRowAction: (FileMetadata) -> Unit,
) {
    val listState = rememberLazyListState()
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { ROW_HEIGHT.toPx() }
    val canGoUp = pane.uri.parent != null || pane.uri.scheme == "archive"

    /** 手指 Y 坐标 → 列表项下标（-1 表示未命中；父目录行算 0） */
    fun indexAt(y: Float): Int {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y <= it.offset + it.size }
            ?: return -1
        val logical = info.index - (if (canGoUp) 1 else 0)
        return if (logical in pane.items.indices) logical else -1
    }

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
                    text = middleEllipsis(pane.uri.displayPath.ifEmpty { "/" }),
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
                if (pane.tabs.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        pane.tabs.forEachIndexed { index, _ ->
                            Box(
                                Modifier
                                    .size(if (index == pane.activeTab) 8.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (index == pane.activeTab) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                                    .clickableNoRipple { controller.switchTab(side, index) }
                            )
                        }
                    }
                }
            }
        }

        // ---- 列表
        Box(Modifier.weight(1f)) {
            when {
                pane.loading && pane.items.isEmpty() -> LoadingState()
                pane.error != null -> ErrorState(
                    message = pane.error,
                    actionLabel = "重试",
                    onAction = { controller.refresh(side) },
                )
                pane.items.isEmpty() && !canGoUp -> com.u707t.panelfm.core.ui.EmptyState(
                    if (pane.filtered) "没有匹配的项" else "空目录",
                    if (pane.filtered) "试试清除搜索或过滤条件" else "底部 ＋ 新建，或 ⇄ 从对面复制进来",
                )
                else -> Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (pane.hasSelection) {
                                // 多选状态下：手指滑过即连续选中（MT 的区间选择）
                                Modifier.pointerInput(pane.items.size, canGoUp) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val anchor = indexAt(down.position.y)
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) break
                                                val idx = indexAt(change.position.y)
                                                if (anchor >= 0 && idx >= 0) controller.selectRange(side, anchor, idx)
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (canGoUp) {
                            item(key = "__parent__") {
                                ParentRow(onClick = { controller.up(side) })
                            }
                        }
                        items(pane.items, key = { it.uri.toString() }) { item ->
                            MtFileRow(
                                item = item,
                                selected = pane.selection.contains(item.uri.toString()),
                                dimmed = !focused,
                                onSwipeSelect = {
                                    controller.focus(side)
                                    controller.toggleSelection(side, item.uri)
                                },
                                onClick = {
                                    if (pane.hasSelection) controller.toggleSelection(side, item.uri)
                                    else {
                                        controller.focus(side)
                                        controller.openItem(side, item)
                                    }
                                },
                                onLongClick = {
                                    controller.focus(side)
                                    if (!pane.hasSelection) controller.enterSelectionMode(side, item)
                                    else controller.toggleSelection(side, item.uri)
                                },
                                onMore = {
                                    controller.focus(side)
                                    onRowAction(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ROW_HEIGHT = 56.dp

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
        FileIcon(name = "", isDirectory = true, size = 40.dp)
        Text(
            "..",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MtFileRow(
    item: FileMetadata,
    selected: Boolean,
    dimmed: Boolean,
    onSwipeSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val alpha = if (dimmed) 0.62f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(item.uri.toString()) {
                // 左右滑动任意文件 → 进入多选（MT 手册）
                detectHorizontalDragGestures { change, dragAmount ->
                    if (abs(dragAmount) > 4f) {
                        onSwipeSelect()
                        change.consume()
                    }
                }
            }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(name = item.name, isDirectory = item.isDirectory, size = 40.dp, alpha = alpha)
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
