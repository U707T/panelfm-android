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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata

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
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        // ---- 标签页
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pane.tabs.forEachIndexed { index, tab ->
                val active = index == pane.activeTab
                Row(
                    Modifier
                        .padding(start = 4.dp, top = 3.dp, bottom = 3.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${tab.label}  ${tab.uri.displayPath}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickableNoRipple { controller.switchTab(side, index) },
                    )
                    if (active && pane.tabs.size > 1) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭标签",
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(13.dp)
                                .clickableNoRipple { controller.closeTab(side, index) },
                        )
                    }
                }
            }
            IconButton(onClick = { controller.newTab(side) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "新建标签", modifier = Modifier.size(16.dp))
            }
        }

        // ---- 路径栏 + 工具
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else Color.Transparent
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.back(side) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.ArrowBack, "后退", modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = { controller.forward(side) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.ArrowForward, "前进", modifier = Modifier.size(17.dp))
            }
            Text(
                text = pane.uri.displayPath,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            IconButton(onClick = { controller.toggleHidden(side) }, modifier = Modifier.size(30.dp)) {
                Text(if (pane.showHidden) "隐" else "·", style = MaterialTheme.typography.labelMedium,
                    color = if (pane.showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.ArrowDropDown, "排序", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortBy.entries.forEach { by ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(by) + if (pane.sort.by == by) (if (pane.sort.ascending) " ↑" else " ↓") else "") },
                            onClick = {
                                val asc = if (pane.sort.by == by) !pane.sort.ascending else true
                                controller.setSort(side, SortSpec(by, asc, pane.sort.dirsFirst))
                                showSortMenu = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (pane.sort.dirsFirst) "文件夹置顶：开" else "文件夹置顶：关") },
                        onClick = {
                            controller.setSort(side, pane.sort.copy(dirsFirst = !pane.sort.dirsFirst))
                            showSortMenu = false
                        },
                    )
                }
            }
            IconButton(onClick = { controller.refresh(side) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(17.dp))
            }
        }

        // ---- 统计行
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pane.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (pane.hasSelection) {
                Text(
                    "已选 ${pane.selection.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ---- 列表
        Box(Modifier.weight(1f)) {
            when {
                pane.loading -> LoadingState()
                pane.error != null -> ErrorState(
                    message = pane.error,
                    actionLabel = "重试",
                    onAction = { controller.refresh(side) },
                )
                pane.items.isEmpty() -> EmptyState("空目录", "长按可多选，⇄ 可复制/移动到对面窗格")
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(pane.items, key = { it.uri.toString() }) { item ->
                        FileRow(
                            item = item,
                            selected = pane.selection.contains(item.uri.toString()),
                            onClick = {
                                if (pane.hasSelection) controller.toggleSelection(side, item.uri)
                                else controller.openItem(side, item)
                            },
                            onLongClick = {
                                if (!pane.hasSelection) controller.enterSelectionMode(side, item)
                                else controller.toggleSelection(side, item.uri)
                            },
                            onMore = { onRowAction(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    item: FileMetadata,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(name = item.name, isDirectory = item.isDirectory, size = 34.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    val t = Fmt.time(item.lastModified)
                    if (t.isNotBlank()) append(t)
                    if (!item.isDirectory && item.size >= 0) {
                        if (isNotEmpty()) append("  ·  ")
                        append(Fmt.size(item.size))
                    }
                    item.permissions?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append(Fmt.mode(it))
                    }
                    item.symlinkTarget?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append("→ ").append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, "更多", modifier = Modifier.size(16.dp))
        }
    }
}

private fun sortLabel(by: SortBy): String = when (by) {
    SortBy.NAME -> "按名称"
    SortBy.SIZE -> "按大小"
    SortBy.TIME -> "按时间"
    SortBy.TYPE -> "按类型"
}

/** 无涟漪点击（更接近 MT 的干脆手感） */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
