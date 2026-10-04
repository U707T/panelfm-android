package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** MT 编辑器行操作（菜单 0x7f0e001b）的回归测试 */
class LineOpsTest {

    @Test
    fun `复制行`() {
        val t = "a\nb\nc"
        assertEquals("a\nb\nb\nc", LineOps.duplicateLine(t, 1))
    }

    @Test
    fun `删除行`() {
        assertEquals("a\nc", LineOps.deleteLine("a\nb\nc", 1))
    }

    @Test
    fun `清空行保留空行`() {
        assertEquals("a\n\nc", LineOps.clearLine("a\nb\nc", 1))
    }

    @Test
    fun `剪切行返回被剪内容`() {
        val (rest, cut) = LineOps.cutLine("a\nb\nc", 1)
        assertEquals("a\nc", rest)
        assertEquals("b", cut)
    }

    @Test
    fun `越界索引不改动文本`() {
        assertEquals("a", LineOps.duplicateLine("a", 5))
        assertEquals("a", LineOps.deleteLine("a", -1))
    }

    @Test
    fun `转大小写`() {
        assertEquals("ABC", LineOps.toUpperCase("aBc"))
        assertEquals("abc", LineOps.toLowerCase("aBc"))
    }

    @Test
    fun `增删缩进`() {
        assertEquals("    a\n    b", LineOps.indent("a\nb"))
        assertEquals("a\nb", LineOps.unindent("    a\n    b"))
        // Tab 缩进也能剥掉
        assertEquals("a", LineOps.unindent("\ta"))
        // 非空行缩进；纯空白行保持原样（避免产生「只有缩进的空行」）
        assertEquals("    a\n\n    b", LineOps.indent("a\n\nb"))
        assertEquals("    a\n   \n    b", LineOps.indent("a\n   \nb"))
    }

    @Test
    fun `切换注释 加上`() {
        val out = LineOps.toggleComment("a\nb", "//")
        assertEquals("// a\n// b", out)
    }

    @Test
    fun `切换注释 取消（不累积空格）`() {
        val once = LineOps.toggleComment("a\nb", "//")
        val twice = LineOps.toggleComment(once, "//")
        assertEquals("a\nb", twice)
    }

    @Test
    fun `切换注释 保留缩进`() {
        val out = LineOps.toggleComment("    a\n\tb", "//")
        assertEquals("    // a\n\t// b", out)
    }

    @Test
    fun `切换注释 部分已注释时全部加上`() {
        val out = LineOps.toggleComment("// a\nb", "//")
        assertEquals("// // a\n// b", out)
    }

    @Test
    fun `空文本切换注释不变`() {
        assertEquals("", LineOps.toggleComment("", "//"))
    }

    @Test
    fun `行号定位`() {
        val t = "aa\nbb\ncc"
        assertEquals(0, LineOps.lineIndexOf(t, 0))
        assertEquals(0, LineOps.lineIndexOf(t, 1))
        assertEquals(1, LineOps.lineIndexOf(t, 3))
        assertEquals(2, LineOps.lineIndexOf(t, 7))
        assertEquals(0, LineOps.lineStartOffset(t, 0))
        assertEquals(3, LineOps.lineStartOffset(t, 1))
        assertEquals(6, LineOps.lineStartOffset(t, 2))
    }

    @Test
    fun `去行尾空白与首尾空行（不动行首）`() {
        // 行尾空白与首尾空行被清掉；行首缩进**保留**（不能顺手 trimStart，会破坏缩进）
        assertEquals(" a\nb", LineOps.trimTrailingWhitespace("\n a  \nb\t\n\n"))
        assertEquals("a\nb", LineOps.trimTrailingWhitespace("a\nb"))
        // 中间空行保留
        assertEquals("a\n\nb", LineOps.trimTrailingWhitespace("a\n  \nb\n"))
        // 全空白 → 空串
        assertEquals("", LineOps.trimTrailingWhitespace("  \n\t\n "))
    }
}
