package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.data.Bookmark
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.mtCombinedClickable
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ================================================================================================
// 书签面板（MT 底部抽屉 / 书签页共用的那一块）：最近 + 书签 两个标签页 + ⋮ 菜单 + 行菜单。
//
// MT 的完整书签面板是**从底栏上滑拉出的底部抽屉**（`0x7f1107ca`「从底部工具栏上滑即可打开书签」），
// 抽屉里是「最近」（`0x7f1106a6`）与「书签」（`0x7f1100da`）两个标签页。
// 抽屉外壳（遮罩 / 高度拖动 / 底部 ⌄）在 BookmarkDrawer.kt；这里只放两个标签页共用的内容，
// 独立书签页（从侧边栏 / 主页进入）也复用它 —— 两处不能各写一套列表。
// ================================================================================================

/** 书签面板的标签页（MT：`0x7f1106a6`「最近」 / `0x7f1100da`「书签」） */
enum class BookmarkTab(val label: String) {
    RECENT("最近"),
    BOOKMARK("书签"),
}

// ------------------------------------------------------------------ 纯逻辑（可单测）

/**
 * 行标题：
 *  - 网络路径 → 连接名（MT 抽屉里显示「Dav」而不是末级目录名）；
 *  - 本地 / 压缩包 → 末级目录名，根目录退回「内部存储」。
 */
fun bookmarkRowTitle(uri: VfsUri, connectionName: String?): String {
    val conn = connectionName?.takeIf { it.isNotBlank() }
    if (uri.scheme != "local" && uri.scheme != "archive" && conn != null) return conn
    return uri.name.ifEmpty {
        when (uri.scheme) {
            "local" -> "内部存储"
            "archive" -> "压缩包"
            else -> uri.scheme.uppercase()
        }
    }
}

/**
 * 行副标题（MT 观感）：
 *  - 本地 → 真实文件系统路径（`/storage/emulated/0/…`，由 [pathOf] 换算）；
 *  - 网络 → `协议@连接名 /存储内路径`（MT 截图里的 `webdav@Dav /本地存储/Vedios`）。
 */
fun bookmarkRowSubtitle(uri: VfsUri, connectionName: String?, pathOf: (VfsUri) -> String): String {
    val path = pathOf(uri)
    if (uri.scheme == "local" || uri.scheme == "archive") return path
    val host = connectionName?.takeIf { it.isNotBlank() } ?: uri.authority
    return "${uri.scheme}@$host $path"
}

/**
 * 「复制路径」复制的文本：**必须能直接拿来用**，不能复制 [bookmarkRowSubtitle] 的展示串。
 *
 * 旧实现复制 `dav@我的 NAS /Vedios` —— 既不是文件系统路径，也不是可解析 URI，
 * 粘贴到输入框/地址栏都无法跳转。这里改为：
 *  - 本地 / 压缩包：真实文件系统路径（与列表显示一致，可直接粘到其他应用）；
 *  - 网络：完整 `VfsUri`（带 `?c=<连接号>`），粘回路径输入框能还原成同一连接同一目录。
 */
fun bookmarkCopyText(uri: VfsUri, pathOf: (VfsUri) -> String): String =
    if (uri.scheme == "local" || uri.scheme == "archive") pathOf(uri) else uri.toString()

/**
 * 拖动排序：把 [id] 从当前位置移动 [moved] 行，返回新顺序。
 *
 * 落点夹取到 `0..lastIndex`（拖出列表边界不外溢）；`id` 不在列表里或没移动时**原样返回**
 * （调用方据此判断要不要写库，避免每次长按松手都打一次事务）。
 */
fun moveBookmarkIds(ids: List<Long>, id: Long, moved: Int): List<Long> {
    val from = ids.indexOf(id)
    if (from < 0 || moved == 0) return ids
    val to = (from + moved).coerceIn(0, ids.lastIndex)
    if (to == from) return ids
    val out = ids.toMutableList()
    out.removeAt(from)
    out.add(to, id)
    return out
}

/** 抽屉高度的三档落点（MT：半屏 ↔ 全屏，往下拖过阈值 = 收起） */
enum class BookmarkDrawerAnchor(val fraction: Float) {
    CLOSE(0f),
    HALF(0.52f),
    FULL(0.94f),
}

/**
 * 「最近」列表的可用性过滤：压缩包**内部**路径不进列表。
 *
 * 与书签的 `bookmarkTargetOf` 同一条理由 —— 下次从「最近」点进去时压缩包还没挂载，
 * `archive://` 必然报「未连接」；与其让用户点一次错，不如不展示。
 */
fun recentPathsForDrawer(uris: List<VfsUri>): List<VfsUri> = uris.filter { it.scheme != "archive" }

/**
 * 抽屉高度松手后吸附到哪一档。
 *
 * 按「当前位置离哪个档位最近」判定，而不是按拖动方向：手指抖一下不会把抽屉甩走。
 * 低于半屏的 60% 视为「要收起」—— MT 抽屉下拉关闭的手感阈值。
 */
fun bookmarkDrawerAnchor(fraction: Float): BookmarkDrawerAnchor {
    val half = BookmarkDrawerAnchor.HALF.fraction
    val full = BookmarkDrawerAnchor.FULL.fraction
    if (fraction < half * 0.6f) return BookmarkDrawerAnchor.CLOSE
    return if (fraction > (half + full) / 2f) BookmarkDrawerAnchor.FULL else BookmarkDrawerAnchor.HALF
}

// ------------------------------------------------------------------ 面板

/**
 * 书签面板内容（标签页 + 列表），抽屉与独立页共用。
 *
 * @param onOpened 打开某一条之后要做的事（抽屉里 = 收起抽屉；独立页里 = 回到双列）
 * @param pathOf   URI → 展示路径（本地换算成 `/storage/emulated/0/…`）
 * @param headerModifier 抽屉把它自己的拖动修饰符挂在标题行上（拖动标题行 = 拉伸 / 收起抽屉）
 */
@Composable
fun BookmarkPanel(
    container: AppContainer,
    controller: BrowserController,
    tab: BookmarkTab,
    onTabChange: (BookmarkTab) -> Unit,
    onOpened: () -> Unit,
    pathOf: (VfsUri) -> String,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
) {
    // 列表要查 SQLite：组合期同步查会白卡一帧（条目多 / 库被别处写锁时更明显）→ 放 IO 上读。
    // 读回来之前是 null（不是空列表）：抽屉每次拉起都会重读，用空列表会先闪一下「还没有书签」。
    var version by remember { mutableIntStateOf(0) }
    val loadedBookmarks by produceState(initialValue = null as List<Bookmark>?, version) {
        value = withContext(Dispatchers.IO) { container.browser.bookmarks() }
    }
    val loadedRecents by produceState(initialValue = null as List<VfsUri>?, version, tab) {
        if (tab == BookmarkTab.RECENT) {
            value = withContext(Dispatchers.IO) {
                recentPathsForDrawer(
                    runCatching { container.bookmarkDao.recentPaths(60) }.getOrDefault(emptyList())
                )
            }
        }
    }
    val bookmarks = loadedBookmarks.orEmpty()

    var confirmClear by remember { mutableStateOf<BookmarkTab?>(null) }

    Column(modifier.fillMaxSize()) {
        BookmarkPanelHeader(
            tab = tab,
            onTabChange = onTabChange,
            bookmarkCount = bookmarks.size,
            onAddBookmark = {
                // 在回调里读当前活动窗格：抽屉开着时焦点可能已经变了，组合期捕获会加错窗格
                controller.addBookmark(controller.state.value.focused)
                version++
            },
            onClear = { confirmClear = tab },
            modifier = headerModifier,
        )
        when (tab) {
            BookmarkTab.BOOKMARK -> loadedBookmarks?.let { list ->
                BookmarkList(
                    bookmarks = list,
                    controller = controller,
                    onOpened = onOpened,
                    pathOf = pathOf,
                    onChanged = { version++ },
                )
            }
            BookmarkTab.RECENT -> loadedRecents?.let { list ->
                RecentList(
                    recents = list,
                    controller = controller,
                    onOpened = onOpened,
                    pathOf = pathOf,
                    onChanged = { version++ },
                )
            }
        }
    }

    // 清空 = 一次性抹掉一整份数据，二次确认（MT 对批量删除同样弹确认，`0x7f1105a8`）
    confirmClear?.let { which ->
        val isBookmark = which == BookmarkTab.BOOKMARK
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(if (isBookmark) "清空书签" else "清空最近记录") },
            text = {
                Text(
                    if (isBookmark) "将删除全部 ${bookmarks.size} 条书签；路径历史（最近）不受影响。"
                    else "将清空全部路径历史；书签不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isBookmark) controller.clearBookmarks() else controller.clearRecentPaths()
                    confirmClear = null
                    version++
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("取消") }
            },
        )
    }
}

/** 标题行：最近 / 书签 + 选中下划线 + 右侧 ⋮（MT 抽屉顶部那一行） */
@Composable
private fun BookmarkPanelHeader(
    tab: BookmarkTab,
    onTabChange: (BookmarkTab) -> Unit,
    bookmarkCount: Int,
    onAddBookmark: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookmarkTab.entries.forEach { item ->
            val selected = item == tab
            Box(
                Modifier
                    .fillMaxHeight()
                    .clickableNoRipple { onTabChange(item) }
                    .padding(horizontal = 18.dp)
                    .semantics {
                        role = Role.Tab
                        contentDescription = item.label
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // MT 的 TabLayout 指示条：贴着标题行底边的一条强调色细线
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.74f)
                            .height(2.5.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            Box(
                Modifier
                    .size(44.dp)
                    .clickableNoRipple { menu = true }
                    .semantics {
                        role = Role.Button
                        contentDescription = "书签菜单"
                    },
                contentAlignment = Alignment.Center,
            ) {
                MtVectorIcon(
                    icon = MtIcon.MORE,
                    size = 20.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ClaudeDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MtMenuRow(MtIcon.BOOKMARK, "添加书签（当前目录）") {
                    menu = false
                    onAddBookmark()
                }
                if (tab == BookmarkTab.BOOKMARK) {
                    MtMenuRow(
                        icon = MtIcon.DELETE,
                        label = "清空书签",
                        enabled = bookmarkCount > 0,
                    ) { menu = false; onClear() }
                } else {
                    MtMenuRow(MtIcon.DELETE, "清空最近记录") { menu = false; onClear() }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

// ------------------------------------------------------------------ 列表

@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    controller: BrowserController,
    onOpened: () -> Unit,
    pathOf: (VfsUri) -> String,
    onChanged: () -> Unit,
) {
    if (bookmarks.isEmpty()) {
        EmptyState(
            title = "还没有书签",
            subtitle = "⋮ →「添加书签（当前目录）」，或在双列页 ⋮ 菜单里「添加书签」",
        )
        return
    }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    /** 拖动落点 → 新顺序 → 写库（MT `0x7f110140`「长按后拖动排序」） */
    fun commitDrag(id: Long, moved: Int) {
        val before = bookmarks.map { it.id }
        val after = moveBookmarkIds(before, id, moved)
        if (after != before) {
            controller.reorderBookmarks(after)
            onChanged()
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { bookmark ->
            val isDragging = draggingId == bookmark.id
            val connName = controller.connectionNameOf(bookmark.uri)
            val subtitle = bookmarkRowSubtitle(bookmark.uri, connName, pathOf)
            Box(
                Modifier
                    .graphicsLayer { if (isDragging) translationY = dragOffset }
                    .background(
                        if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else Color.Transparent
                    )
                    .onGloballyPositioned { rowHeightPx = it.size.height.toFloat() }
                    .bookmarkRowGestures(
                        onClick = {
                            controller.openBookmark(bookmark)
                            onOpened()
                        },
                        onLongPress = {
                            draggingId = bookmark.id
                            dragOffset = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { dragOffset += it },
                        onDragEnd = { moved ->
                            val id = draggingId
                            draggingId = null
                            dragOffset = 0f
                            if (id != null) {
                                val step = rowHeightPx.coerceAtLeast(1f)
                                // 基本没动（< 1/5 行）= 长按后原地松手 → 行菜单；
                                // 真拖了才按「离哪一行最近」取整落点，0.6 行也算移动一行。
                                if (kotlin.math.abs(moved) < step * 0.2f) {
                                    menuFor = id
                                } else {
                                    val lines = (moved / step).roundToInt()
                                    if (lines != 0) commitDrag(id, lines)
                                }
                            }
                        },
                        onDragCancel = {
                            draggingId = null
                            dragOffset = 0f
                        },
                    ),
            ) {
                BookmarkRow(
                    title = bookmark.name.ifEmpty { bookmarkRowTitle(bookmark.uri, connName) },
                    subtitle = subtitle,
                )
                ClaudeDropdownMenu(expanded = menuFor == bookmark.id, onDismissRequest = { menuFor = null }) {
                    MtMenuRow(MtIcon.FOLDER, "打开") {
                        menuFor = null
                        controller.openBookmark(bookmark)
                        onOpened()
                    }
                    MtMenuRow(MtIcon.COPY, "复制路径") {
                        menuFor = null
                        clipboard.setText(AnnotatedString(bookmarkCopyText(bookmark.uri, pathOf)))
                        controller.showStatus("路径已复制")
                    }
                    MtMenuRow(MtIcon.DELETE, "删除书签") {
                        menuFor = null
                        controller.removeBookmark(bookmark.id)
                        onChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentList(
    recents: List<VfsUri>,
    controller: BrowserController,
    onOpened: () -> Unit,
    pathOf: (VfsUri) -> String,
    onChanged: () -> Unit,
) {
    if (recents.isEmpty()) {
        EmptyState(
            title = "还没有访问记录",
            subtitle = "在双列页打开过的目录会出现在这里",
        )
        return
    }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var menuFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(recents, key = { it.toString() }) { uri ->
            val connName = controller.connectionNameOf(uri)
            val subtitle = bookmarkRowSubtitle(uri, connName, pathOf)
            Box {
                BookmarkRow(
                    title = bookmarkRowTitle(uri, connName),
                    subtitle = subtitle,
                    // 最近列表没有行内按钮：点击 = 打开，长按 = 行菜单（与 MT 的抽屉一致）
                    modifier = Modifier.mtCombinedClickable(
                        onClick = {
                            controller.openRecentPath(uri)
                            onOpened()
                        },
                        onLongClick = { menuFor = uri.toString() },
                    ),
                )
                ClaudeDropdownMenu(expanded = menuFor == uri.toString(), onDismissRequest = { menuFor = null }) {
                    MtMenuRow(MtIcon.FOLDER, "打开") {
                        menuFor = null
                        controller.openRecentPath(uri)
                        onOpened()
                    }
                    MtMenuRow(MtIcon.COPY, "复制路径") {
                        menuFor = null
                        clipboard.setText(AnnotatedString(bookmarkCopyText(uri, pathOf)))
                        controller.showStatus("路径已复制")
                    }
                    MtMenuRow(MtIcon.DELETE, "从最近中移除") {
                        menuFor = null
                        controller.forgetRecentPath(uri)
                        onChanged()
                    }
                }
            }
        }
    }
}

/** 抽屉 / 书签页的一行：28dp 文件夹图标 + 名称 + 路径（MT 抽屉行高 ≈40dp） */
@Composable
private fun BookmarkRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(name = title, isDirectory = true, size = 28.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 书签行手势：短按 = 打开；**长按后拖动** = 排序；长按后原地松手 = 行菜单。
 *
 * 三个动作必须在**一次**手势判定里分开，不能拆成 `combinedClickable(onLongClick)` + 另一个
 * 拖动 `pointerInput`：长按那一刻两者会同时想接管，菜单会跟拖动抢手势。
 *
 * ⚠️ 这里**不用** `awaitLongPressOrCancellation`：它判定「长按成立」时已经把抬手/移动事件
 * 取走了，之后补一个 `waitForUpOrCancellation()` 会一直等**下一次**手势 —— 表现为
 * 「点一下没反应，下一次点击才打开上一条」。改成 `waitForUpOrCancellation` 外面套
 * `withTimeoutOrNull(长按超时)`：超时 = 长按，抬手 = 点击，被列表滚动消费 = 什么都不做。
 */
private fun Modifier.bookmarkRowGestures(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onDragCancel: () -> Unit,
): Modifier = this
    // 自定义手势不经过 clickable，读屏的「点击」要显式补一条动作
    .semantics {
        role = Role.Button
        onClick(label = "打开") {
            onClick()
            true
        }
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var cancelled = false
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                val change = waitForUpOrCancellation()
                if (change == null) cancelled = true
                change
            }
            when {
                up != null -> onClick()
                // 被列表滚动 / 滑出手势区抢走：不点击、不进入拖动
                cancelled -> Unit
                else -> {
                    onLongPress()
                    var total = 0f
                    val completed = drag(down.id) { change ->
                        change.consume()
                        val dy = change.positionChange().y
                        total += dy
                        onDrag(dy)
                    }
                    if (completed) onDragEnd(total) else onDragCancel()
                }
            }
        }
    }

/** 取连接名（网络路径行标题 / 副标题用；本地路径返回 null） */
private fun BrowserController.connectionNameOf(uri: VfsUri): String? {
    val conn = container.connectionOf(VfsUris.connectionId(uri))
        ?: container.connectionByAuthority(uri.scheme, uri.authority)
    return conn?.name
}
