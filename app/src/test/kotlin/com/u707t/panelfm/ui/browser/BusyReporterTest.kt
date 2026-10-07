package com.u707t.panelfm.ui.browser

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 长操作进度上报器（审计 U4）：
 *  - 取消必须能在「下一次上报」就生效（压缩 / 校验 / 下载循环都靠它响应取消）；
 *  - UI 写状态要节流，但第一条与阶段切换（force / note）必须立刻可见。
 */
class BusyReporterTest {

    @Test
    fun `取消后再上报会抛 CancellationException`() {
        var active = true
        val reporter = BusyReporter(
            ensureActive = { if (!active) throw CancellationException("cancelled") },
            sink = { _, _ -> },
        )
        reporter.report(1, 2) // 未取消：正常
        active = false
        assertThrows(CancellationException::class.java) { reporter.report(2, 2) }
        assertThrows(CancellationException::class.java) { reporter.note("阶段") }
    }

    @Test
    fun `第一条一定上报（假时钟从 0 开始，防 Long_MIN 溢出）`() {
        val sinks = mutableListOf<Float?>()
        val reporter = BusyReporter(
            ensureActive = {},
            sink = { f, _ -> sinks += f },
            nowMs = { 0L },
        )
        reporter.report(10, 100)
        assertEquals(1, sinks.size)
        assertEquals(0.1f, sinks[0]!!, 0.0001f)
    }

    @Test
    fun `节流：窗口内跳过，窗口外放行，force 与 note 立即上报`() {
        var now = 0L
        val sinks = mutableListOf<Pair<Float?, String?>>()
        val reporter = BusyReporter(
            ensureActive = {},
            sink = { f, d -> sinks += f to d },
            throttleMs = 100L,
            nowMs = { now },
        )
        reporter.report(10, 100, "a")
        now = 50
        reporter.report(20, 100, "b") // 被节流（< 100ms）
        now = 150
        reporter.report(50, 100, "c") // 窗口已过 → 上报
        now = 151
        reporter.note("d") // note 一定上报（进度沿用上次）
        reporter.report(80, 100, "e", force = true) // force 忽略节流
        assertEquals(4, sinks.size)
        assertEquals(0.1f, sinks[0].first!!, 0.0001f)
        assertEquals(0.5f, sinks[1].first!!, 0.0001f)
        assertEquals(0.5f, sinks[2].first!!, 0.0001f) // note 维持此前进度
        assertEquals(0.8f, sinks[3].first!!, 0.0001f)
    }

    @Test
    fun `总量未知时进度为 null（转圈），随后有总量则按比例`() {
        val sinks = mutableListOf<Float?>()
        val reporter = BusyReporter(
            ensureActive = {},
            sink = { f, _ -> sinks += f },
            throttleMs = 0L,
            nowMs = { 0L },
        )
        reporter.report(100, -1, "已处理 100")
        assertNull(sinks[0])
        reporter.report(50, 100, "50 / 100")
        assertEquals(0.5f, sinks[1]!!, 0.0001f)
    }

    @Test
    fun `busyFraction 边界：未知为 null，越界夹紧`() {
        assertNull(busyFraction(5, 0))
        assertNull(busyFraction(5, -1))
        assertEquals(0f, busyFraction(0, 100)!!, 0f)
        assertEquals(0.5f, busyFraction(50, 100)!!, 0.0001f)
        assertEquals(1f, busyFraction(100, 100)!!, 0f)
        assertEquals(1f, busyFraction(200, 100)!!, 0f)
    }
}
