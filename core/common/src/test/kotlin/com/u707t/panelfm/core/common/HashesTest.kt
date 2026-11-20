package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 十六进制 / 摘要（缓存键、校验值、S3 签名、会话指纹共用一套）。
 *
 * 重点不是「能算出来」，而是**固定 ASCII 小写**：这行以前被抄了 9 次，
 * 少写一次 `Locale.ROOT` 就会在阿拉伯语等 locale 下变成非 ASCII 数字，
 * 于是缓存键随系统语言漂移、校验值跟别的工具对不上。
 */
class HashesTest {

    @Test
    fun `toHex 是小写两位十六进制`() {
        assertEquals("00ff1020", byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x20).toHex())
        assertEquals("", ByteArray(0).toHex())
        // 0x0A 必须是 "0a" 而不是 "a"
        assertEquals("0a", byteArrayOf(0x0A).toHex())
    }

    @Test
    fun `sha256Hex 全长是 64 位`() {
        assertEquals(64, sha256Hex("abc".toByteArray()).length)
        // 已知向量：SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `sha256Hex 取前 8 字节与全长前缀一致`() {
        val full = sha256Hex("panel fm".toByteArray())
        val short = sha256Hex("panel fm".toByteArray(), takeBytes = 8)
        assertEquals(16, short.length)
        assertEquals(full.take(16), short)
    }

    @Test
    fun `takeBytes 超界时退回全长而不是报错`() {
        assertEquals(64, sha256Hex("x".toByteArray(), takeBytes = 999).length)
        assertEquals(64, sha256Hex("x".toByteArray(), takeBytes = 0).length)
    }

    @Test
    fun `sha1Hex 与已知向量一致`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc".toByteArray()))
    }

    @Test
    fun `digestHex 的算法名原样透传`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digestHex("MD5", "abc".toByteArray()))
        assertNotEquals(digestHex("MD5", "abc".toByteArray()), digestHex("SHA-1", "abc".toByteArray()))
    }
}
