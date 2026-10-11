package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器菜单模型（v2.0.15 · NP `edit_menu.xml` 对齐）。
 *
 * 这些规则以前写在 Composable 里、没有任何回归：只读分段该禁哪些项、
 * 「切换注释 / 格式化 / 校验」按语言给不给、勾选态跟谁走。
 */
class EditorMenuModelTest {

    private fun input(
        readOnly: Boolean = false,
        canFormat: Boolean = true,
        canValidate: Boolean = true,
        canToggleComment: Boolean = true,
        wordwrap: Boolean = false,
        fluentMode: Boolean = false,
        showWhitespace: Boolean = false,
        monospace: Boolean = true,
        symbolBar: Boolean = false,
        indentGuide: Boolean = true,
        magnifier: Boolean = true,
        fontSize: Int = 14,
        languageLabel: String = "JSON",
        languageManual: Boolean = false,
        themeLabel: String = "跟随应用",
    ) = EditorMenuInput(
        readOnly = readOnly,
        canFormat = canFormat,
        canValidate = canValidate,
        canToggleComment = canToggleComment,
        wordwrap = wordwrap,
        fluentMode = fluentMode,
        showWhitespace = showWhitespace,
        monospace = monospace,
        symbolBar = symbolBar,
        indentGuide = indentGuide,
        magnifier = magnifier,
        fontSize = fontSize,
        languageLabel = languageLabel,
        languageManual = languageManual,
        themeLabel = themeLabel,
    )

    private fun entry(action: EditorMenuAction, input: EditorMenuInput = input()): EditorMenuEntry =
        editorMenuSections(input).flatMap { it.entries }.firstOrNull { it.action == action }
            ?: error("菜单里没有 $action")

    @Test
    fun `分组顺序与 NP 一致`() {
        val titles = editorMenuSections(input()).map { it.title }
        assertEquals(
            listOf("文件", "编辑", "查找", "跳转", "视图", "编码", "设置", "代码整理", ""),
            titles,
        )
    }

    @Test
    fun `编码与设置是二级菜单，其余平铺`() {
        val submenus = editorMenuSections(input()).filter { it.submenu }.map { it.title }
        assertEquals(listOf("编码", "设置"), submenus)
    }

    @Test
    fun `只读时禁用一切会改正文的项，复制行保留可用`() {
        val ro = input(readOnly = true)
        listOf(
            EditorMenuAction.SAVE,
            EditorMenuAction.UNDO,
            EditorMenuAction.REDO,
            EditorMenuAction.CUT_LINE,
            EditorMenuAction.DELETE_LINE,
            EditorMenuAction.CLEAR_LINE,
            EditorMenuAction.DUPLICATE_LINE,
            EditorMenuAction.TO_UPPER,
            EditorMenuAction.TO_LOWER,
            EditorMenuAction.INDENT,
            EditorMenuAction.UNINDENT,
            EditorMenuAction.TOGGLE_COMMENT,
            EditorMenuAction.FORMAT,
            EditorMenuAction.COMPRESS,
            EditorMenuAction.CHARSET_SAVE,
        ).forEach { assertFalse("$it 只读时应置灰", entry(it, ro).enabled) }

        assertTrue("复制行只写剪贴板，只读下保持可用", entry(EditorMenuAction.COPY_LINE, ro).enabled)
        assertTrue("重新加载不改正文", entry(EditorMenuAction.RELOAD, ro).enabled)
        assertTrue("查找不改正文", entry(EditorMenuAction.FIND, ro).enabled)
    }

    @Test
    fun `切换注释按语言给（不支持时置灰并注明）`() {
        val noComment = input(canToggleComment = false)
        val e = entry(EditorMenuAction.TOGGLE_COMMENT, noComment)
        assertFalse(e.enabled)
        assertTrue(e.label.contains("当前语言不支持"))
        assertTrue(entry(EditorMenuAction.TOGGLE_COMMENT).enabled)
    }

    @Test
    fun `格式化与校验按语言给`() {
        assertFalse(entry(EditorMenuAction.FORMAT, input(canFormat = false)).enabled)
        assertTrue(entry(EditorMenuAction.FORMAT).enabled)
        assertFalse(entry(EditorMenuAction.VALIDATE, input(canValidate = false)).enabled)
    }

    @Test
    fun `勾选态跟随设置`() {
        val e = editorMenuSections(
            input(
                wordwrap = true,
                fluentMode = true,
                showWhitespace = true,
                monospace = false,
                symbolBar = true,
                indentGuide = false,
                magnifier = false,
            ),
        ).flatMap { it.entries }.associateBy { it.action }

        assertEquals(true, e.getValue(EditorMenuAction.WORDWRAP).checked)
        assertEquals(true, e.getValue(EditorMenuAction.FLUENT_MODE).checked)
        assertEquals(true, e.getValue(EditorMenuAction.WHITESPACE).checked)
        assertEquals(false, e.getValue(EditorMenuAction.MONOSPACE).checked)
        assertEquals(true, e.getValue(EditorMenuAction.SYMBOL_BAR).checked)
        assertEquals(false, e.getValue(EditorMenuAction.INDENT_GUIDE).checked)
        assertEquals(false, e.getValue(EditorMenuAction.MAGNIFIER).checked)
        // 非勾选项不能有勾选态（否则 UI 会画出一个假勾）
        assertEquals(null, e.getValue(EditorMenuAction.SAVE).checked)
    }

    @Test
    fun `标签带上当前值`() {
        val e = editorMenuSections(
            input(fontSize = 20, languageLabel = "Kotlin", languageManual = true, themeLabel = "深色（Darcula）"),
        ).flatMap { it.entries }.associateBy { it.action }

        assertEquals("字体大小：20", e.getValue(EditorMenuAction.FONT_SIZE).label)
        assertEquals("语法：Kotlin（手动）", e.getValue(EditorMenuAction.LANGUAGE).label)
        assertEquals("配色风格：深色（Darcula）", e.getValue(EditorMenuAction.THEME).label)
    }

    @Test
    fun `每个动作只出现一次`() {
        val actions = editorMenuSections(input()).flatMap { it.entries }.map { it.action }
        assertEquals(actions.size, actions.distinct().size)
    }
}
