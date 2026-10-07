package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncodingsTest {

    @Test
    fun `utf8 识别`() {
        val bytes = "你好，PanelFM".toByteArray(Charsets.UTF_8)
        val decoded = TextEncodings.decode(bytes)
        assertEquals("UTF-8", decoded.charset)
        assertEquals("你好，PanelFM", decoded.text)
    }

    @Test
    fun `gbk 识别`() {
        val bytes = "中文测试".toByteArray(charset("GBK"))
        val decoded = TextEncodings.decode(bytes)
        assertEquals("GBK", decoded.charset)
        assertEquals("中文测试", decoded.text)
    }

    @Test
    fun `utf8 bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        val decoded = TextEncodings.decode(bytes)
        assertEquals("UTF-8 (BOM)", decoded.charset)
        assertEquals("abc", decoded.text)
    }

}
