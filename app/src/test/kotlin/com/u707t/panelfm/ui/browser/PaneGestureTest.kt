package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫选「边缘自动滚动」的速度曲线（[edgeScrollStep]）。
 *
 * 这条曲线决定长列表连选的手感：碰到边缘要慢（能微调）、越靠边越快（扫得动）、
 * 中间区域必须**完全不滚**（否则正常扫选时列表自己乱跑）。
 */
class PaneGestureTest {

    private val height = 1000f
    private val edge = 48f
    private val maxStep = 14f

    @Test
    fun `列表中间不滚动`() {
        assertNull(edgeScrollStep(y = 500f, height = height, edge = edge, maxStep = maxStep))
        assertNull(edgeScrollStep(y = 48f, height = height, edge = edge, maxStep = maxStep))
        assertNull(edgeScrollStep(y = 952f, height = height, edge = edge, maxStep = maxStep))
    }

    @Test
    fun `上边缘向上滚、下边缘向下滚，越靠边越快`() {
        val nearTop = edgeScrollStep(y = 36f, height = height, edge = edge, maxStep = maxStep)!!
        val atTop = edgeScrollStep(y = 0f, height = height, edge = edge, maxStep = maxStep)!!
        assertTrue(nearTop < 0f)
        assertTrue(atTop < 0f)
        assertEquals(-maxStep / 4f, nearTop, 0.01f)
        assertEquals(-maxStep, atTop, 0.01f)

        val nearBottom = edgeScrollStep(y = 964f, height = height, edge = edge, maxStep = maxStep)!!
        val atBottom = edgeScrollStep(y = 1000f, height = height, edge = edge, maxStep = maxStep)!!
        assertTrue(nearBottom > 0f)
        assertEquals(maxStep / 4f, nearBottom, 0.01f)
        assertEquals(maxStep, atBottom, 0.01f)
    }

    @Test
    fun `手指划出列表范围也按最靠边处理（列表上方为负、下方为正）`() {
        assertEquals(-maxStep, edgeScrollStep(y = -200f, height = height, edge = edge, maxStep = maxStep)!!, 0.01f)
        assertEquals(maxStep, edgeScrollStep(y = 1500f, height = height, edge = edge, maxStep = maxStep)!!, 0.01f)
    }

    @Test
    fun `非法尺寸不滚动（避免未布局时把列表滚飞）`() {
        assertNull(edgeScrollStep(y = 0f, height = 0f, edge = edge, maxStep = maxStep))
        assertNull(edgeScrollStep(y = 10f, height = height, edge = 0f, maxStep = maxStep))
    }
}
