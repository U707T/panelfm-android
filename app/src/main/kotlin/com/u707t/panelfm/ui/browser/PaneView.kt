package com.u707t.panelfm.ui.browser
import androidx.compose.runtime.DisposableEffect
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
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
import com.u707t.panelfm.core.ui.MtRowGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 行高（MT 实测 48dp：图标 32 + 上下 padding 8×2） */
private val ROW_HEIGHT = MtSpec.RowHeight

// ---------------------------------------------------------------------------
// 手势阈值全部集中在 core.ui.MtGesture（文档附录 G.5 / F.5），
// 判定逻辑在 core.ui.MtRowGesture（纯逻辑、单测在 MtRowGestureTest）；
// 这里只做指针事件 → 行号 → 控制器调用的搬运。
// ---------------------------------------------------------------------------

/**
 * 单个窗格（对齐 MT 管理器 `0x7f0c0033`）：
 *  - 列表首行 `..`；行高固定 48dp、行间 **1px** 分割线（MT `dividerHeight=1px`）
 *  - **左右滑动 ≥24dp 且 |dx| > 2|dy| = 进入多选并选中该行**（MT `0x7f1106f3`，实机对照为**离散**一次一行）；
 *    再滑动另一行 = 两项之间的闭区间选中（MT `0x7f11062f` 语义，见 `MtSelection.swipe`）
 *  - **长按 400ms = 直接弹该项二级菜单**：不改选择、不进多选（MT 实机行为；v1.9.0 已推翻旧模型）
 *  - 单击 = 打开（目录）/ 预览（文件）；多选状态下单击 = 切换选中
 *  - 手势挂在**列表**上（一个指针节点）；行回收不影响进行中的手势 —— 见 [ListGestures]
 *  - **每窗格两枚浮动钮**（复刻 MT A.2 + 2026-10-08 改版）：
 *    📋 粘贴（bottom|end 12dp，MT 规格 50dp / 图标 20dp / 底色 `#FFFF0000`）；
 *    ✕ 退出多选（bottom|end 74dp，多选态才出现）—— 已改为 **40dp 中性悬浮钮**
 *    （浅色白底深灰叉 / 深色深灰底浅灰叉 + 弹簧入场，不再用高饱和红圆）
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
    val density = LocalDensity.current


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
    /** 手指 Y（**列表本地坐标**）→ 列表项下标（-1 = 未命中；`..` 行也算未命中真实项） */
    fun indexAtY(localY: Float): Int {
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { localY >= it.offset && localY <= it.offset + it.size }
            ?: return -1
        val logical = com.u707t.panelfm.core.common.ScrollMemory.toItemIndex(info.index, canGoUp)
        return if (logical in pane.items.indices) logical else -1
    }

    /**
     * 滑动选中的行动效：`previewKey` 那一行朝滑动方向轻推 [MtGesture.SwipeAnimDp] 再弹回，
     * 抬手弹回原位。这里只放「目标位移」，实际动画在行内（[MtFileRow]）—— 只让那一行重绘。
     */
    var previewKey by remember { mutableStateOf<String?>(null) }
    var previewTarget by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val uiScope = rememberCoroutineScope()

    /** 行手势判定机（纯逻辑在 core.ui.MtRowGesture；判定与派发分离，便于单测） */
    val touchSlopDp = with(LocalDensity.current) { LocalViewConfiguration.current.touchSlop / this.density }
    /** 滑动选中的行动效位移（px）：在组合里算好，供非 @Composable 的处理函数使用 */
    val swipeAnimPx = with(LocalDensity.current) { MtGesture.SwipeAnimDp.dp.toPx() }
    val machine = remember(touchSlopDp) { MtRowGesture(touchSlopDp = touchSlopDp) }

    // ------------------------------------------------------------------
    // 行点击 / 长按 / 滑动选中（手势层在下面 rowListGestures 里，这里只放「做什么」）
    // ------------------------------------------------------------------

    /** 单击：多选态 = 切换选中（开启「点击连选」= 区间选择）；否则 = 打开 / 预览 */
    fun handleRowTap(item: FileMetadata) {
        if (pane.hasSelection) {
            if (!controller.tapSelect(side, item)) controller.toggleSelection(side, item.uri)
        } else {
            controller.focus(side)
            // 离开本目录前先把滚动位置存下来（最直接的保障：
            // 进子目录 / 打开文件都会换内容，之后返回上级要停在原地）
            saveScrollNow(loadedKey)
            controller.openItem(side, item)
        }
    }

    /**
     * 长按（400ms）= **直接弹该项的二级菜单**，不改选择、不进多选（MT 实机行为）。
     * 菜单里的「复制 / 移动 / 删除」等按该项处理；对选择集的操作在底栏 / 顶栏。
     */
    fun handleRowLongPress(item: FileMetadata) {
        controller.focus(side)
        onRowAction(item)
    }

    /**
     * 左右滑动一项（MT `0x7f1106f3` / `0x7f11062f`）：震动 + 动效 → 进入多选并选中该行；
     * 若之前滑动过另一项，两项之间的闭区间一并选中（语义在 [com.u707t.panelfm.core.common.MtSelection.swipe]）。
     */
    fun handleRowSwipeSelect(item: FileMetadata, towardRight: Boolean) {
        controller.swipeSelect(side, item)
        // 动效：该行朝滑动方向轻推 [MtGesture.SwipeAnimDp] 再弹回（读它的只有这一行）
        val key = item.uri.toString()
        previewKey = key
        previewTarget = swipeAnimPx * if (towardRight) 1f else -1f
        uiScope.launch {
            kotlinx.coroutines.delay(90)
            if (previewKey == key) previewTarget = 0f
        }
    }

    /**
     * 列表手势配置（包在 rememberUpdatedState 里：手势跨越多帧，回调必须取**最新一帧**的，
     * 否则长按过程中目录被刷新，会拿着过期的 items 算出错误的行号）。
     */
    val gestures by rememberUpdatedState(
        ListGestures(
            enabled = { !pane.loading },
            indexAtY = { y -> indexAtY(y) },
            itemAt = { index -> pane.items.getOrNull(index) },
            onTapRow = { item -> handleRowTap(item) },
            onLongPressRow = { item -> handleRowLongPress(item) },
            onSwipeSelect = { item, towardRight -> handleRowSwipeSelect(item, towardRight) },
        )
    )

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
                        // 行手势挂在**列表**上（见 ListGestures 的注释）
                        .rowListGestures(
                            machine = machine,
                            gestures = { gestures },
                            haptic = haptic,
                        ),
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
                            // 行动效预览：只有被滑动的那一行读这个位移（其余行传 null，不参与重组）
                            preview = if (previewKey == item.uri.toString()) previewTarget else null,
                            onTap = { handleRowTap(item) },
                            onLongPress = { handleRowLongPress(item) },
                        )
                        // MT：列表分隔线 = **1px**（`dividerHeight=1px`，色 `0x7f06003a`）
                        DividerPx()
                    }
                }
            }

            // ---- 每窗格 FAB（复刻 MT 0x7f0c0033 的 090166/09016A，附录 A.2）：
            //   剪贴板（粘贴，bottom|end 12dp，MT 规格 50dp 红色）；
            //   退出多选（✕，bottom|end 74dp；见 [ExitSelectionButton] —— 2026-10-08 改版，
            //   由 MT 复刻的 50dp 纯红 FAB 改为 40dp 中性悬浮钮）
            if (clipboardReady) {
                MtFab(
                    icon = MtIcon.PASTE,
                    contentDescription = "粘贴到当前目录",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MtSpec.FabMargin, bottom = MtSpec.FabMargin),
                ) { controller.pasteFromClipboard(side) }
            }
            ExitSelectionButton(
                visible = pane.hasSelection,
                onClick = { controller.clearSelection(side) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = MtSpec.FabMargin, bottom = MtSpec.FabStackStep),
            )

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

/**
 * 退出多选的轻量悬浮钮（2026-10-08 用户反馈改版，替代 MT 复刻的 50dp 纯红 FAB）：
 *
 *  - 视觉：40dp 中性圆钮 —— 浅色主题白底 + 深灰叉，深色主题 #303030 底 + 浅灰叉；
 *    叉自绘 Canvas（圆头细线），比 Material 实心字形更轻，3dp 阴影保持悬浮感；
 *  - 动效：进入多选时弹簧缩放 + 淡入，退出时快速缩小淡出（出现/消失都不「跳」）；
 *  - 位置沿用原 FAB 锚点（bottom|end 74dp，与粘贴 FAB 间隙 12dp），触控目标 40dp。
 */
@Composable
private fun ExitSelectionButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalPanelDarkTheme.current
    val container = if (dark) Color(0xFF303030) else Color.White
    val cross = if (dark) Color(0xFFE0E0E0) else Color(0xFF3C3C3C)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 140)) + scaleIn(
            initialScale = 0.72f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 1200f),
        ),
        exit = fadeOut(tween(durationMillis = 140)) + scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(durationMillis = 140),
        ),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "退出多选"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(15.dp)) {
                val w = size.width
                val inset = w * 0.06f
                val stroke = w * 0.11f
                drawLine(
                    color = cross,
                    start = Offset(inset, inset),
                    end = Offset(w - inset, w - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = cross,
                    start = Offset(w - inset, inset),
                    end = Offset(inset, w - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
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
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = MtSpec.RowNameSize, lineHeight = 17.sp),
            // 主题感知：深色主题下用 onSurface（旧实现写死近黑色 → 深色下不可读）
            color = MaterialTheme.colorScheme.onSurface,
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
    /** 滑动选中的行动效：非 null = 这一行刚被滑动选中（值 = 目标横向位移 px），随后弹回 0 */
    preview: Float?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val settings by container.settings.collectAsState()

    // 窗格焦点用「活动侧阴影 + 顶栏计数」表达即可；MT 2.14.5 实测**两窗格颜色一致**，
    // 不做整体调暗（旧实现非活动窗格淡化 0.85，同机对比时明显偏灰）。
    // 缩略图策略由本行已有的 settings 下传，避免每行再各订阅一次 settings 流
    val thumb = rememberThumb(
        container, item, targetPx = 96, skip = skipThumb,
        policy = ThumbPolicy(
            onMobileData = settings.thumbnailsOnMobile,
            maxBytes = settings.thumbnailMaxBytes,
            timeoutSec = settings.thumbnailTimeoutSec,
        ),
    )
    val dark = LocalPanelDarkTheme.current
    val previewMaxPx = with(LocalDensity.current) { MtGesture.SwipeAnimDp.dp.toPx() }
    // 行动效位移：用高刚度弹簧追目标值 —— 轻推过去、目标回 0 时自然弹回。
    // 动画只影响这一行：读取位置在 graphicsLayer 里（只重绘图层，不带着列表一起重组）。
    val previewSpec = remember { spring<Float>(dampingRatio = 1f, stiffness = 1400f) }
    val previewOffset by animateFloatAsState(targetValue = preview ?: 0f, animationSpec = previewSpec)

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            // ------------------------------------------------------------------
            // 滑动选中的行动效：整行朝滑动方向轻推一点再弹回，并轻微缩一点（最多 2%）。
            // 放在 graphicsLayer 里读动画值 —— 只重绘这一层，不触发重组、不影响其它行。
            // ------------------------------------------------------------------
            .graphicsLayer {
                translationX = previewOffset
                val f = 1f - 0.02f * (abs(previewOffset) / previewMaxPx).coerceIn(0f, 1f)
                scaleX = f
                scaleY = f
            }
            // 选中态：MT 用强调蓝的浅色底（浅色主题 #1976d2 @ 14%）
            .background(
                if (selected) {
                    if (dark) MtSpec.RowSelectedDark else MtSpec.RowSelectedLight
                } else Color.Transparent
            )
            // 触摸手势（点击 / 长按弹菜单 / 左右滑动选中）全部挂在**列表**上，
            // 这里只保留无障碍语义（见 PaneView 里的 ListGestures）。
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(item.name)
                    append(if (item.isDirectory) "，文件夹" else "，文件")
                    if (!item.isDirectory && item.size >= 0) append("，${Fmt.size(item.size)}")
                    val t = Fmt.time(item.lastModified)
                    if (t.isNotBlank()) append("，修改于 $t")
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
                    .clip(RoundedCornerShape(MtSpec.CornerSmall)),
            )
        } else {
            FileIcon(
                name = item.name,
                isDirectory = item.isDirectory,
                size = MtSpec.RowIcon,
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
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // MT「文件列表显示」三档（`0x7f110200/201/202`）：不显示权限 / 权限+大小 / 时间+大小
                rowSubtitle(settings.listDisplayMode, item),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.RowSubSize, lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selected) {
            // MT 的选中标记（顶栏计数 + 行内勾选）
            MtVectorIcon(
                icon = MtIcon.CHECK,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.primary,
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
