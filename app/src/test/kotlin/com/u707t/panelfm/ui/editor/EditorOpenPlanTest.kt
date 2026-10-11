package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开大文件的档位判定（v2.0.16 起不再有只读分段浏览，这里锁住新的三层兜底）：
 *  - > 2 MB：自动开流畅模式（仍然可编辑）；
 *  - > 16 MB：先确认；
 *  - > 64 MB：拒绝（保护内存）。
 *
 * 边界是「超过」：正好等于阈值不触发（例如 2 MB 整的文件不自动开流畅模式）。
 */
class EditorOpenPlanTest {

    private val mb = 1024L * 1024

    @Test
    fun `小文件：直接打开，不自动开流畅模式`() {
        listOf(0L, 1L, 2 * mb - 1).forEach { size ->
            val plan = editorOpenPlan(size)
            assertEquals("$size 应直接打开", EditorOpenDecision.OPEN, plan.decision)
            assertFalse("$size 不该自动开流畅模式", plan.autoFluent)
        }
    }

    @Test
    fun `超过 2 MB：直接打开但自动开流畅模式`() {
        val plan = editorOpenPlan(2 * mb + 1)
        assertEquals(EditorOpenDecision.OPEN, plan.decision)
        assertTrue(plan.autoFluent)
        // 阈值本身不触发（大小是「超过」才算）
        assertFalse(editorOpenPlan(2 * mb).autoFluent)
    }

    @Test
    fun `超过 16 MB：先确认，且确认后同样自动开流畅模式`() {
        assertEquals(EditorOpenDecision.OPEN, editorOpenPlan(16 * mb).decision)
        val plan = editorOpenPlan(16 * mb + 1)
        assertEquals(EditorOpenDecision.CONFIRM, plan.decision)
        assertTrue(plan.autoFluent)
    }

    @Test
    fun `超过 64 MB：拒绝打开`() {
        assertEquals(EditorOpenDecision.CONFIRM, editorOpenPlan(64 * mb).decision)
        val plan = editorOpenPlan(64 * mb + 1)
        assertEquals(EditorOpenDecision.REFUSE, plan.decision)
        // 拒绝时不谈流畅模式（根本不会打开）
        assertFalse(plan.autoFluent)
    }

    @Test
    fun `档位随大小单调（打开 → 确认 → 拒绝），不会跳档`() {
        val sizes = listOf(1, 2, 3, 8, 16, 32, 64, 128).map { it * mb }
        val decisions = sizes.map { editorOpenPlan(it).decision }
        // 非降序：OPEN(0) < CONFIRM(1) < REFUSE(2)
        assertEquals(decisions.sortedBy { it.ordinal }, decisions)
        // 自动流畅模式：一旦跨过 2 MB 就一直为真（直到被拒绝）
        sizes.zip(decisions).forEach { (size, decision) ->
            if (decision != EditorOpenDecision.REFUSE) {
                assertEquals("$size 应自动开流畅模式", size > 2 * mb, editorOpenPlan(size).autoFluent)
            }
        }
    }
}
