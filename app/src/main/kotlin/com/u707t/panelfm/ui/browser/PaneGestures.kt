package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.u707t.panelfm.core.ui.MtRowGesture
import com.u707t.panelfm.core.vfs.FileMetadata

// ---------------------------------------------------------------------------
// 文件列表的触摸手势层（复刻 MT：手势挂在**列表**上，行本身不处理触摸）
//
// 这里只有「怎么判定」和「怎么派发」；选择语义在 core.common.MtSelection，
// 手势判定机在 core.ui.MtRowGesture（两者都可单测）。布局在 PaneView.kt。
// 本层自身的**触摸生命周期**（抬手必须退出循环）也有回归测试：
// app/src/test 的 RowGestureWiringTest（假作用域驱动真实 awaitEachGesture）。
// ---------------------------------------------------------------------------

/**
 * 行手势回调集合（用 [androidx.compose.runtime.rememberUpdatedState] 包裹后交给 `pointerInput`，
 * 避免长手势过程中捕获到过期的 lambda / 列表内容）。
 *
 * 手势挂在**整张列表**上（一个指针节点），而不是每一行上：复刻 MT 的结构
 * （MT 的列表手势挂在 ListView 上，行本身不处理触摸），也避免 `LazyColumn` 回收行时
 * 把进行中的手势一起取消。
 *
 * 触摸一律走 **Initial pass**（父节点先收到）：菜单 / 滑动选中触发后立刻消费事件，
 * 内层 `scrollable` 与行的 `clickable` 都收不到这次触摸的其余部分。
 */
internal class ListGestures(
    val enabled: () -> Boolean,
    val indexAtY: (Float) -> Int,
    val itemAt: (Int) -> FileMetadata?,
    val onTapRow: (FileMetadata) -> Unit,
    val onLongPressRow: (FileMetadata) -> Unit,
    /** 滑动选中：第二个参数 = 滑动方向（右滑 = true，用于行动效） */
    val onSwipeSelect: (FileMetadata, Boolean) -> Unit,
)

/**
 * 列表行手势：**整张列表一个指针节点**。
 * 判定交给 [MtRowGesture]（纯逻辑、可单测），这里只负责「坐标 → 行号 → 回调」的搬运。
 *
 * 三个动作（用户实机确认的 MT 语义）：
 *  - **长按**（400ms）→ 震动 + 弹该项二级菜单（不等松手；不改选择）；
 *  - **左右滑动**（≥24dp）→ 震动 + 进入多选 / 与上一次滑动连成区间；
 *  - 纵向拖动 → 让位给列表滚动。
 */
internal fun Modifier.rowListGestures(
    machine: MtRowGesture,
    gestures: () -> ListGestures,
    haptic: HapticFeedback,
): Modifier = pointerInput(Unit) {
    // 每个触摸从按下到抬手只跑一遍 [detectRowGesture]；
    // 跑完必须返回，`awaitEachGesture` 才能干净地开始监听下一次触摸（原因见其注释）。
    awaitEachGesture {
        detectRowGesture(machine, gestures, haptic)
    }
}

/**
 * 跟踪**一次**行手势（按下 → 判定 → 抬手），判定结果直接派发到 [ListGestures]。
 *
 * 从 `awaitEachGesture` 的块里提出来单独成函数，是为了让「触摸生命周期」能被单测覆盖：
 * `RowGestureWiringTest` 用假的 [AwaitPointerEventScope]（脚本事件流 + 每次触摸换新
 * PointerId，复刻真实语义）驱动它 —— 实机 bug「滑动选择必须滑 2 次 / 滑第二项有一整段
 * 固定无响应」正是出在这个循环的退出时机上，而判定机 [MtRowGesture] 本身完全正确。
 *
 * ## ⚠️ 循环的退出时机（这里踩过坑）
 *
 * **抬手（或指针消失）必须让本循环退出。** Compose 的 `awaitEachGesture` 在手势块返回后
 * 会调用 `awaitAllPointersUp()`；若块没退出、而是继续挂着等事件，那么下一次触摸的
 * 第一个事件会先被这里收到：`PointerId` 每次触摸都是**新值**（`MotionEventAdapter`
 * 用单调递增的 nextId，抬手即回收映射），走到 `?: break` 退出块之后，
 * `awaitAllPointersUp` 会**把这次触摸整个等到抬手才放行** ——
 * 该次触摸的按下 / 滑动 / 长按全部派发不出来。实机表现就是：
 *
 *  - 滑动选中一项后直接滑第二项**没反应**（要再滑一次，即「滑动选择必须滑 2 次」）；
 *  - 长按弹过菜单之后，紧接着的那一次触摸同样被整段吞掉。
 *
 * 所以循环末尾统一收口：**先派发这一帧的判定，再按「指针已抬起」退出**
 * （Tap / HandOff 分支本来就该提前 break）。
 */
internal suspend fun AwaitPointerEventScope.detectRowGesture(
    machine: MtRowGesture,
    gestures: () -> ListGestures,
    haptic: HapticFeedback,
) {
    // Initial pass（父节点先收到）：触发后立刻消费事件，
    // 内层 scrollable / clickable 看不到这次触摸的其余部分。
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val g = gestures()
    // 加载中（遮罩可见）整体不响应：避免遮罩期间误开文件 / 误多选
    if (!g.enabled()) return

    machine.begin(downIndex = g.indexAtY(down.position.y))
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // 跟踪的这个指针不在了（多点触控让位 / 系统取消）：本次触摸结束
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val dxPx = change.position.x - down.position.x
            // 判定前先记阶段：MENU / SWIPED 期间的事件（**含抬手那一下**）全部吞掉，
            // 避免重复触发。注意 update() 会立刻把阶段改成 DONE，判定完再读就晚了。
            val phaseBefore = machine.phase
            val decision = machine.update(
                dx = dxPx / density,
                dy = (change.position.y - down.position.y) / density,
                elapsedMs = change.uptimeMillis - down.uptimeMillis,
                pressed = change.pressed,
            )
            if (phaseBefore == MtRowGesture.Phase.MENU || phaseBefore == MtRowGesture.Phase.SWIPED) {
                change.consume()
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

                MtRowGesture.Decision.LongPressMenu -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    g.itemAt(machine.downIndex)?.let { item ->
                        change.consume()
                        g.onLongPressRow(item)
                    }
                    // 不 break：继续吞事件到手指抬起（见循环末尾的抬手收口）
                }

                is MtRowGesture.Decision.SwipeSelect -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    change.consume()
                    g.itemAt(machine.downIndex)?.let { item ->
                        g.onSwipeSelect(item, decision.towardRight)
                    }
                }

                // 纵向拖动：让位给列表滚动（不消费事件）
                MtRowGesture.Decision.HandOff -> break
            }
            // 抬手 / 系统取消 = 本次触摸结束：必须退出循环（原因见函数头注释），
            // 让 awaitEachGesture 的手势块返回，为下一次触摸重新开始
            if (!change.pressed) break
        }
    } finally {
        machine.cancel()
    }
}
