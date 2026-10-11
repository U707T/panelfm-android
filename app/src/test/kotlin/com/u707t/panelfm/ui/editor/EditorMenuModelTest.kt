package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器菜单模型（v2.0.15 · NP `edit_menu.xml` 对齐）。
 *
 * 这些规则以前写在 Composable 里、没有任何回归：编辑项跟语言能力走、
 * 「切换注释 / 格式化 / 校验」按语言给不给、勾选态跟谁走、流畅模式的「自动档」入口何时出现。
 */
class EditorMenuModelTest {

    private fun input(
        canFormat: Boolean = true,
        canValidate: Boolean = true,
        canToggleComment: Boolean = true,
        wordwrap: Boolean = false,
        fluentMode: Boolean = false,
        fluentManual: Boolean = false,
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
        canFormat = canFormat,
        canValidate = canValidate,
        canToggleComment = canToggleComment,
        wordwrap = wordwrap,
        fluentMode = fluentMode,
        fluentManual = fluentManual,
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

    private fun entries(input: EditorMenuInput = input()): Map<EditorMenuAction, EditorMenuEntry> =
        editorMenuSections(input).flatMap { it.entries }.associateBy { it.action }

    private fun entry(action: EditorMenuAction, input: EditorMenuInput = input()): EditorMenuEntry =
        entries(input)[action] ?: error("菜单里没有 $action")

    @Test
    fun `分组顺序：常用项平铺在最上，其余全部是二级菜单`() {
        val sections = editorMenuSections(input())
        // 一级平铺的只有两组：最上面的常用项与最下面的「退出」
        val flat = sections.filter { !it.submenu }
        assertEquals(listOf("", ""), flat.map { it.title })
        assertEquals(
            listOf("查找", "查找 / 替换", "保存", "重新加载"),
            flat.first().entries.map { it.label },
        )
        assertEquals(listOf("退出编辑器"), flat.last().entries.map { it.label })
        // 中间全是二级菜单，且顺序按使用频率排
        assertEquals(
            listOf("编辑", "跳转", "视图", "编码", "设置", "代码整理"),
            sections.filter { it.submenu }.map { it.title },
        )
    }

    @Test
    fun `一级菜单总行数克制（不超过 15 行）`() {
        // 手机上 ⋮ 菜单要能一眼看完：常用 4 项 + 6 个分组入口 + 退出 = 11 行
        val sections = editorMenuSections(input())
        val rootRows = sections.count { it.submenu } + sections.filter { !it.submenu }.sumOf { it.entries.size }
        assertEquals(11, rootRows)
        assertTrue("一级菜单不该超过 15 行（当前 $rootRows）", rootRows <= 15)
    }

    @Test
    fun `编辑与文件项默认可点（没有只读分段浏览）`() {
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
            EditorMenuAction.CHARSET_SAVE,
            EditorMenuAction.COMPRESS,
        ).forEach { assertTrue("$it 应可点", entry(it).enabled) }
    }

    @Test
    fun `切换注释按语言给（不支持时置灰并注明）`() {
        val e = entry(EditorMenuAction.TOGGLE_COMMENT, input(canToggleComment = false))
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
        val e = entries(
            input(
                wordwrap = true,
                fluentMode = true,
                showWhitespace = true,
                monospace = false,
                symbolBar = true,
                indentGuide = false,
                magnifier = false,
            ),
        )

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
    fun `流畅模式：自动档不显示「恢复自动」，显式选择后才显示`() {
        // 自动档（用户没选过）：大文件自动开时只显示勾选态，不给「恢复自动」
        val auto = entries(input(fluentMode = true, fluentManual = false))
        assertEquals(true, auto.getValue(EditorMenuAction.FLUENT_MODE).checked)
        assertNull("自动档下不该有「恢复自动」项", auto[EditorMenuAction.FLUENT_AUTO])

        // 用户显式选过：多一行「恢复自动（按文件大小）」
        val manual = entries(input(fluentMode = false, fluentManual = true))
        assertEquals(false, manual.getValue(EditorMenuAction.FLUENT_MODE).checked)
        assertTrue(manual.getValue(EditorMenuAction.FLUENT_AUTO).label.contains("恢复自动"))
    }

    @Test
    fun `标签带上当前值`() {
        val e = entries(
            input(fontSize = 20, languageLabel = "Kotlin", languageManual = true, themeLabel = "深色（Darcula）"),
        )

        assertEquals("字体大小：20", e.getValue(EditorMenuAction.FONT_SIZE).label)
        assertEquals("语法：Kotlin（手动）", e.getValue(EditorMenuAction.LANGUAGE).label)
        assertEquals("配色风格：深色（Darcula）", e.getValue(EditorMenuAction.THEME).label)
    }

    @Test
    fun `每个动作只出现一次`() {
        val actions = editorMenuSections(input(fluentManual = true)).flatMap { it.entries }.map { it.action }
        assertEquals(actions.size, actions.distinct().size)
    }
}
