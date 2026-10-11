package com.u707t.panelfm.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑器偏好（v2.0.15 · 对齐 NP管理器「设置」子菜单）的编解码：
 * 「自动换行」「流畅模式」两个三态与配色白名单，都是**老数据 / 脏数据不能改变默认行为**的地方。
 */
class PrefsEditorPrefsTest {

    @Test
    fun `三态：未设置时交给默认逻辑（换行按语言·流畅模式按文件大小）`() {
        assertNull(parseTriState(null))
        assertNull(parseTriState(""))
        assertNull(parseTriState("auto"))
        assertEquals(true, parseTriState("on"))
        assertEquals(false, parseTriState("off"))
    }

    @Test
    fun `三态编解码往返`() {
        listOf(null, true, false).forEach { value ->
            assertEquals(value, parseTriState(encodeTriState(value)))
        }
    }

    @Test
    fun `踩过类型变更的键：老 Boolean 与新 String 都能读（v2_0_15 的 editor_fluent_mode）`() {
        assertEquals(true, parseTriStateValue(true))
        assertEquals(false, parseTriStateValue(false))
        assertEquals(true, parseTriStateValue("on"))
        assertEquals(false, parseTriStateValue("off"))
        // 缺省 / 脏数据 → 交给「按文件大小自动」
        assertEquals(null, parseTriStateValue(null))
        assertEquals(null, parseTriStateValue(42L))
        assertEquals(null, parseTriStateValue("auto"))
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
