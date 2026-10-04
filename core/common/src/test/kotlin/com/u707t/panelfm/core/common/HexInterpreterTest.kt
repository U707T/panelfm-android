package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hex 数值解释（MT 检查面板 0x7f0c002a）：大端/小端 + 各类型 */
class HexInterpreterTest {

    private val bytes = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, // 0..3：整数 0x01020304
        0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte(), // 4..7
        0x41, 0x42, 0x43, 0x00, // 8..11：UTF8 "ABC"
    )

    @Test
    fun `无符号与有符号字节`() {
        assertEquals("-1", HexInterpreter.interpret(bytes, 4, HexInterpreter.Kind.BYTE, true))
        assertEquals("255", HexInterpreter.interpret(bytes, 4, HexInterpreter.Kind.BYTE_UNSIGNED, true))
    }

    @Test
    fun `大端短整数与整数`() {
        assertEquals("258", HexInterpreter.interpret(bytes, 0, HexInterpreter.Kind.SHORT, true))
        assertEquals("16909060", HexInterpreter.interpret(bytes, 0, HexInterpreter.Kind.INT, true))
    }

    @Test
    fun `小端整数`() {
        assertEquals("67305985", HexInterpreter.interpret(bytes, 0, HexInterpreter.Kind.INT, false))
    }

    @Test
    fun `整数无符号把负数解释成大数`() {
        // bytes[4..7] = FF FE FD FC → 有符号 -66052，无符号 4294901244
        assertEquals("-66052", HexInterpreter.interpret(bytes, 4, HexInterpreter.Kind.INT, true))
        assertEquals("4294901244", HexInterpreter.interpret(bytes, 4, HexInterpreter.Kind.INT_UNSIGNED, true))
    }

    @Test
    fun `长整数需要 8 字节`() {
        assertNull(HexInterpreter.interpret(bytes, 8, HexInterpreter.Kind.LONG, true))
    }

    @Test
    fun `浮点数按位解释`() {
        // 0x3F800000 = 1.0f
        val f = byteArrayOf(0x3F, 0x80.toByte(), 0x00, 0x00)
        assertEquals("1.0", HexInterpreter.interpret(f, 0, HexInterpreter.Kind.FLOAT, true))
    }

    @Test
    fun `UTF8 字符串读到零字节`() {
        assertEquals("ABC", HexInterpreter.interpret(bytes, 8, HexInterpreter.Kind.UTF8, true))
    }

    @Test
    fun `Unicode 字符串按 UTF16 解码`() {
        // '中' = U+4E2D → 大端 4E 2D
        val u = byteArrayOf(0x4E, 0x2D, 0x00, 0x00)
        assertEquals("中", HexInterpreter.interpret(u, 0, HexInterpreter.Kind.UNICODE, true))
    }

    @Test
    fun `越界返回 null`() {
        assertNull(HexInterpreter.interpret(bytes, 100, HexInterpreter.Kind.BYTE, true))
    }

    @Test
    fun `一次算全部类型`() {
        val all = HexInterpreter.interpretAll(bytes, 0, true)
        assertEquals(true, all.any { it.first == HexInterpreter.Kind.INT })
        assertEquals(true, all.any { it.first == HexInterpreter.Kind.FLOAT })
    }
}
