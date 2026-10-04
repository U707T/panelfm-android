package com.u707t.panelfm.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.Bookmark
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtListRow
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.safeAreaPadding

/**
 * 书签管理（复刻 MT 的书签面板）：
 *  - 点开跳到对应目录；**长按后拖动排序**（MT `0x7f110140`「长按后拖动排序」）
 *  - 行尾 ✕ 删除
 *
 * MT 的完整书签面板是从**底栏上滑**调出的（`0x7f1107ca`），这里作为独立页呈现同一份数据。
 */
@Composable
fun BookmarksScreen(container: AppContainer, onBack: () -> Unit, onOpen: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    var order by remember { mutableStateOf<List<Long>>(emptyList()) }
    val raw = remember(version) { container.browser.bookmarks() }
    // 拖动排序的本地顺序（优先于创建时间序；拖动结束写回数据库）
    val bookmarks: List<Bookmark> = remember(raw, order) {
        if (order.isEmpty()) raw else {
            val index = order.withIndex().associate { (i, id) -> id to i }
            raw.sortedBy { index[it.id] ?: Int.MAX_VALUE }
        }
    }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("书签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${bookmarks.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bookmarks.isNotEmpty()) {
            // MT 0x7f110140：长按后拖动排序（提示文案与 MT 一致）
            Text(
                "长按后拖动排序",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
            )
        }

        if (bookmarks.isEmpty()) {
            EmptyState("还没有书签", "在双列页 ⋮ 菜单里「添加书签」，或长按列表项选「添加书签」")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    val isDragging = draggingId == bookmark.id
                    Box(
                        Modifier
                            .graphicsLayer {
                                if (isDragging) translationY = dragOffset
                            }
                            .background(
                                if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .onGloballyPositioned { rowHeightPx = it.size.height.toFloat() }
                            .pointerInput(bookmark.id, bookmarks.size) {
                                // MT 0x7f110140：**长按后**才进入拖动（与「长按打开菜单」区分：
                                // 长按后不松手并移动 = 排序；长按后松手 = 保持原位）
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = bookmark.id
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        val id = draggingId
                                        draggingId = null
                                        if (id != null) {
                                            // 落点 = 当前行 + 拖动行数 → 生成新顺序并持久化
                                            val step = rowHeightPx.coerceAtLeast(1f)
                                            val moved = (dragOffset / step).toInt()
                                            if (moved != 0) {
                                                val ids = bookmarks.map { it.id }.toMutableList()
                                                val from = ids.indexOf(id)
                                                if (from >= 0) {
                                                    val to = (from + moved).coerceIn(0, ids.lastIndex)
                                                    if (to != from) {
                                                        ids.removeAt(from)
                                                        ids.add(to, id)
                                                        order = ids
                                                        container.browser.reorderBookmarks(ids)
                                                    }
                                                }
                                            }
                                        }
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                }
                            },
                    ) {
                        MtListRow(
                            title = bookmark.name.ifEmpty { bookmark.uri.name.ifEmpty { "/" } },
                            subtitle = bookmark.uri.toString(),
                            icon = { FileIcon(name = bookmark.name, isDirectory = true, size = 38.dp) },
                            onClick = {
                                container.browser.openBookmark(bookmark)
                                onOpen()
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 拖动把手（MT 的排序手柄）
                                    MtVectorIcon(
                                        icon = MtIcon.EXPAND,
                                        size = MtSpec.MenuIcon,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .size(28.dp)
                                            .padding(end = 4.dp),
                                    )
                                    TextButton(onClick = {
                                        container.browser.removeBookmark(bookmark.id)
                                        version++
                                    }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                                }
                            },
                            titleColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
