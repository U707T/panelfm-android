package com.u707t.panelfm.ui.browser
import androidx.compose.runtime.DisposableEffect
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtDividerColor
import com.u707t.panelfm.core.ui.MtFab
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlin.math.abs

/** 行高（MT 实测 48dp：图标 32 + 上下 padding 8×2） */
private val ROW_HEIGHT = MtSpec.RowHeight

// ---------------------------------------------------------------------------
// 手势阈值集中在 core.ui.MtGesture（文档附录 G.5），这里只做单位换算
// ---------------------------------------------------------------------------

/** 左右滑动进入多选（MT 0x7f1106f3） */
private val SWIPE_SELECT = MtGesture.SwipeSelectDp.dp

/** 已多选态右滑出菜单（MT 0x7f110697） */
private val SWIPE_MENU = MtGesture.SwipeMenuDp.dp

/** 长按触发时间（MT 400ms，比系统默认 500ms 灵敏） */
private const val LONG_PRESS_MS = MtGesture.LongPressMs

/** 长按位移容差（MT 12dp） */
private val LONG_PRESS_SLOP = MtGesture.LongPressSlopDp.dp

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
 * 单个窗格（对齐 MT 管理器 `0x7f0c0033`）：
 *  - 列表首行 `..`；行高固定 48dp、行间 **1px** 分割线（MT `dividerHeight=1px`）
 *  - **左右滑动 ≥24dp 且 |dx| > 2|dy| = 进入多选**（MT `0x7f1106f3`）；继续滑过行间 = 区间选择
 *  - **已多选态右滑 ≥48dp = 呼出更多操作**（MT `0x7f110697`；文档 F.5 冲突消解顺序第 5 条）
 *  - **长按 400ms = 锚点 + 多选**；**长按第二项 = 连选区间**（MT `0x7f110631`）
 *  - 单击 = 打开（目录）/ 预览（文件）；多选状态下单击 = 切换选中
 *  - **每窗格两枚 FAB**（复刻 MT A.2）：📋 粘贴（bottom|end 12dp）/ ✕ 关闭（bottom|end 74dp），
 *    50dp / 图标 20dp / 底色 `#FFFF0000`，**显隐由状态决定**（布局里 MT 都写 visible）
 *  - **加载遮罩**：`#66222222` + 转圈 + 「取消」+ 10sp 百分比（MT `09020D/09020E`）
 *  - 任何触摸都会先把本窗格设为活动窗口（同一时间只有一个窗口激活）
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
    val settings by container.settings.collectAsState()

    /** 列表在根坐标系中的顶部（把行内局部坐标换算成列表坐标） */
    var listTopRoot by remember { mutableStateOf(0f) }

    /** 手指 Y（根坐标）→ 列表项下标（-1 = 未命中） */
    fun indexAtRoot(rootY: Float): Int {
        val y = rootY - listTopRoot
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y <= it.offset + it.size }
            ?: return -1
        val logical = com.u707t.panelfm.core.common.ScrollMemory.toItemIndex(info.index, canGoUp)
        return if (logical in pane.items.indices) logical else -1
    }

    /** 本次滑动选择的锚点（按下的那一行）；-1 = 未开始 */
    var swipeAnchor by remember { mutableStateOf(-1) }

    // ------------------------------------------------------------------
    // 滚动位置记忆（复刻 MT：进子目录再返回，列表停在原地，不跳回顶部）
    //
    // 关键点：
    //  1. 用 `loadedUri`（**这批 items 属于哪个目录**）而不是 `pane.uri` —— 切目录时
    //     uri 先变、items 后到，用 uri 会把旧目录的位置写到新目录头上。
    //  2. 恢复与记录**串行**：先恢复到记忆位置，再开始观察并持续记录；
    //     否则「记录」会先用顶部的 (0,0) 把记忆擦掉，恢复就永远拿不到值。
    //  3. 用 `firstVisibleItemIndex/ScrollOffset`（滚动位置**状态**）而不是 `layoutInfo`
    //     （布局**结果**）：`scrollToItem` 会同步改前者，后者要等下一帧测量。
    //  4. 位置直接用 **LazyListState 的列表下标**存取（含 `..` 行），不做二次换算，
    //     避免两侧换算不一致导致「返回时偏一行」。
    // ------------------------------------------------------------------

    /** 当前这批 items 的滚动 key（= loadedUri）；null = 还没加载完 */
    val loadedKey = pane.loadedUri

    /**
     * 观察者写回时用它复核「我还是当前目录吗」。
     *
     * 切目录时旧 effect 会被取消，但取消是异步的：旧观察者可能在被取消前
     * 又收到一次 `scrollToItem(0,0)`（新目录的定位）引起的发射，
     * 若不加这道校验就会把**旧目录**的记忆擦成 0 —— 返回上级时又跳回顶部。
     */
    val currentKey by rememberUpdatedState(loadedKey)

    /** 立刻把当前位置写回记忆（离开组合 / 切换目录前调用，防止极端时序下丢位置） */
    fun saveScrollNow(key: String?) {
        if (key == null) return
        controller.rememberScroll(
            side,
            VfsUri.parse(key),
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        )
    }

    // 注册到控制器：底栏 ← → ↑ / 返回键 / ⋮ 跳转都直接调控制器，需要在切目录前落盘一次。
    // onDispose 也存一次：打开预览/编辑器时浏览页会被移出组合，回来时是全新的 LazyListState，
    // 必须靠这份记忆把位置还原。
    DisposableEffect(side, loadedKey) {
        val keyAtEnter = loadedKey
        controller.registerScrollSaver(side) { saveScrollNow(keyAtEnter) }
        onDispose {
            saveScrollNow(keyAtEnter)
            controller.unregisterScrollSaver(side)
        }
    }

    // 只在「这批内容换了目录」时重启（loadedKey 与 items 是同一次 updatePane 写入的，天然同步）。
    // 刻意**不**把 pane.items.size 放进 key：目录被后台刷新后条数变化会重启 effect，
    // 那样用户正在滚动的列表会被拽回记忆位置（观感是「滚到一半自己跳了」）。
    LaunchedEffect(loadedKey) {
        val key = loadedKey ?: return@LaunchedEffect
        if (pane.items.isEmpty()) return@LaunchedEffect
        val maxIndex = com.u707t.panelfm.core.common.ScrollMemory.toListIndex(pane.items.lastIndex, canGoUp)
        if (maxIndex < 0) return@LaunchedEffect
        // ① 先定位。**两种情况都必须显式定位**：
        //    · 有记忆 → 回到记忆位置（这就是「返回上级停在原地」）
        //    · 没记忆 → 回顶部（0,0）。这一步不能省：LazyListState 是跨目录复用的，
        //      上一个目录停在 20 号的话，新目录若项目够多就会**沿用 20 号**，
        //      表现为「进新目录后莫名停在中间」。
        //    显式「定位到指定项」（搜索/跳转）优先级更高，交给下面那个 effect 处理。
        if (pane.scrollToUri == null) {
            val entry = controller.recallScroll(side, VfsUri.parse(key))
            if (entry != null) {
                // 用 scrollToItem（不带动画）：返回上级时应当「原地不动」，
                // 动画会先显示顶部再滑过去，观感反而像「跳了一下」
                listState.scrollToItem(entry.index.coerceIn(0, maxIndex), entry.offset)
            } else {
                listState.scrollToItem(0, 0)
            }
        }
        // ② 再观察：此后用户每次滚动都持续写回（滚一段就点进子目录也不会丢）
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                // 复核：只有「我仍是当前目录」才写（防止被取消的旧观察者擦掉旧目录的记忆）
                if (currentKey == key) controller.rememberScroll(side, VfsUri.parse(key), index, offset)
            }
    }

    // 定位到指定项（搜索结果点进来 / 「打开所在目录」）：列表就绪后滚动到该项并清空请求
    LaunchedEffect(pane.scrollToUri, pane.items) {
        val target = pane.scrollToUri ?: return@LaunchedEffect
        val index = pane.items.indexOfFirst { it.uri.toString() == target }
        if (index >= 0) {
            val listIndex = com.u707t.panelfm.core.common.ScrollMemory.toListIndex(index, canGoUp)
            listState.animateScrollToItem(listIndex.coerceAtLeast(0))
        }
        controller.consumeScrollTo(side)
    }

    // 窗格边缘阴影由外层 DualPaneScreen 负责（MT 的 shadow_left/right 在窗格外侧 5dp）

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
                    if (pane.filtered) "试试清除搜索或过滤条件" else "底部 ＋ 新建，或从对面窗格复制进来",
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
                        item(key = "__parent__") {
                            ParentRow(
                                onClick = {
                                    // 返回上级前同样先存位置（这样「再进回这个子目录」也停在原地）
                                    saveScrollNow(loadedKey)
                                    controller.up(side)
                                },
                            )
                            DividerPx()
                        }
                    }
                    items(
                        pane.items,
                        key = { it.uri.toString() },
                        contentType = { if (it.isDirectory) "dir" else "file" },
                    ) { item ->
                        MtFileRow(
                            container = container,
                            skipThumb = listState.isScrollInProgress && settings.skipThumbsWhileScrolling,
                            item = item,
                            selected = pane.selection.contains(item.uri.toString()),
                            selectionMode = pane.hasSelection,
                            dimmed = !focused,
                            // 加载中（遮罩可见）时行手势整体关闭：避免遮罩期间误开文件 / 误多选
                            gesturesEnabled = !pane.loading,
                            tapRange = settings.tapRangeSelect,
                            indexAtRoot = { rootY -> indexAtRoot(rootY) },
                            onTap = {
                                // MT：多选态单击 = 切换选中（开启「点击连选」后 = 区间选择）；
                                // 否则单击 = 打开 / 预览
                                if (pane.hasSelection) {
                                    if (!controller.tapSelect(side, item)) controller.toggleSelection(side, item.uri)
                                } else {
                                    controller.focus(side)
                                    // 离开本目录前先把滚动位置存下来（最直接的保障：
                                    // 进子目录 / 打开文件都会换内容，之后返回上级要停在原地）
                                    saveScrollNow(loadedKey)
                                    controller.openItem(side, item)
                                }
                            },
                            onSwipeSelect = { index ->
                                // MT：左右滑动 = 进入多选（该项单选）；已有多选时滑动该行 = 加选该行
                                controller.focus(side)
                                swipeAnchor = index
                                val target = pane.items.getOrNull(index)
                                if (target != null) {
                                    if (!pane.hasSelection) controller.startSelectionDrag(side, index)
                                    else controller.addToSelection(side, target.uri)
                                }
                            },
                            onSweepTo = { index ->
                                val anchor = swipeAnchor
                                if (anchor >= 0 && index != anchor) controller.setSelectionRange(side, anchor, index)
                            },
                            onLongPress = {
                                // MT：长按 400ms = 锚点 + 进入多选；
                                // 再长按**另一项** = 连选区间（MT 0x7f110631）；松手弹动作菜单
                                controller.longPressSelect(side, item)
                                onRowAction(item)
                            },
                            // MT 0x7f110697「右滑列表项可进行更多操作」：已多选态右滑 ≥48dp → 弹动作菜单
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
                        // MT：列表分隔线 = **1px**（`dividerHeight=1px`，色 `0x7f06003a`）
                        DividerPx()
                    }
                }
            }

            // ---- 每窗格 FAB（复刻 MT 0x7f0c0033 的 090166/09016A，附录 A.2）：
            //   剪贴板（粘贴，bottom|end 12dp）/ 取消（✕，bottom|end 74dp；多选态才出现）
            //   50dp / 图标 20dp / 底色 #FFFF0000 / elevation 3dp
            if (clipboardReady) {
                MtFab(
                    icon = MtIcon.PASTE,
                    contentDescription = "粘贴到当前目录",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MtSpec.FabMargin, bottom = MtSpec.FabMargin),
                ) { controller.pasteFromClipboard(side) }
            }
            if (pane.hasSelection) {
                MtFab(
                    icon = MtIcon.CLOSE,
                    contentDescription = "退出多选",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MtSpec.FabMargin, bottom = MtSpec.FabStackStep),
                ) { controller.clearSelection(side) }
            }

            // ---- MT 加载遮罩（复刻 0x7f0c0033 的 09020D/09020E）：
            //   #66222222 半透明黑 + 转圈 + 「取消」按钮 + 10sp 百分比文字；
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
                                // MT：百分比文字 10sp（090214）
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

/** `..` 返回上级（列表首行；与普通行同样式：32dp 图标 + 8dp 内边距） */
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
            .padding(horizontal = MtSpec.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(
            name = "",
            isDirectory = true,
            size = MtSpec.RowIcon,
        )
        Text(
            "..",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = MtSpec.RowNameSize),
            color = MtSpec.RowNameLight,
            modifier = Modifier.padding(start = MtSpec.RowIconGap),
        )
    }
}

@Composable
private fun MtFileRow(
    container: com.u707t.panelfm.AppContainer,
    skipThumb: Boolean,
    item: FileMetadata,
    selected: Boolean,
    selectionMode: Boolean,
    dimmed: Boolean,
    gesturesEnabled: Boolean = true,
    tapRange: Boolean = false,
    indexAtRoot: (Float) -> Int,
    onTap: () -> Unit,
    onSwipeSelect: (Int) -> Unit,
    onSweepTo: (Int) -> Unit,
    onLongPress: () -> Unit,
    onSwipeMenu: (Int) -> Unit,
) {
    val settings by container.settings.collectAsState()

    // 非活动窗格：MT 只靠「活动侧阴影 + 顶栏高亮」表达焦点，**不整体调暗**；
    // 这里保留极轻微淡化（0.85），既区分焦点又不影响可读性（旧值 0.55 太暗、像禁用态）
    val alpha = if (dimmed) 0.85f else 1f
    val selectSlop = with(LocalDensity.current) { SWIPE_SELECT.toPx() }
    val menuSlop = with(LocalDensity.current) { SWIPE_MENU.toPx() }
    val longPressSlop = with(LocalDensity.current) { LONG_PRESS_SLOP.toPx() }
    val thumb = rememberThumb(container, item, targetPx = 96, skip = skipThumb)
    val haptic = LocalHapticFeedback.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
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
            // 选中态：MT 用强调蓝的浅色底（浅色主题 #1976d2 @ 14%）
            .background(
                if (selected) {
                    if (dark) MtSpec.RowSelectedDark else MtSpec.RowSelectedLight
                } else Color.Transparent
            )
            // ------------------------------------------------------------------
            // 行手势（MT 语义 + 文档附录 F.5 的冲突消解顺序）：
            //   1. 长按（>400ms，位移 <12dp）      = 锚点 + 进入多选（松手弹动作菜单）
            //   2. 单击                             = 打开 / 预览（多选态 = 切换选中）
            //   3. 左右滑动 |dx| ≥ 24dp 且 |dx|>2|dy| = 进入多选；继续滑过行间 = 区间选择
            //   4. 已多选态右滑 ≥ 48dp              = 呼出该项的更多操作
            //   5. 纵向拖动                         = 交给列表滚动（不消费事件）
            // ------------------------------------------------------------------
            .pointerInput(item.uri.toString(), gesturesEnabled, selectionMode) {
                if (!gesturesEnabled) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                val lpSlop = longPressSlop
                val selSlop = selectSlop
                val mSlop = menuSlop
                val selectingAtStart = selectionMode
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val downTime = down.uptimeMillis
                    val downIndex = gestures.indexAtRoot(gestures.rowTop().y + downPos.y)
                    var mode = RowGestureMode.UNDECIDED
                    var lastIndex = downIndex
                    /** 右滑呼出菜单：只触发一次 */
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
                                    // 防误触：判定「拖动过」用长按位移容差（12dp）而不是系统 touchSlop（约 8dp）
                                    val dragged = abs(dx) > lpSlop || abs(dy) > lpSlop
                                    when {
                                        elapsed >= LONG_PRESS_MS -> gestures.onLongPress()
                                        !dragged -> gestures.onTap()
                                    }
                                    change.consume()
                                    break
                                }
                                // 长按阈值到（MT 400ms）：进入「长按」态（震动提示；松手弹动作菜单）
                                elapsed >= LONG_PRESS_MS -> {
                                    mode = RowGestureMode.LONG_PRESS
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                // 纵向为主 → 列表滚动，不消费事件（先判断，避免斜向滚动被误判成滑动选择）
                                abs(dy) > touchSlop && abs(dy) >= abs(dx) -> break
                                // 左右滑动（≥24dp 且横向 > 纵向×2）→ 进入多选
                                // （MT 0x7f1106f3「左右滑动文件可直接选择」：两个方向都可进入选择）
                                downIndex >= 0 && abs(dx) > selSlop && abs(dx) > abs(dy) * 2f -> {
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
                                // MT 0x7f110697「右滑列表项可进行更多操作」：
                                // **已多选态**下继续右滑 ≥48dp → 呼出动作菜单（文档 F.5 第 5 条）。
                                // 要求横向位移足够大 + 纵向位移仍小（避免向下扫选区间时误弹菜单）。
                                if (!menuFired && selectingAtStart && dx > mSlop && dx > abs(dy) * 2f && abs(dy) < mSlop) {
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
            .padding(horizontal = MtSpec.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(MtSpec.RowIcon)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            FileIcon(
                name = item.name,
                isDirectory = item.isDirectory,
                size = MtSpec.RowIcon,
                alpha = alpha,
                folderColor = if (dimmed) MtSpec.FolderGlyphLight.copy(alpha = 0.75f)
                else MtSpec.FolderGlyphLight,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = MtSpec.RowIconGap),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MtSpec.RowNameSize,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = (if (item.isHidden) MtSpec.RowSubLight else MtSpec.RowNameLight)
                    .copy(alpha = alpha),
            )
            Text(
                // MT「文件列表显示」三档（`0x7f110200/201/202`）：不显示权限 / 权限+大小 / 时间+大小
                rowSubtitle(settings.listDisplayMode, item),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.RowSubSize),
                color = MtSpec.RowSubLight.copy(alpha = alpha),
                maxLines = 1,
            )
        }
        if (selected) {
            // MT 的选中标记（顶栏计数 + 行内勾选）
            MtVectorIcon(
                icon = MtIcon.CHECK,
                size = 18.dp,
                tint = MtSpec.AccentLight,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 列表副标题（MT「文件列表显示」三档，文案 `0x7f110200/201/202`）：
 * 纯函数在 [com.u707t.panelfm.core.common.MtListSubtitle]（可单测），这里只负责取字段。
 */
private fun rowSubtitle(mode: Int, item: FileMetadata): String =
    com.u707t.panelfm.core.common.MtListSubtitle.render(
        mode = mode,
        permissionText = item.permissions?.let { Fmt.modeLong(it, item.isDirectory, item.isSymlink) },
        timeText = Fmt.time(item.lastModified),
        sizeText = if (!item.isDirectory && item.size >= 0) Fmt.size(item.size) else "",
    )

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
