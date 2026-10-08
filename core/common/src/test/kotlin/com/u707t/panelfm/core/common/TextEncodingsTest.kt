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

    // ---- 保存编码闭环：写回 → 重新识别 → 文本一致（2026-10-08 重审 §3 修复）

    @Test
    fun `utf8 bom 往返：重建 BOM 且重新识别一致`() {
        val text = "abc\n中文"
        val out = TextEncodings.encode(text, "UTF-8 (BOM)")
        assertEquals("UTF-8 (BOM)", out.charset)
        assertEquals(0xEF.toByte(), out.bytes[0])
        val back = TextEncodings.decode(out.bytes)
        assertEquals("UTF-8 (BOM)", back.charset)
        assertEquals(text, back.text)
    }

    @Test
    fun `utf16le 往返：重建 BOM 且重新识别一致`() {
        val text = "hello 世界\n第二行"
        val out = TextEncodings.encode(text, "UTF-16LE")
        assertEquals("UTF-16LE", out.charset)
        assertEquals(0xFF.toByte(), out.bytes[0])
        val back = TextEncodings.decode(out.bytes)
        assertEquals("UTF-16LE", back.charset)
        assertEquals(text, back.text)
    }

    @Test
    fun `utf16be 往返`() {
        val text = "hello 世界"
        val out = TextEncodings.encode(text, "UTF-16BE")
        assertEquals(0xFE.toByte(), out.bytes[0])
        val back = TextEncodings.decode(out.bytes)
        assertEquals("UTF-16BE", back.charset)
        assertEquals(text, back.text)
    }

    @Test
    fun `gbk 往返`() {
        val text = "中文测试\n第二行"
        val out = TextEncodings.encode(text, "GBK")
        assertEquals("GBK", out.charset)
        val back = TextEncodings.decode(out.bytes)
        assertEquals("GBK", back.charset)
        assertEquals(text, back.text)
    }

    @Test
    fun `iso-8859-1 可表示时保持原编码`() {
        val text = "café déjà vu"
        val out = TextEncodings.encode(text, "ISO-8859-1")
        assertEquals("ISO-8859-1", out.charset)
        val back = TextEncodings.decode(out.bytes)
        assertEquals("ISO-8859-1", back.charset)
        assertEquals(text, back.text)
    }

    @Test
    fun `iso-8859-1 无法表示时回退 UTF-8 并如实上报`() {
        val text = "café 中文"
        val out = TextEncodings.encode(text, "ISO-8859-1")
        assertEquals("UTF-8", out.charset)
        assertEquals(text, TextEncodings.decode(out.bytes).text)
    }

    @Test
    fun `decodeWith 与识别编码名一致`() {
        val bytes = TextEncodings.encode("第一段\n第二段", "UTF-16LE").bytes
        assertEquals("第一段\n第二段", TextEncodings.decodeWith("UTF-16LE", bytes))
    }

    // ---- 中文编码启发式（Big5）+ UTF-32（v2.0.7 新增）

    @Test
    fun `big5 识别（繁体）`() {
        val text = "繁體中文測試，這是第二行。"
        val decoded = TextEncodings.decode(text.toByteArray(charset("Big5")))
        assertEquals("Big5", decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun `gbk 长文本不会被误判为 Big5`() {
        val text = "这是一个比较长的简体中文段落，用来确认编码识别不会把 GBK 误判成 Big5；" +
            "里面包含常见字：时间、国家、会议、学习、对话、进来、实现、结果，以及标点。"
        val decoded = TextEncodings.decode(text.toByteArray(charset("GBK")))
        assertEquals("GBK", decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun `utf-32 往返与识别（LE 与 BE）`() {
        val text = "hello 世界\n第二行"
        listOf("UTF-32LE", "UTF-32BE").forEach { name ->
            val out = TextEncodings.encode(text, name)
            assertEquals(name, out.charset)
            val back = TextEncodings.decode(out.bytes)
            assertEquals(name, back.charset)
            assertEquals(text, back.text)
            assertEquals(text, TextEncodings.decodeWith(name, out.bytes))
        }
    }

    @Test
    fun `新增中文编码的保存往返（不可表示时回退 UTF-8）`() {
        // 可表示：往返一致
        assertEquals("Big5", TextEncodings.encode("繁體中文測試", "Big5").charset)
        // 不可表示（emoji 在 Big5 里没有）：回退 UTF-8 并如实上报
        val out = TextEncodings.encode("中文😀", "Big5")
        assertEquals("UTF-8", out.charset)
        assertEquals("中文😀", TextEncodings.decode(out.bytes).text)
    }
}
