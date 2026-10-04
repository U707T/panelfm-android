package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对比引擎：MT「忽略」四档 + 区分大小写 + 行号语义（复刻 0x7f0e000a） */
class TextDiffEngineTest {

    private fun kinds(r: TextDiffEngine.DiffResult) = r.lines.map { it.kind }

    @Test
    fun `不忽略时按原文比较`() {
        val r = TextDiffEngine.diff("a\n b", "a\nb")
        assertEquals(listOf(DiffLineKind.SAME, DiffLineKind.REMOVE, DiffLineKind.ADD), kinds(r))
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
    }

    @Test
    fun `行尾换行产生的空行两侧都算未变`() {
        val r = TextDiffEngine.diff("a\n", "a\n")
        assertEquals(listOf(DiffLineKind.SAME, DiffLineKind.SAME), kinds(r))
    }

    @Test
    fun `忽略首尾空格后视为相同`() {
        val r = TextDiffEngine.diff("a\n  b  ", "a\nb", ignore = DiffIgnore.TRIM)
        assertEquals(2, r.sameCount)
        assertEquals(0, r.added + r.removed)
    }

    @Test
    fun `忽略全部空格后行内空格差异不算差异`() {
        val r = TextDiffEngine.diff("x = 1", "x=1", ignore = DiffIgnore.ALL_SPACES)
        assertEquals(1, r.sameCount)
    }

    @Test
    fun `忽略空格和空行会丢掉空行但保留原文行号`() {
        val r = TextDiffEngine.diff("a\n\nb", "a\nb", ignore = DiffIgnore.SPACES_AND_BLANK)
        assertEquals(0, r.added + r.removed)
        assertEquals(listOf(1, 3), r.lines.map { it.leftNo })
    }

    @Test
    fun `默认区分大小写`() {
        val r = TextDiffEngine.diff("Abc", "abc")
        assertEquals(1, r.removed)
        assertEquals(1, r.added)
    }

    @Test
    fun `关闭区分大小写后视为相同`() {
        val r = TextDiffEngine.diff("Abc", "abc", caseSensitive = false)
        assertEquals(1, r.sameCount)
    }

    @Test
    fun `展示的始终是原文而不是归一化后的文本`() {
        val r = TextDiffEngine.diff("  keep  ", "  keep  ", ignore = DiffIgnore.ALL_SPACES)
        assertEquals("  keep  ", r.lines.first().text)
    }

    @Test
    fun `差异块数量用于上一个下一个差异`() {
        val r = TextDiffEngine.diff("a\nb\nc\nd", "a\nX\nc\nY")
        // b→X 与 d→Y 是两块（中间 c 相同）
        assertEquals(2, r.hunks.size)
    }

    @Test
    fun `大文件截断会被标记`() {
        val r = TextDiffEngine.diff("a\nb\nc", "a\nb", maxLines = 2)
        assertTrue(r.truncated)
    }
}
