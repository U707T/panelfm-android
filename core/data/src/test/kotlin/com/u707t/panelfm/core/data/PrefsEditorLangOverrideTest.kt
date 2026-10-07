package com.u707t.panelfm.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑器「语法」覆盖记录的解析（2026-10-08 重审 §3 · 🔵10）。
 *
 * 回归目标：空扩展名（无后缀文件）的覆盖也要能存回来——
 * 旧实现 `idx <= 0` 会把 `|source.shell` 整条丢弃。
 */
class PrefsEditorLangOverrideTest {

    @Test
    fun `空扩展名（无后缀文件）合法`() {
        assertEquals("" to "source.shell", parseLangOverride("|source.shell"))
        assertEquals("" to "", parseLangOverride("|"))
    }

    @Test
    fun `普通扩展名解析`() {
        assertEquals("kt" to "source.kotlin", parseLangOverride("kt|source.kotlin"))
    }

    @Test
    fun `纯文本覆盖（空 scope）可解析`() {
        assertEquals("txt" to "", parseLangOverride("txt|"))
    }

    @Test
    fun `无分隔符的行丢弃`() {
        assertNull(parseLangOverride("broken"))
        assertNull(parseLangOverride(""))
    }
}
