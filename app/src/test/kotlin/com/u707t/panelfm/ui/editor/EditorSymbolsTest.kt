package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号输入栏的符号表（NP `menu_symbol_input`）。 */
class EditorSymbolsTest {

    @Test
    fun `符号表非空且显示文本不重复`() {
        assertTrue(EDITOR_SYMBOLS.isNotEmpty())
        val labels = EDITOR_SYMBOLS.map { it.first }
        assertEquals("显示文本重复会让用户无法区分按钮", labels.size, labels.distinct().size)
    }

    @Test
    fun `显示与插入一一对应，且插入内容非空`() {
        assertEquals(EDITOR_SYMBOLS.size, EDITOR_SYMBOL_LABELS.size)
        assertEquals(EDITOR_SYMBOLS.size, EDITOR_SYMBOL_VALUES.size)
        EDITOR_SYMBOLS.forEach { (label, value) ->
            assertTrue("$label 的插入内容不能为空", value.isNotEmpty())
        }
    }

    @Test
    fun `必须含 Tab（走编辑器的缩进逻辑）`() {
        val tab = EDITOR_SYMBOLS.firstOrNull { it.second == "\t" }
        assertTrue("符号栏必须能插入 Tab", tab != null)
    }

    @Test
    fun `常用代码符号齐全`() {
        val values = EDITOR_SYMBOLS.map { it.second }.toSet()
        listOf("{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "=", "/", "\\", "|", "&", ";", ":").forEach {
            assertTrue("缺少符号 $it", values.contains(it))
        }
    }
}
