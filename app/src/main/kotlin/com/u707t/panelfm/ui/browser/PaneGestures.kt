package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.u707t.panelfm.core.ui.MtRowGesture
import com.u707t.panelfm.core.vfs.FileMetadata

// ---------------------------------------------------------------------------
// 文件列表的触摸手势层（复刻 MT：手势挂在**列表**上，行本身不处理触摸）
//
// 这里只有「怎么判定」和「怎么派发」；选择语义在 core.common.MtSelection，
// 手势判定机在 core.ui.MtRowGesture（两者都可单测）。布局在 PaneView.kt。
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
    awaitEachGesture {
        // Initial pass（父节点先收到）：触发后立刻消费事件，
        // 内层 scrollable / clickable 看不到这次触摸的其余部分。
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val g = gestures()
        // 加载中（遮罩可见）整体不响应：避免遮罩期间误开文件 / 误多选
        if (!g.enabled()) return@awaitEachGesture

        machine.begin(downIndex = g.indexAtY(down.position.y))
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dxPx = change.position.x - down.position.x
                val decision = machine.update(
                    dx = dxPx / density,
                    dy = (change.position.y - down.position.y) / density,
                    elapsedMs = change.uptimeMillis - down.uptimeMillis,
                    pressed = change.pressed,
                )
                // 菜单已弹 / 滑动已选：吞掉后续事件（含抬手那一下），避免重复触发
                when (machine.phase) {
                    MtRowGesture.Phase.MENU, MtRowGesture.Phase.SWIPED -> change.consume()
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

                    MtRowGesture.Decision.LongPressMenu -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        g.itemAt(machine.downIndex)?.let { item ->
                            change.consume()
                            g.onLongPressRow(item)
                        }
                        // 不 break：继续吞事件到手指抬起（awaitEachGesture 会等全部指针抬起）
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
            }
        } finally {
            machine.cancel()
        }
    }
}
