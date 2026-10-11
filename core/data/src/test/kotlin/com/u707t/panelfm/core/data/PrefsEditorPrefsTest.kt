package com.u707t.panelfm.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑器偏好（v2.0.15 · 对齐 NP管理器「设置」子菜单）的编解码：
 * 「自动换行」三态与配色白名单，都是**老数据 / 脏数据不能改变默认行为**的地方。
 */
class PrefsEditorPrefsTest {

    @Test
    fun `自动换行三态：未设置按语言默认`() {
        assertNull(parseEditorWordwrap(null))
        assertNull(parseEditorWordwrap(""))
        assertNull(parseEditorWordwrap("auto"))
        assertEquals(true, parseEditorWordwrap("on"))
        assertEquals(false, parseEditorWordwrap("off"))
    }

    @Test
    fun `自动换行编解码往返`() {
        listOf(null, true, false).forEach { value ->
            assertEquals(value, parseEditorWordwrap(encodeEditorWordwrap(value)))
        }
    }

    @Test
    fun `配色白名单：只认 light 与 dark，其余回落跟随应用`() {
        assertEquals("", sanitizeEditorTheme(null))
        assertEquals("", sanitizeEditorTheme(""))
        assertEquals("", sanitizeEditorTheme("quietlight"))
        assertEquals("", sanitizeEditorTheme("DARK"))
        assertEquals("light", sanitizeEditorTheme("light"))
        assertEquals("dark", sanitizeEditorTheme("dark"))
    }
}
