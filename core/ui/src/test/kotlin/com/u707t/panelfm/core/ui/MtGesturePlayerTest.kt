package com.u707t.panelfm.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器手势的统一判定（v1.3.6）：
 *  - 死区 8dp：位移不够**什么都不触发**（进度 / 音量 / 亮度都不灵）；
 *  - 主轴优势比 1.4：斜滑（dx≈dy）保持未定态，避免在功能之间跳变；
 *  - 灵敏度：进度 3px = 1 秒；音量 / 亮度满量程 = 手势区高度的 45%（v2.3.3 起按 dp 换算，
 *    原来误把 IRIS 的逻辑像素当物理 px，2.75x 屏上过灵 2.75 倍）。
 */
class MtGesturePlayerTest {

    @Test
    fun `死区内不判定方向`() {
        assertNull(MtGesture.playerAxis(0f, 0f))
        assertNull(MtGesture.playerAxis(5f, 5f))
        assertNull(MtGesture.playerAxis(7.9f, -3f))
    }

    @Test
    fun `明显横滑判定为进度`() {
        assertEquals(true, MtGesture.playerAxis(30f, 5f))
        assertEquals(true, MtGesture.playerAxis(-40f, 10f))
    }

    @Test
    fun `明显竖滑判定为音量亮度`() {
        assertEquals(false, MtGesture.playerAxis(5f, 30f))
        assertEquals(false, MtGesture.playerAxis(-6f, -50f))
    }

    @Test
    fun `斜滑保持未定态不再横跳`() {
        // dx 与 dy 接近时，两个方向都不该锁定（旧实现取绝对值大者会来回跳）
        assertNull(MtGesture.playerAxis(20f, 20f))
        assertNull(MtGesture.playerAxis(20f, 16f))
        assertNull(MtGesture.playerAxis(-18f, 20f))
    }

    @Test
    fun `刚好越过优势比可以锁定`() {
        // 20 > 14 * 1.4 = 19.6 → 横滑
        assertEquals(true, MtGesture.playerAxis(20f, 14f))
        // 15.4 与 11：15.4 > 11*1.4=15.4 不成立（相等）→ 未定
        assertNull(MtGesture.playerAxis(15.4f, 11f))
    }

    @Test
    fun `进度灵敏度固定为每 3px 一秒`() {
        assertEquals(0L, MtGesture.seekDeltaSeconds(0f))
        assertEquals(10L, MtGesture.seekDeltaSeconds(30f))
        assertEquals(-10L, MtGesture.seekDeltaSeconds(-30f))
        // 与屏幕宽度无关：同样 300px 永远是 100 秒
        assertEquals(100L, MtGesture.seekDeltaSeconds(300f))
    }

    @Test
    fun `音量亮度满量程按手势区高度换算`() {
        // 800dp 高的机身：0.45 × 800 = 360dp 走完 0 → 100%
        assertEquals(360f, MtGesture.levelFullScaleDp(800f), 0.001f)
        // 小屏按比例缩小，但不少于下限（再小就「一碰到底」）
        assertEquals(260f, MtGesture.levelFullScaleDp(500f), 0.001f)
        // 平板封顶，不至于要滑半屏
        assertEquals(480f, MtGesture.levelFullScaleDp(1600f), 0.001f)
    }

    @Test
    fun `音量增量按满量程比例换算`() {
        assertEquals(1f, MtGesture.levelDelta(-360f, 360f), 0.001f)
        assertEquals(-1f, MtGesture.levelDelta(360f, 360f), 0.001f)
        assertEquals(0.5f, MtGesture.levelDelta(-180f, 360f), 0.001f)
        assertEquals(0f, MtGesture.levelDelta(0f, 360f), 0.001f)
    }

    @Test
    fun `轻滑 24dp 只走几个百分点`() {
        // 回归：旧实现把 IRIS 的「200 逻辑像素」当成物理 px，2.75x 屏上 200px ≈ 73dp，
        // 24dp（约 6mm）滑一下 = 33% 音量 —— 真机反馈的「轻滑幅度太大」。
        // 现在同样的位移是 6.7%（25 档机型约 1.7 档）。
        assertEquals(0.067f, MtGesture.levelDelta(-24f, 360f), 0.001f)
    }

    @Test
    fun `三种手势的进入难度一致`() {
        // 同一段位移（40dp）在横 / 竖两个方向上都能进入，且判定互斥
        assertTrue(MtGesture.playerAxis(40f, 0f) == true)
        assertFalse(MtGesture.playerAxis(0f, 40f) == true)
    }
}
