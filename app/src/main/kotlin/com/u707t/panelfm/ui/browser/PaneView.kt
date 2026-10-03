package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata

/**
 * 单个窗格（MT 风格）：顶部一行紧凑信息（标签/路径/统计），下面是文件列表。
 * 行：实心文件夹图标 + 名称 + 「时间 · 大小」，多选时右侧出现对勾。
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

    Column(modifier.fillMaxSize()) {
        // ---- 窗格信息行（路径 + 统计 + 标签页）
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
                    text = pane.uri.displayPath.ifEmpty { "/" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                pane.items.isEmpty() -> EmptyState(
                    if (pane.filtered) "没有匹配的项" else "空目录",
                    if (pane.filtered) "试试清除搜索或过滤条件" else "长按多选，底部 ⇄ 可复制/移动到对面",
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(pane.items, key = { it.uri.toString() }) { item ->
                        MtFileRow(
                            item = item,
                            selected = pane.selection.contains(item.uri.toString()),
                            dimmed = !focused,
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
                            onMore = { controller.focus(side); onRowAction(item) },
                        )
                    }
                }
            }
        }
    }
}

/** MT 式文件行 */
@Composable
private fun MtFileRow(
    item: FileMetadata,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val alpha = if (dimmed) 0.62f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(name = item.name, isDirectory = item.isDirectory, size = 40.dp, )
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
