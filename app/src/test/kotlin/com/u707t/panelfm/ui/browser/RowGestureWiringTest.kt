package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import com.u707t.panelfm.core.ui.MtRowGesture
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 行手势**接线层**（[detectRowGesture]）的回归测试。
 *
 * 覆盖 v2.0.0 实机上报的两个相邻 bug（判定机 `MtRowGestureTest` 全绿也照样复现，
 * 说明问题不在判定机、而在触摸生命周期）：
 *
 *  1. **滑动选择必须滑 2 次** —— 滑动选中后，紧接着的那一次触摸被整个吞掉；
 *  2. **滑第二项有一整段固定无响应的延迟** —— 同上：被吞掉的那一次就是「第二滑」，
 *     从按下到抬手全程没有任何判定派发。
 *
 * 根因：一次触摸结束后手势循环没有退出，下一次触摸的第一个事件被 stale 循环收到
 * （PointerId 每次触摸都是新值）→ 循环 `?: break` → `awaitEachGesture` 的
 * `awaitAllPointersUp()` 再把这次触摸整段等到抬手才放行。修法见 [detectRowGesture] 注释。
 *
 * 测试手段（为什么可信）：
 *  - 驱动的是**生产代码本身**：真 `awaitEachGesture` + 真 [detectRowGesture]，
 *    只把 `PointerInputScope` / `AwaitPointerEventScope` 换成脚本化的假实现
 *    （`awaitEachGesture` 的内部收尾 `awaitAllPointersUp()` 也因此是真实行为）；
 *  - 复刻关键真实语义：**每次触摸的 PointerId 都是新值**（`MotionEventAdapter` 的
 *    nextId 单调递增，抬手即回收映射 —— 这正是旧实现踩坑的地方）。
 *
 * ⚠️ 旧实现（循环抬手后不退出）下，本文件前两个用例会红 —— 它们就是这两个实机 bug
 * 的可执行规格。
 */
class RowGestureWiringTest {

    // ------------------------------------------------------------------ 滑动选中（离散、两连滑）

    @Test
    fun `滑动选中后紧接第二次滑动：第二次必须立即生效（实机：不许滑两次）`() = runTest {
        val swipes = mutableListOf<String>()
        // 第一次滑动选中后的抬手事件（留引用：断言「含抬手一起吞」）
        val up1 = change(id = 1L, x = 32f, y = 100f, pressed = false, previousPressed = true, t = 90)
        val scope = FakePointerScope(
            script = ArrayDeque(
                listOf(
                    // 触摸 1：滑 a（id = 1）
                    event(change(1L, 0f, 100f, pressed = true, previousPressed = false, t = 0)),
                    event(change(1L, 30f, 100f, pressed = true, previousPressed = true, t = 50)),
                    event(up1),
                    // 触摸 2：滑 b（**新** PointerId = 2）
                    event(change(2L, 0f, 200f, pressed = true, previousPressed = false, t = 300)),
                    event(change(2L, 30f, 200f, pressed = true, previousPressed = true, t = 350)),
                    event(change(2L, 32f, 200f, pressed = false, previousPressed = true, t = 390)),
                )
            )
        )

        runTouchStream(scope, gesturesOf(listOf(item("a"), item("b")), swipes = swipes))

        assertEquals(
            "第二次滑动被上一次手势的循环吞掉了（实机表现：滑动选择必须滑两次）",
            listOf("a", "b"),
            swipes,
        )
        // SWIPED 期间连抬手的那个事件也要吞掉（旧实现判定完才读阶段，漏吞了抬手）
        assertTrue("滑动选中的抬手事件应被消费（避免内层再收到半截手势）", up1.isConsumed)
    }

    // ------------------------------------------------------------------ 长按弹菜单后的下一次触摸

    @Test
    fun `长按弹菜单后：紧接着的一次点按仍然生效（不许被上一回吞掉）`() = runTest {
        val longPressed = mutableListOf<String>()
        val tapped = mutableListOf<String>()
        val menuUp = change(id = 1L, x = 0f, y = 100f, pressed = false, previousPressed = true, t = 500)
        val scope = FakePointerScope(
            script = ArrayDeque(
                listOf(
                    // 触摸 1：按住 a 到 450ms → 立刻弹二级菜单
                    event(change(1L, 0f, 100f, pressed = true, previousPressed = false, t = 0)),
                    event(change(1L, 0f, 100f, pressed = true, previousPressed = true, t = 450)),
                    event(menuUp),
                    // 触摸 2：点按 b（新 PointerId）
                    event(change(2L, 0f, 200f, pressed = true, previousPressed = false, t = 700)),
                    event(change(2L, 0f, 200f, pressed = false, previousPressed = true, t = 740)),
                )
            )
        )

        runTouchStream(
            scope,
            gesturesOf(listOf(item("a"), item("b")), tapped = tapped, longPressed = longPressed),
        )

        assertEquals(listOf("a"), longPressed)
        assertEquals(
            "长按菜单之后的第一次点按被上一回手势的循环吞掉了（与「必须滑两次」同一根因）",
            listOf("b"),
            tapped,
        )
        assertTrue("MENU 期间的抬手事件应被消费", menuUp.isConsumed)
    }

    // ------------------------------------------------------------------ 空白处点按（让位给 `..` 行）

    @Test
    fun `空白处点按不消费事件（让位给下方 clickable），且下一次滑动不受影响`() = runTest {
        val swipes = mutableListOf<String>()
        val tapped = mutableListOf<String>()
        val blankUp = change(id = 1L, x = 0f, y = 400f, pressed = false, previousPressed = true, t = 80)
        val scope = FakePointerScope(
            script = ArrayDeque(
                listOf(
                    // 触摸 1：点按空白（y = 400 未命中任何行）
                    event(change(1L, 0f, 400f, pressed = true, previousPressed = false, t = 0)),
                    event(blankUp),
                    // 触摸 2：滑 a（新 PointerId）
                    event(change(2L, 0f, 100f, pressed = true, previousPressed = false, t = 300)),
                    event(change(2L, 30f, 100f, pressed = true, previousPressed = true, t = 350)),
                    event(change(2L, 32f, 100f, pressed = false, previousPressed = true, t = 390)),
                )
            )
        )

        runTouchStream(scope, gesturesOf(listOf(item("a"), item("b")), tapped = tapped, swipes = swipes))

        assertEquals(emptyList<String>(), tapped)
        assertFalse("空白处点按不能消费事件（`..` 行 / 其它 clickable 要自己收到）", blankUp.isConsumed)
        assertEquals(listOf("a"), swipes)
    }

    // ------------------------------------------------------------------ 静止长按（计时路径，v2.0.14）

    /**
     * 实机 bug（v2.0.14 修复）：手指按住后**完全静止**时没有任何 MOVE 事件，旧实现只在
     * 「事件到达」时推进判定机 —— 长按要等下一个事件（实机上往往就是抬手）才弹菜单，
     * 观感是「偶尔要松手才弹 / 触发很慢」。
     *
     * 本用例的脚本**只有按下、没有第二个事件**：注入的假等待在"脚本已空"时返回 null
     * （= 计时到点仍无事件），驱动生产的静止长按分支；随后手势层继续等事件、脚本耗尽
     * 正常收场。断言菜单已派发 —— 语义 = 「不依赖任何后续事件，尤其不依赖抬手」。
     *
     * 说明：为什么不直接跑真实计时 —— 受限挂起块（restricted）的上下文为空，测试环境里
     * `withTimeoutOrNull` 的计时不可控；生产的默认等待 [awaitEventOrLongPressTimeout]
     * 与 foundation 自身的长按超时是同一机制，由实机验证。
     */
    @Test
    fun `静止长按：无任何后续事件，计时到点必须弹菜单（不许等松手）`() = runTest {
        val longPressed = mutableListOf<String>()
        val script = ArrayDeque(
            listOf(
                event(change(1L, 0f, 100f, pressed = true, previousPressed = false, t = 0)),
            )
        )
        val scope = FakePointerScope(script)

        runTouchStream(
            scope,
            gesturesOf(listOf(item("a")), longPressed = longPressed),
            // 假等待：脚本里没有事件 = “计时到点仍无事件” → null（确定性驱动静止长按分支）
            awaitNext = { _ ->
                if (script.isEmpty()) null else awaitPointerEvent(PointerEventPass.Initial)
            },
        )

        assertEquals(
            "手指静止时计时到点必须弹菜单（旧实现要等下一个事件 / 松手）",
            listOf("a"),
            longPressed,
        )
    }
}

// --------------------------------------------------------------------------- 测试基础设施
// 「假 Compose 指针作用域」：脚本化事件流，驱动**真实**的 awaitEachGesture + detectRowGesture。
// 仅测试使用。

/** 事件脚本用完：手势层还在等事件 —— 说明它没有回到「等下一次触摸」的状态。 */
private class ScriptExhausted : RuntimeException("事件脚本已空：手势层还在等不存在的下一次触摸")

/**
 * 假的手势作用域：按脚本顺序派发事件，其余语义与 Compose 一致（结构上是 PointerInputScope
 * + 内存中的 AwaitPointerEventScope，`awaitEachGesture` 的循环与收尾都是真的）。
 *
 * - `awaitPointerEvent` 对任何 pass 都返回下一个脚本事件（本测试不关心 pass 交错细节，
 *   只关心「哪个事件被哪一次触摸消费 / 派发」）；
 * - 复刻真实语义：**每次触摸的 PointerId 都是新值**（`MotionEventAdapter` 的 nextId
 *   单调递增，抬手回收映射后再按会拿到新号）—— 用脚本里的 id 递增模拟。
 */
private class FakePointerScope(private val script: ArrayDeque<PointerEvent>) : PointerInputScope {

    override val size: IntSize = IntSize(1080, 1920)

    override val viewConfiguration: ViewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis: Long = 400L
        override val doubleTapTimeoutMillis: Long = 300L
        override val doubleTapMinTimeMillis: Long = 40L
        override val touchSlop: Float = 8f
    }

    override val density: Float = 1f
    override val fontScale: Float = 1f

    private var last: PointerEvent = PointerEvent(emptyList())

    private val inner = object : AwaitPointerEventScope {
        override val size: IntSize get() = this@FakePointerScope.size
        override val viewConfiguration: ViewConfiguration get() = this@FakePointerScope.viewConfiguration
        override val density: Float get() = this@FakePointerScope.density
        override val fontScale: Float get() = this@FakePointerScope.fontScale
        override val currentEvent: PointerEvent get() = last
        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
            last = script.removeFirstOrNull() ?: throw ScriptExhausted()
            return last
        }
    }

    override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
        suspendCoroutine { cont ->
            // 与真实实现同款：restricted（@RestrictsSuspension）挂起块必须用
            // createCoroutine 启动，且 completion 的 context 必须为空 —— 直接
            // `block(inner)` 会因为「restricted 协程上下文非空」在运行时被拒。
            val completion = object : Continuation<R> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<R>) {
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
            }
            block.createCoroutine(inner, completion).resume(Unit)
        }
}

/**
 * 与生产代码**同构**的入口：`awaitEachGesture { detectRowGesture(...) }`。
 * 脚本用完时手势层回到「等下一次触摸」（抛 [ScriptExhausted]）—— 正常收场；
 * 用例的失败信号一律来自「本该派发的回调没派发」的断言。
 */
private suspend fun runTouchStream(
    scope: FakePointerScope,
    gestures: ListGestures,
    /** 「等事件或长按计时到点」的等待实现（测试注入点；默认 = 生产真实计时等待） */
    awaitNext: (suspend AwaitPointerEventScope.(Long) -> PointerEvent?)? = null,
) {
    val machine = MtRowGesture(
        touchSlopDp = 8f,
        longPressSlopDp = 12f,
        swipeSelectDp = 24f,
        longPressMs = 400L,
    )
    try {
        scope.awaitEachGesture {
            detectRowGesture(machine, { gestures }, NoHaptic, awaitNext = awaitNext)
        }
    } catch (_: ScriptExhausted) {
        // 事件流空了：手势层在等下一次触摸 —— 收场
    }
}

/**
 * 行 i 的命中区 = y ∈ [i×100 + 50, i×100 + 150)；之外 = 未命中（-1）。
 * 与用例里的坐标一一对应（a 在 y=100，b 在 y=200，空白用 y=400）。
 */
private fun gesturesOf(
    items: List<FileMetadata>,
    tapped: MutableList<String> = mutableListOf(),
    longPressed: MutableList<String> = mutableListOf(),
    swipes: MutableList<String> = mutableListOf(),
): ListGestures = ListGestures(
    enabled = { true },
    indexAtY = { y -> items.indices.firstOrNull { y >= it * 100f + 50f && y < it * 100f + 150f } ?: -1 },
    itemAt = { index -> items.getOrNull(index) },
    onTapRow = { tapped += it.name },
    onLongPressRow = { longPressed += it.name },
    onSwipeSelect = { item, _ -> swipes += item.name },
)

private fun item(name: String) = FileMetadata(
    uri = VfsUri.parse("local:///dir/$name"),
    name = name,
    isDirectory = false,
    size = 1,
    lastModified = 0,
)

private fun change(
    id: Long,
    x: Float,
    y: Float,
    pressed: Boolean,
    previousPressed: Boolean,
    t: Long,
): PointerInputChange = PointerInputChange(
    id = PointerId(id),
    uptimeMillis = t,
    position = Offset(x, y),
    pressed = pressed,
    pressure = 1f,
    previousUptimeMillis = t,
    previousPosition = Offset(x, y),
    previousPressed = previousPressed,
    isInitiallyConsumed = false,
)

private fun event(change: PointerInputChange): PointerEvent = PointerEvent(listOf(change))

private object NoHaptic : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
