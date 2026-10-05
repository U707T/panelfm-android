package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtRowGesture
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------------------
// 文件列表的触摸手势层（复刻 MT：手势挂在**列表**上，行本身不处理触摸）
//
// 这里只有「怎么判定」和「怎么派发」；选中语义在 core.common.MtSelection，
// 手势判定机在 core.ui.MtRowGesture（两者都可单测）。布局在 PaneView.kt。
// ---------------------------------------------------------------------------

/**
 * 行手势回调集合（用 [rememberUpdatedState] 包裹后交给 `pointerInput`，
 * 避免长手势过程中捕获到过期的 lambda / 列表内容）。
 *
 * 手势挂在**整张列表**上（一个指针节点），而不是每一行上：
 *
 *  - 复刻 MT 的结构（MT 的列表手势挂在 ListView 上，行本身不处理触摸）；
 *  - 行级节点在 `LazyColumn` 里会**随着滚动被销毁** —— 扫选到屏幕外时行被回收、
 *    手势跟着被取消，「边缘自动滚动 + 连续区间」根本做不完（旧实现的硬伤）；
 *  - 挂在列表上以后，扫选判定与列表滚动在同一处，边缘自动滚动不会互相打架。
 *
 * 触摸一律走 **Initial pass**（父节点先收到）：横滑进入多选后立刻消费事件，
 * 内层 `scrollable` 看到「已被消费」就不再纵向滚动 —— 与旧的行级实现效果一致。
 */
internal class ListGestures(
    val enabled: () -> Boolean,
    val indexAtY: (Float) -> Int,
    val itemAt: (Int) -> FileMetadata?,
    val keyAt: (Int) -> String?,
    val selectionMode: () -> Boolean,
    val onTapRow: (FileMetadata) -> Unit,
    val onLongPressRow: (FileMetadata) -> Unit,
    val onSweepStart: (Int) -> Unit,
    val onSweepTo: (Int) -> Unit,
    val onSweepEnd: () -> Unit,
    val onSwipeMenu: (FileMetadata) -> Unit,
    /** 跟手预览：key = 要位移的那一行（null = 结束）；x = 目标位移（px） */
    val onPreview: (key: String?, x: Float) -> Unit,
)

/**
 * 列表行手势：**整张列表一个指针节点**（为什么不是行级，见 [ListGestures] 的注释）。
 * 判定交给 [MtRowGesture]（纯逻辑、可单测），这里只负责「坐标 → 行号 → 回调」的搬运。
 *
 * 两块只有挂在列表上才能做出来的交互：
 *  - **边缘自动滚动**：手指停在列表上/下边缘的热区里，列表按帧滚动、区间继续铺
 *    （MT 的长列表能一路扫到屏幕之外，靠的就是它）；
 *  - **跟手预览**：扫过的那一行横向跟着手指走（阻尼 + 上限），抬手弹回原位。
 *
 * 注意 Compose 的坑：`pointerInput` 的代码块是**受限挂起作用域**（restricted suspension），
 * 里面不能调 `scrollBy` / `animateTo` / `withFrameNanos` 这类挂起函数。所以挂起的事
 * （自动滚动、回弹）一律交给 `scope`（组合作用域）里的协程，这里只做非挂起的判定与派发。
 */
internal fun Modifier.rowListGestures(
    machine: MtRowGesture,
    gestures: () -> ListGestures,
    listState: LazyListState,
    scope: CoroutineScope,
    haptic: HapticFeedback,
): Modifier = pointerInput(Unit) {
    val edgePx = MtGesture.SweepEdgeDp.dp.toPx()
    val stepPx = MtGesture.SweepMaxStepDp.dp.toPx()
    val previewMaxPx = MtGesture.SweepPreviewDp.dp.toPx()
    // 扫选运行期数据：指针协程写、自动滚动协程读（同一个主线程，天然串行，无需加锁）
    val runtime = SweepRuntime()
    var ticker: Job? = null

    awaitEachGesture {
        // Initial pass（父节点先收到）：横滑进入多选后立刻消费事件，
        // 内层 scrollable 看到「已被消费」就不再纵向滚动 —— 与旧的行级实现效果一致。
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val g = gestures()
        // 加载中（遮罩可见）整体不响应：避免遮罩期间误开文件 / 误多选
        if (!g.enabled()) return@awaitEachGesture

        machine.begin(downIndex = g.indexAtY(down.position.y), selectingAtStart = g.selectionMode())
        runtime.active = false
        runtime.pointerY = down.position.y
        runtime.height = size.height.toFloat()
        var sweeping = false

        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dxPx = change.position.x - down.position.x
                runtime.pointerY = change.position.y
                runtime.height = size.height.toFloat()
                val decision = machine.update(
                    dx = dxPx / density,
                    dy = (change.position.y - down.position.y) / density,
                    elapsedMs = change.uptimeMillis - down.uptimeMillis,
                    pressed = change.pressed,
                    index = g.indexAtY(runtime.pointerY),
                )
                when (machine.phase) {
                    // 扫选 / 长按期间不让列表跟着纵向滚（长按后要弹菜单，列表不能先滚走）
                    MtRowGesture.Phase.SWEEP -> {
                        change.consume()
                        sweeping = true
                        // 跟手预览：阻尼后夹在 ±SweepPreviewDp 之内（跟手但不脱手）
                        g.onPreview(g.keyAt(machine.downIndex), (dxPx * MtGesture.SweepPreviewDamp).coerceIn(-previewMaxPx, previewMaxPx))
                    }
                    MtRowGesture.Phase.LONG_PRESS -> change.consume()
                    else -> Unit
                }
                when (decision) {
                    MtRowGesture.Decision.None -> Unit

                    MtRowGesture.Decision.Tap -> {
                        // 未命中真实行（`..` 行 / 空白）：不消费，交给 `..` 行自己的 clickable
                        g.itemAt(machine.downIndex)?.let { item ->
                            change.consume()
                            g.onTapRow(item)
                        }
                        break
                    }

                    MtRowGesture.Decision.LongPressArmed ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                    MtRowGesture.Decision.LongPress -> {
                        g.itemAt(machine.downIndex)?.let { item ->
                            change.consume()
                            g.onLongPressRow(item)
                        }
                        break
                    }

                    MtRowGesture.Decision.EnterSweep -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change.consume()
                        g.onSweepStart(machine.downIndex)
                        runtime.active = true
                        ticker?.cancel()
                        ticker = startSweepTicker(scope, runtime, listState, machine, gestures, edgePx, stepPx)
                    }

                    is MtRowGesture.Decision.SweepTo -> {
                        change.consume()
                        g.onSweepTo(decision.index)
                    }

                    MtRowGesture.Decision.SweepEnd -> {
                        change.consume()
                        break
                    }

                    MtRowGesture.Decision.SwipeMenu -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        change.consume()
                        g.itemAt(machine.downIndex)?.let { g.onSwipeMenu(it) }
                        break
                    }

                    // 纵向拖动：让位给列表滚动（不消费事件）
                    MtRowGesture.Decision.HandOff -> break
                }
            }
        } finally {
            // 收尾（无论正常结束 / 菜单弹出 / 被取消都要走）：
            // 停掉自动滚动、结束扫选会话、让预览弹回原位
            runtime.active = false
            ticker?.cancel()
            ticker = null
            if (machine.phase == MtRowGesture.Phase.SWEEP) machine.cancel()
            if (sweeping) {
                g.onSweepEnd()
                g.onPreview(g.keyAt(machine.downIndex), 0f)
            }
        }
    }
}

/**
 * 启动边缘自动滚动的协程。
 *
 * 单独抽一个**非挂起**函数：`pointerInput` 是受限挂起作用域，
 * 里面不能直接调用 `sweepAutoScroll` 这样的挂起函数（编译器会拒绝）。
 */
internal fun startSweepTicker(
    scope: CoroutineScope,
    runtime: SweepRuntime,
    listState: LazyListState,
    machine: MtRowGesture,
    gestures: () -> ListGestures,
    edgePx: Float,
    stepPx: Float,
): Job = scope.launch { sweepAutoScroll(runtime, listState, machine, gestures, edgePx, stepPx) }

/** 扫选运行期状态（自动滚动协程要读它） */
internal class SweepRuntime {
    /** 是否仍在扫选中（手指按住且已进入扫选） */
    var active = false

    /** 手指在列表内的 Y（px） */
    var pointerY = 0f

    /** 列表高度（px） */
    var height = 0f
}

/**
 * 边缘自动滚动的 ticker：手指停在列表上/下边缘热区里，列表按帧滚动、区间跟着继续铺。
 *
 * 跑在组合作用域（而不是 `pointerInput` 的受限作用域）里 —— 后者调不了 `scrollBy`。
 * 手势结束时 [SweepRuntime.active] 置 false，循环自然退出。
 */
internal suspend fun sweepAutoScroll(
    runtime: SweepRuntime,
    listState: LazyListState,
    machine: MtRowGesture,
    gestures: () -> ListGestures,
    edgePx: Float,
    stepPx: Float,
) {
    // 整个扫选期间都挂着（每帧一次回调，开销可以忽略）：
    // 手指**随时**可以再回到边缘继续滚，不能因为「这一帧不在热区」就把 ticker 结束掉。
    while (coroutineContext.isActive && runtime.active) {
        val step = edgeScrollStep(runtime.pointerY, runtime.height, edgePx, stepPx)
        if (step != null && step != 0f && listState.scrollBy(step) != 0f) {
            val index = gestures().indexAtY(runtime.pointerY)
            if (index >= 0) {
                val d = machine.indexChanged(index)
                if (d is MtRowGesture.Decision.SweepTo) gestures().onSweepTo(d.index)
            }
        }
        withFrameNanos { }
    }
}

/**
 * 手指落在列表上/下边缘热区时，每帧该滚多少像素（正 = 向下滚）；不在热区返回 null。
 *
 * 速度与「伸进热区多深」成正比：刚碰到边缘慢一点（好微调），越靠边越快（长列表扫得动）。
 * 纯函数，单测在 `PaneGestureTest`。
 */
internal fun edgeScrollStep(y: Float, height: Float, edge: Float, maxStep: Float): Float? {
    if (edge <= 0f || height <= 0f) return null
    return when {
        y < edge -> -maxStep * ((edge - y) / edge).coerceIn(0f, 1f)
        y > height - edge -> maxStep * ((y - (height - edge)) / edge).coerceIn(0f, 1f)
        else -> null
    }
}

