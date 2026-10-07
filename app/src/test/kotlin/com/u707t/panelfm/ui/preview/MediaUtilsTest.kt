package com.u707t.panelfm.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放器工具纯函数的回归（2026-10-08 重审 §2 拆分后新增）：
 * 时间格式（clock）/ 图片采样（sampleToFit）/ 数据源剩余字节（computeRemaining）。
 */
class MediaUtilsTest {

    // ------------------------------------------------------------------ clock

    @Test
    fun `clock 分秒与小时格式`() {
        assertEquals("0:00", clock(0))
        assertEquals("0:01", clock(1_000))
        assertEquals("1:01", clock(61_000))
        assertEquals("59:59", clock(3_599_000))
        assertEquals("1:00:00", clock(3_600_000))
        assertEquals("1:01:01", clock(3_661_000))
    }

    @Test
    fun `clock 负数按 0 处理（播放器未就绪时不出负数）`() {
        assertEquals("0:00", clock(-1))
        assertEquals("0:00", clock(-5_000))
    }

    // ------------------------------------------------------------------ sampleToFit

    @Test
    fun `采样：不超目标边界的图不降采样`() {
        assertEquals(1, sampleToFit(800, 600, 2048))
        assertEquals(1, sampleToFit(2048, 1536, 2048))
    }

    @Test
    fun `采样：超过目标边界后按 2 的幂降采样`() {
        assertEquals(2, sampleToFit(4096, 3072, 2048))
        // 8192：sample 2 → 4096（仍 ≥ 2048）→ 4 → 输出 2048
        assertEquals(4, sampleToFit(8192, 100, 2048))
        // 65536：一路翻倍到 32 → 输出 2048
        assertEquals(32, sampleToFit(65536, 65536, 2048))
    }

    // ------------------------------------------------------------------ computeRemaining

    @Test
    fun `剩余字节：显式 length 优先`() {
        assertEquals(1024L, computeRemaining(1024L, 10_000L, 0L))
        assertEquals(512L, computeRemaining(512L, null, 7L))
    }

    @Test
    fun `剩余字节：未知 length 时按总长减偏移（不低于 0）`() {
        assertEquals(6_000L, computeRemaining(-1L, 10_000L, 4_000L))
        assertEquals(0L, computeRemaining(-1L, 100L, 500L))
    }

    @Test
    fun `剩余字节：总长未知或非正 → -1（读到 EOF）`() {
        assertEquals(-1L, computeRemaining(-1L, null, 0L))
        assertEquals(-1L, computeRemaining(-1L, 0L, 0L))
        assertEquals(-1L, computeRemaining(-1L, -5L, 0L))
    }
}
