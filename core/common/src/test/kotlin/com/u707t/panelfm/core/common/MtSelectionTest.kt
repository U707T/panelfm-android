package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MT 选择模型的回归测试（见 [MtSelection] 的类注释：语义依据 MT 字符串 `0x7f11062f/630/631/632/6f3`）。
 *
 * 重点盯两组容易退化的性质：
 *  1. **追加语义**：扫选 / 连选都不许把用户之前选好的项抹掉（旧实现就是在这里踩坑）；
 *  2. **跟手语义**：手指退回锚点时，区间要跟着收回去。
 */
class MtSelectionTest {

    private val keys = listOf("a", "b", "c", "d", "e")

    @Test
    fun `切换单项：选中与取消互为逆操作`() {
        assertEquals(setOf("b"), MtSelection.toggle(emptySet(), "b"))
        assertEquals(emptySet<String>(), MtSelection.toggle(setOf("b"), "b"))
        assertEquals(setOf("a", "b"), MtSelection.toggle(setOf("a"), "b"))
    }

    @Test
    fun `反选 = 相对当前列表取补集（游离键被清掉）`() {
        assertEquals(setOf("b", "d"), MtSelection.invert(setOf("a", "c", "e"), listOf("a", "b", "c", "d", "e")))
        // 列表变化后，已不在列表中的 key 不该留在反选结果里（ghost 被丢弃，a 仍被排除）
        assertEquals(setOf("b", "c"), MtSelection.invert(setOf("a", "ghost"), listOf("a", "b", "c")))
        assertEquals(setOf("a", "b"), MtSelection.invert(emptySet(), listOf("a", "b")))
    }

    @Test
    fun `区间：两端闭区间，方向无关`() {
        assertEquals(setOf("b", "c", "d"), MtSelection.rangeKeys(keys, "b", "d"))
        assertEquals(setOf("b", "c", "d"), MtSelection.rangeKeys(keys, "d", "b"))
        assertEquals(setOf("c"), MtSelection.rangeKeys(keys, "c", "c"))
        assertEquals(setOf("a", "b", "c", "d", "e"), MtSelection.rangeKeys(keys, "e", "a"))
    }

    @Test
    fun `区间：端点失效时宁可不选（返回 null 或原样返回）`() {
        assertNull(MtSelection.rangeKeys(keys, "a", "ghost"))
        assertNull(MtSelection.rangeKeys(keys, "ghost", "a"))
        // 锚点失效（刷新后）时，连选不许在错误区间上乱选
        assertEquals(setOf("a"), MtSelection.unionRange(setOf("a"), keys, "ghost", "c"))
    }

    @Test
    fun `连选是追加：已有选择不会被区间覆盖`() {
        val selection = setOf("a", "e")
        assertEquals(setOf("a", "c", "d", "e"), MtSelection.unionRange(selection, keys, "c", "d"))
    }

    @Test
    fun `滑动进入多选：按下那一行立刻选中，且不推翻已有选择`() {
        val (sel, sweep) = MtSelection.beginSweep(setOf("e"), "b")
        assertEquals(setOf("b", "e"), sel)
        assertEquals("b", sweep.anchorKey)
        assertEquals(setOf("e"), sweep.base)
    }

    @Test
    fun `扫选跟手：区间随手指增长`() {
        val (sel, sweep) = MtSelection.beginSweep(emptySet(), "b")
        assertEquals(setOf("b", "c"), MtSelection.swept(keys, sweep, "c"))
        assertEquals(setOf("b", "c", "d"), MtSelection.swept(keys, sweep, "d"))
        assertEquals(setOf("b"), MtSelection.swept(keys, sweep, "b"))
        // 往回滑：区间收回去（跟手语义；旧实现只增不减）
        assertEquals(setOf("b", "c"), MtSelection.swept(keys, sweep, "c"))
        assertEquals(setOf("b", "c"), MtSelection.swept(keys, sweep, "c"))
        assertEquals(sel, setOf("b"))
    }

    @Test
    fun `扫选跟手：向上滑时区间在下半段也不会丢掉锚点`() {
        val (_, sweep) = MtSelection.beginSweep(emptySet(), "d")
        assertEquals(setOf("c", "d"), MtSelection.swept(keys, sweep, "c"))
        assertEquals(setOf("a", "b", "c", "d"), MtSelection.swept(keys, sweep, "a"))
    }

    @Test
    fun `扫选跟手：base 里的选择在整段扫选中始终保留`() {
        val (_, sweep) = MtSelection.beginSweep(setOf("a", "e"), "c")
        assertEquals(setOf("a", "c", "d", "e"), MtSelection.swept(keys, sweep, "d"))
        // 退回锚点：只剩 base ∪ {锚点}
        assertEquals(setOf("a", "c", "e"), MtSelection.swept(keys, sweep, "c"))
    }

    @Test
    fun `扫选跟手：当前行已不在列表时返回 null（调用方保持原选择）`() {
        val (_, sweep) = MtSelection.beginSweep(emptySet(), "b")
        assertNull(MtSelection.swept(keys, sweep, "ghost"))
        // 连锚点都失效（换目录 / 刷新）同样返回 null
        assertNull(MtSelection.swept(keys, MtSelection.Sweep(anchorKey = "ghost"), "b"))
    }

    @Test
    fun `扫选：锚点永远在区间里（滑回自身也保持选中）`() {
        val (sel, sweep) = MtSelection.beginSweep(emptySet(), "c")
        assertTrue(sel.contains("c"))
        assertTrue(MtSelection.swept(keys, sweep, "c")!!.contains("c"))
    }
}
