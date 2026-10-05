package com.u707t.panelfm.core.ui

import com.u707t.panelfm.core.ui.MtRowGesture.Decision
import com.u707t.panelfm.core.ui.MtRowGesture.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表行手势判定机的回归测试。
 *
 * 这些用例就是「MT 手感」的可执行规格：**每个数字（400ms / 12dp / 24dp / 48dp）都对应
 * 一条会给用户造成困惑的行为**，改阈值前先看这里会不会红。
 */
class MtRowGestureTest {

    private fun machine() = MtRowGesture(
        touchSlopDp = 8f,
        longPressSlopDp = 12f,
        swipeSelectDp = 24f,
        swipeMenuDp = 48f,
        longPressMs = 400L,
    )

    // ------------------------------------------------------------------ 点按 / 长按

    @Test
    fun `轻点：不动的按下与松手判定为点按`() {
        val m = machine()
        m.begin(downIndex = 2, selectingAtStart = false)
        assertEquals(Decision.None, m.update(dx = 0f, dy = 0f, elapsedMs = 0, pressed = true, index = 2))
        assertEquals(Decision.Tap, m.update(dx = 1f, dy = 0f, elapsedMs = 120, pressed = false, index = 2))
        // 结束后不再派发
        assertEquals(Decision.None, m.update(dx = 1f, dy = 0f, elapsedMs = 130, pressed = false, index = 2))
    }

    @Test
    fun `按住 400ms 先震动提示，松手才弹菜单`() {
        val m = machine()
        m.begin(downIndex = 0, selectingAtStart = false)
        assertEquals(Decision.LongPressArmed, m.update(0f, 0f, 400, true, 0))
        assertEquals(Decision.None, m.update(0f, 0f, 600, true, 0))
        assertEquals(Decision.LongPress, m.update(0f, 0f, 700, false, 0))
    }

    @Test
    fun `事件稀疏时（按住 500ms 直接松手）也要判成长按，而不是点按`() {
        val m = machine()
        m.begin(downIndex = 0, selectingAtStart = false)
        assertEquals(Decision.LongPress, m.update(0f, 0f, 500, false, 0))
    }

    @Test
    fun `按在列表空白处不会触发长按（不震动、不弹菜单）`() {
        val m = machine()
        m.begin(downIndex = -1, selectingAtStart = false)
        assertEquals(Decision.None, m.update(0f, 0f, 500, true, -1))
        // 松手只判成点按，由调用方按「未命中行」忽略（交给 `..` 行自己的 clickable）
        assertEquals(Decision.Tap, m.update(0f, 0f, 600, false, -1))
    }

    @Test
    fun `长按成立后，移动不再改判为扫选`() {
        val m = machine()
        m.begin(downIndex = 1, selectingAtStart = false)
        assertEquals(Decision.LongPressArmed, m.update(0f, 0f, 400, true, 1))
        assertEquals(Decision.None, m.update(80f, 0f, 450, true, 1))
        assertEquals(Decision.LongPress, m.update(80f, 0f, 460, false, 1))
    }

    @Test
    fun `拖过 12dp 后松手：既不是点按也不是滑动（防误触）`() {
        val m = machine()
        m.begin(downIndex = 3, selectingAtStart = false)
        assertEquals(Decision.None, m.update(dx = 15f, dy = 0f, elapsedMs = 90, pressed = true, index = 3))
        assertEquals(Decision.None, m.update(dx = 15f, dy = 0f, elapsedMs = 100, pressed = false, index = 3))
    }

    // ------------------------------------------------------------------ 滑动进入多选 / 跟手区间

    @Test
    fun `左右滑动 24dp（横向占优）进入多选`() {
        val m = machine()
        m.begin(downIndex = 4, selectingAtStart = false)
        assertEquals(Decision.None, m.update(dx = 23f, dy = 0f, elapsedMs = 40, pressed = true, index = 4))
        assertEquals(Decision.EnterSweep, m.update(dx = 25f, dy = 1f, elapsedMs = 60, pressed = true, index = 4))
        assertEquals(Phase.SWEEP, m.phase)
        // 左滑同样可以进入（MT 0x7f1106f3「左右滑动」）
        val n = machine()
        n.begin(downIndex = 4, selectingAtStart = false)
        assertEquals(Decision.EnterSweep, n.update(dx = -30f, dy = 0f, elapsedMs = 60, pressed = true, index = 4))
    }

    @Test
    fun `按在空白处（未命中行）横滑不会进入多选`() {
        val m = machine()
        m.begin(downIndex = -1, selectingAtStart = false)
        assertEquals(Decision.None, m.update(dx = 60f, dy = 0f, elapsedMs = 60, pressed = true, index = -1))
    }

    @Test
    fun `扫选跟手：落到新行才派发 SweepTo，同一行不重复`() {
        val m = machine()
        m.begin(downIndex = 4, selectingAtStart = false)
        assertEquals(Decision.EnterSweep, m.update(30f, 0f, 50, true, 4))
        assertEquals(Decision.None, m.update(34f, 40f, 70, true, 4))
        assertEquals(Decision.SweepTo(5), m.update(34f, 60f, 90, true, 5))
        assertEquals(Decision.None, m.update(34f, 62f, 100, true, 5))
        assertEquals(Decision.SweepTo(6), m.update(34f, 110f, 120, true, 6))
        // 往回滑也要派发（区间跟随手指收回去）
        assertEquals(Decision.SweepTo(5), m.update(34f, 60f, 140, true, 5))
        assertEquals(Decision.SweepEnd, m.update(34f, 60f, 150, false, 5))
    }

    @Test
    fun `边缘自动滚动导致的换行用 indexChanged 派发`() {
        val m = machine()
        m.begin(downIndex = 4, selectingAtStart = false)
        m.update(30f, 0f, 50, true, 4)
        assertEquals(Decision.SweepTo(7), m.indexChanged(7))
        assertEquals(Decision.None, m.indexChanged(7))
        // 没在扫选时（例如手势已结束）不再派发
        m.update(30f, 0f, 150, false, 7)
        assertEquals(Decision.None, m.indexChanged(8))
    }

    // ------------------------------------------------------------------ 右滑出菜单（0x7f110697）

    @Test
    fun `右滑很远但纵向也大时仍不弹菜单`() {
        val m = machine()
        m.begin(downIndex = 2, selectingAtStart = true)
        assertEquals(Decision.EnterSweep, m.update(30f, 0f, 50, true, 2))
        // 横向 100dp（远超 48dp），但纵向 49dp 也已经不小 → 判定为「扫选」而不是「右滑出菜单」
        assertEquals(Decision.SweepTo(4), m.update(100f, 49f, 90, true, 4))
        assertEquals(Phase.SWEEP, m.phase)
    }

    @Test
    fun `已多选态右滑 48dp 出菜单，且一次手势只出一次`() {
        val m = machine()
        m.begin(downIndex = 2, selectingAtStart = true)
        assertEquals(Decision.EnterSweep, m.update(30f, 0f, 50, true, 2))
        assertEquals(Decision.SwipeMenu, m.update(49f, 3f, 90, true, 2))
        assertEquals(Phase.DONE, m.phase)
        assertEquals(Decision.None, m.update(60f, 3f, 120, true, 2))
    }

    @Test
    fun `未进入多选时右滑 48dp 只做扫选，不弹菜单`() {
        val m = machine()
        m.begin(downIndex = 2, selectingAtStart = false)
        assertEquals(Decision.EnterSweep, m.update(30f, 0f, 50, true, 2))
        assertEquals(Decision.None, m.update(49f, 3f, 90, true, 2))
        assertEquals(Phase.SWEEP, m.phase)
    }

    @Test
    fun `纵向位移过大时不弹菜单（避免向下扫选区间误触）`() {
        val m = machine()
        m.begin(downIndex = 2, selectingAtStart = true)
        assertEquals(Decision.EnterSweep, m.update(30f, 0f, 50, true, 2))
        // 斜着往右下扫：横向虽然到 50dp，但纵向也不小 → 不应弹菜单（只按扫选处理）
        assertEquals(Decision.SweepTo(3), m.update(50f, 49f, 90, true, 3))
        assertEquals(Phase.SWEEP, m.phase)
    }

    // ------------------------------------------------------------------ 纵向让位

    @Test
    fun `纵向拖动交给列表滚动，此后不再派发任何判定`() {
        val m = machine()
        m.begin(downIndex = 5, selectingAtStart = false)
        assertEquals(Decision.HandOff, m.update(2f, 20f, 60, true, 6))
        assertEquals(Phase.HANDOFF, m.phase)
        assertEquals(Decision.None, m.update(2f, 60f, 120, true, 9))
        assertEquals(Decision.None, m.update(2f, 60f, 140, false, 9))
    }

    @Test
    fun `斜向拖动：纵向占优时让位，横向占优时才扫选`() {
        val vertical = machine()
        vertical.begin(downIndex = 1, selectingAtStart = false)
        assertEquals(Decision.HandOff, vertical.update(25f, 30f, 80, true, 2))

        val horizontal = machine()
        horizontal.begin(downIndex = 1, selectingAtStart = false)
        assertEquals(Decision.EnterSweep, horizontal.update(25f, 12f, 80, true, 1))
    }
}
