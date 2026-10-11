package com.u707t.panelfm.ui.editor

import com.u707t.panelfm.core.common.TextEncodings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文件编码」选择表 ↔ `TextEncodings` 的一致性（NP `load_reset_charset` / `save_reset_charset`）。
 *
 * 这张表把「用户在对话框里能选的编码」与「实现真的能编码写回的编码」钉在一起：
 * 旧实现里 ISO-8859-1 会静默转存 UTF-8（两张表各写各的），这类漂移必须由测试拦住。
 */
class EditorCharsetsTest {

    @Test
    fun `每个可选编码都能被 TextEncodings 原样编码与解码`() {
        EditorCharsets.ALL.forEach { cs ->
            val encoded = TextEncodings.encode("abc 123", cs)
            assertEquals("$cs 应被原样编码（不回落 UTF-8）", cs, encoded.charset)
            assertEquals("$cs 解码应还原原文", "abc 123", TextEncodings.decodeWith(cs, encoded.bytes))
        }
    }

    @Test
    fun `重新加载的选项表首项是自动识别`() {
        val options = EditorCharsets.reloadOptions()
        assertEquals(EditorCharsets.AUTO to "自动识别", options.first())
        assertEquals(EditorCharsets.ALL.size, options.size - 1)
    }

    @Test
    fun `保存的选项表不含自动识别`() {
        assertTrue(EditorCharsets.saveOptions().none { it.first == EditorCharsets.AUTO })
        assertEquals(EditorCharsets.ALL.size, EditorCharsets.saveOptions().size)
    }

    @Test
    fun `显示名不为空且与编码名不冲突`() {
        EditorCharsets.ALL.forEach { cs ->
            val label = EditorCharsets.label(cs)
            assertTrue("$cs 的显示名不能为空", label.isNotBlank())
        }
        assertEquals("UTF-8（带 BOM）", EditorCharsets.label("UTF-8 (BOM)"))
        assertEquals("ISO-8859-1（Latin-1）", EditorCharsets.label("ISO-8859-1"))
    }
}
