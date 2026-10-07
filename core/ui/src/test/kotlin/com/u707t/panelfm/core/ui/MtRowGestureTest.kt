package com.u707t.panelfm.core.ui

import com.u707t.panelfm.core.ui.MtRowGesture.Decision
import com.u707t.panelfm.core.ui.MtRowGesture.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表行手势判定机的回归测试（用户实机确认的 MT 语义）。
 *
 * 这些用例就是「MT 手感」的可执行规格：**每个数字（400ms / 12dp / 24dp）都对应
 * 一条会给用户造成困惑的行为**，改阈值前先看这里会不会红。
 *
 * 三条主线：
 *  1. **长按 = 立刻弹二级菜单**（到达阈值那一刻，不等松手；不改选择）；
 *  2. **左右滑动 = 离散的「选中/连选」**（不是按住一路刷）；
 *  3. 纵向拖动 = 让位给列表滚动。
 */
class MtRowGestureTest {

    private fun machine() = MtRowGesture(
        touchSlopDp = 8f,
        longPressSlopDp = 12f,
        swipeSelectDp = 24f,
        longPressMs = 400L,
    )

    // ------------------------------------------------------------------ 点按

    @Test
    fun `轻点：不动的按下与松手判定为点按`() {
        val m = machine()
        m.begin(downIndex = 2)
        assertEquals(Decision.None, m.update(dx = 0f, dy = 0f, elapsedMs = 0, pressed = true))
        assertEquals(Decision.Tap, m.update(dx = 1f, dy = 0f, elapsedMs = 120, pressed = false))
        // 结束后不再派发
        assertEquals(Decision.None, m.update(dx = 1f, dy = 0f, elapsedMs = 130, pressed = false))
    }

    @Test
    fun `拖过容差但没到任何阈值时松手 = 什么都不做`() {
        val m = machine()
        m.begin(downIndex = 2)
        assertEquals(Decision.None, m.update(dx = 14f, dy = 0f, elapsedMs = 100, pressed = true))
        assertEquals(Decision.None, m.update(dx = 14f, dy = 0f, elapsedMs = 140, pressed = false))
    }

    // ------------------------------------------------------------------ 长按（弹菜单）

    @Test
    fun `长按到达阈值立刻弹菜单，不等松手`() {
        val m = machine()
        m.begin(downIndex = 0)
        assertEquals(Decision.None, m.update(0f, 0f, 399, true))
        assertEquals(Decision.LongPressMenu, m.update(0f, 0f, 400, true))
        assertEquals(Phase.MENU, m.phase)
        // 手指还按着：不再重复派发
        assertEquals(Decision.None, m.update(0f, 0f, 800, true))
        // 松手后回到 DONE
        m.update(0f, 0f, 900, false)
        assertEquals(Phase.DONE, m.phase)
    }

    @Test
    fun `事件稀疏时（按住 500ms 直接松手）也要判成长按，而不是点按`() {
        val m = machine()
        m.begin(downIndex = 0)
        assertEquals(Decision.LongPressMenu, m.update(0f, 0f, 500, false))
    }

    @Test
    fun `按在列表空白处不会触发长按（不弹菜单）`() {
        val m = machine()
        m.begin(downIndex = -1)
        assertEquals(Decision.None, m.update(0f, 0f, 500, true))
        // 松手只判成点按，由调用方按「未命中行」忽略（交给 `..` 行自己的 clickable）
        assertEquals(Decision.Tap, m.update(0f, 0f, 600, false))
    }

    @Test
    fun `菜单弹出后继续拖动不会误触发滑动选中`() {
        val m = machine()
        m.begin(downIndex = 0)
        assertEquals(Decision.LongPressMenu, m.update(0f, 0f, 400, true))
        assertEquals(Decision.None, m.update(60f, 0f, 460, true))
        assertEquals(Decision.None, m.update(60f, 0f, 520, true))
    }

    // ------------------------------------------------------------------ 滑动选中（离散）

    @Test
    fun `左右滑动过 24dp 触发滑动选中，并带上方向`() {
        val m = machine()
        m.begin(downIndex = 3)
        assertEquals(Decision.None, m.update(10f, 0f, 40, true))
        assertEquals(Decision.SwipeSelect(towardRight = true), m.update(26f, 0f, 80, true))
        assertEquals(Phase.SWIPED, m.phase)
    }

    @Test
    fun `左滑同样触发（方向为左）`() {
        val m = machine()
        m.begin(downIndex = 3)
        assertEquals(Decision.SwipeSelect(towardRight = false), m.update(-30f, 0f, 60, true))
    }

    @Test
    fun `一次手势只触发一次滑动选中（不会按住一路刷）`() {
        val m = machine()
        m.begin(downIndex = 3)
        assertEquals(Decision.SwipeSelect(towardRight = true), m.update(30f, 0f, 60, true))
        // 继续横向 / 纵向拖动都不再派发任何动作
        assertEquals(Decision.None, m.update(80f, 0f, 120, true))
        assertEquals(Decision.None, m.update(80f, 40f, 160, true))
        m.update(80f, 40f, 200, false)
        assertEquals(Phase.DONE, m.phase)
    }

    @Test
    fun `按在空白处滑动不算（避免列表空白处的误触）`() {
        val m = machine()
        m.begin(downIndex = -1)
        assertEquals(Decision.None, m.update(40f, 0f, 80, true))
    }

    // ------------------------------------------------------------------ 纵向让位

    @Test
    fun `纵向过 touchSlop 且纵向占优 → 让位给列表滚动`() {
        val m = machine()
        m.begin(downIndex = 5)
        assertEquals(Decision.HandOff, m.update(2f, 12f, 60, true))
        assertEquals(Phase.HANDOFF, m.phase)
        // 让位之后不再判定
        assertEquals(Decision.None, m.update(2f, 40f, 120, true))
    }

    @Test
    fun `斜向但纵向占优时仍然让位（滚列表优先于选中）`() {
        val m = machine()
        m.begin(downIndex = 5)
        assertEquals(Decision.HandOff, m.update(10f, 14f, 60, true))
    }

    @Test
    fun `滑动阈值判定：横向必须占优且过 24dp`() {
        val m = machine()
        m.begin(downIndex = 1)
        // 24dp 但纵向也大（|dx| ≤ 2|dy|）→ 不算滑动选中
        assertEquals(Decision.None, m.update(24f, 13f, 80, true))
        m.cancel()
        m.begin(downIndex = 1)
        assertEquals(Decision.SwipeSelect(towardRight = true), m.update(26f, 12f, 80, true))
    }
}
