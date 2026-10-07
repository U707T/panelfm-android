package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MT 选择模型的回归测试（见 [MtSelection] 的类注释：语义依据 MT 字符串与用户实机确认）。
 *
 * 重点盯三组容易退化的性质：
 *  1. **滑动选中是离散的**：无锚点 → 只选它；有锚点 → 连成闭区间（不会「按住一路刷」）；
 *  2. **追加语义**：连选不许把用户之前选好的项抹掉；
 *  3. **锚点失效要退化**（刷新 / 换目录后不许在错误区间上乱选）。
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

    // ------------------------------------------------------------------ 滑动选中（离散）

    @Test
    fun `第一次滑动只选它，并把锚点设在它身上`() {
        val (sel, anchor) = MtSelection.swipe(emptySet(), keys, "c", anchor = null)
        assertEquals(setOf("c"), sel)
        assertEquals("c", anchor)
    }

    @Test
    fun `第一次滑动不推翻已有选择（追加）`() {
        val (sel, anchor) = MtSelection.swipe(setOf("e"), keys, "b", anchor = null)
        assertEquals(setOf("b", "e"), sel)
        assertEquals("b", anchor)
    }

    @Test
    fun `滑动第二项 → 两项之间的闭区间全部选中，锚点挪过去`() {
        val (first, anchor1) = MtSelection.swipe(emptySet(), keys, "b", anchor = null)
        val (second, anchor2) = MtSelection.swipe(first, keys, "d", anchor1)
        assertEquals(setOf("b", "c", "d"), second)
        assertEquals("d", anchor2)
        // 第三次滑动继续延伸（b..e）
        val (third, _) = MtSelection.swipe(second, keys, "e", anchor2)
        assertEquals(setOf("b", "c", "d", "e"), third)
    }

    @Test
    fun `反向滑动同样连区间（右往左）`() {
        val (first, anchor1) = MtSelection.swipe(emptySet(), keys, "d", anchor = null)
        val (second, _) = MtSelection.swipe(first, keys, "a", anchor1)
        assertEquals(setOf("a", "b", "c", "d"), second)
    }

    @Test
    fun `滑动同一项两次：只保留它，不会误扩成整段`() {
        val (first, anchor1) = MtSelection.swipe(emptySet(), keys, "c", anchor = null)
        val (second, anchor2) = MtSelection.swipe(first, keys, "c", anchor1)
        assertEquals(setOf("c"), second)
        assertEquals("c", anchor2)
    }

    @Test
    fun `锚点失效（列表刷新过）时退化为只选这一项`() {
        val (sel, anchor) = MtSelection.swipe(setOf("e"), keys, "b", anchor = "ghost")
        assertEquals(setOf("b", "e"), sel)
        assertEquals("b", anchor)
    }
}
