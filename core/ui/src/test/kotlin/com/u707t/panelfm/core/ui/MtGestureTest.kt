package com.u707t.panelfm.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MT 手势阈值（文档附录 G.5 / F.5）的判定回归。
 *
 * 这些数字是「复刻 MT 手感」的核心：改阈值必须同步改这里，否则会静默漂移。
 */
class MtGestureTest {

    @Test
    fun `阈值与文档 G5 一致`() {
        assertEquals(400L, MtGesture.LongPressMs)
        assertEquals(12f, MtGesture.LongPressSlopDp, 0.001f)
        assertEquals(24f, MtGesture.SwipeSelectDp, 0.001f)
        assertEquals(32f, MtGesture.SwipeBookmarkDp, 0.001f)
        assertEquals(12f, MtGesture.SwipeAnimDp, 0.001f)
        assertEquals(2000L, MtGesture.PressAgainMs)
    }

    @Test
    fun `左右滑动 24dp 且横向占优才进入多选`() {
        assertTrue(MtGesture.isSwipeSelect(24f, 0f))
        assertFalse(MtGesture.isSwipeSelect(23.9f, 0f))
        assertTrue(MtGesture.isSwipeSelect(-30f, 0f))
        assertFalse(MtGesture.isSwipeSelect(24f, 13f))
        assertTrue(MtGesture.isSwipeSelect(26f, 12f))
    }

    @Test
    fun `底栏上滑书签要求 32dp 且纵向占优`() {
        assertTrue(MtGesture.isSwipeBookmark(-32f, 0f))
        assertFalse(MtGesture.isSwipeBookmark(-31f, 0f))
        assertFalse(MtGesture.isSwipeBookmark(40f, 0f))
        assertFalse(MtGesture.isSwipeBookmark(-40f, 60f))
    }
}
