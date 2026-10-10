package com.u707t.panelfm.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 字符串工具箱纯逻辑：编码 / 摘要 / 文本 / 进制。 */
class TextToolboxKitTest {

    // ---------------------------------------------------------------- 编码

    @Test
    fun base64RoundTripAndTolerantDecode() {
        val encoded = TextToolboxKit.base64Encode("你好 PanelFM")
        assertEquals("你好 PanelFM", TextToolboxKit.base64Decode(encoded))
        // 粘贴带换行 / 空格（常见于从文档里抄）
        assertEquals("你好", TextToolboxKit.base64Decode("5L2g5aW9\n"))
        // URL-safe 字母表
        assertEquals(TextToolboxKit.base64Decode(TextToolboxKit.base64Encode("???>>>")), "???>>>")
        // 非法输入 → null
        assertNull(TextToolboxKit.base64Decode("????"))
    }

    @Test
    fun hexRoundTripAndTolerantDecode() {
        val encoded = TextToolboxKit.hexEncode("你好")
        assertEquals("E4 BD A0 E5 A5 BD", encoded)
        assertEquals("你好", TextToolboxKit.hexDecode(encoded))
        // 容忍 0x 前缀 / 逗号 / 小写 / 无分隔
        assertEquals("AB", TextToolboxKit.hexDecode("0x41 0x42"))
        assertEquals("AB", TextToolboxKit.hexDecode("41,42"))
        assertEquals("AB", TextToolboxKit.hexDecode("4142"))
        assertEquals("JK", TextToolboxKit.hexDecode("4a4b")) // 小写
        // 非法输入 → null
        assertNull(TextToolboxKit.hexDecode("414")) // 奇数长度
        assertNull(TextToolboxKit.hexDecode("GG")) // 非法字符
    }

    @Test
    fun urlEncodeIsRfc3986() {
        assertEquals("a%20b%2F%E4%BD%A0", TextToolboxKit.urlEncode("a b/你"))
        assertEquals("abc-._~", TextToolboxKit.urlEncode("abc-._~"))
    }

    @Test
    fun urlDecodeKeepsInvalidSequences() {
        assertEquals("你好", TextToolboxKit.urlDecode("%E4%BD%A0%E5%A5%BD"))
        assertEquals("100% wrong", TextToolboxKit.urlDecode("100% wrong"))
        assertEquals("a+b", TextToolboxKit.urlDecode("a+b")) // 不做表单 + → 空格
    }

    @Test
    fun unicodeRoundTripAndPassthrough() {
        assertEquals("\\u0061\\u4F60", TextToolboxKit.unicodeEncode("a你"))
        assertEquals("a你", TextToolboxKit.unicodeDecode("\\u0061\\u4F60"))
        // 混合文本：只替换合法 \uXXXX
        assertEquals("x你y", TextToolboxKit.unicodeDecode("x\\u4F60y"))
        // Windows 路径这类"像转义但不是"的内容原样保留
        assertEquals("C:\\users", TextToolboxKit.unicodeDecode("C:\\users"))
    }

    // ---------------------------------------------------------------- 摘要

    @Test
    fun digestsMatchKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", TextToolboxKit.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", TextToolboxKit.md5("abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", TextToolboxKit.sha1("abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TextToolboxKit.sha256("abc"),
        )
    }

    // ---------------------------------------------------------------- 文本

    @Test
    fun textOps() {
        assertEquals("ABC", TextToolboxKit.upper("aBc"))
        assertEquals("abc", TextToolboxKit.lower("aBc"))
        assertEquals("abc", TextToolboxKit.removeSpaces(" a b\tc\n"))
    }

    // ---------------------------------------------------------------- 进制

    @Test
    fun numberBaseConversions() {
        assertEquals("FF", TextToolboxKit.decToHex("255"))
        assertEquals("400", TextToolboxKit.decToHex("1,024")) // 千分位逗号容忍
        assertEquals("FF", TextToolboxKit.decToHex(" 255 "))
        assertEquals("255", TextToolboxKit.hexToDec("FF"))
        assertEquals("255", TextToolboxKit.hexToDec("0xff"))
        assertEquals("255", TextToolboxKit.hexToDec("ff"))
        assertNull(TextToolboxKit.decToHex("abc"))
        assertNull(TextToolboxKit.hexToDec("zz"))
    }
}
