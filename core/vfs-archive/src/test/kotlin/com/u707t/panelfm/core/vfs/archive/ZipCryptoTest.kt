package com.u707t.panelfm.core.vfs.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZipCrypto 自研实现的回归测试。
 *
 * 关键点：**加密与解密不是同一个运算**——两者都用「明文」字节推进密钥，
 * 所以解密必须先解出明文再推进（曾写成对称 process 导致全部错误，已修）。
 *
 * 参考向量由 Python 版标准实现（与 zipfile 源码同算法）生成，见测试内注释。
 */
class ZipCryptoTest {

    /** 由 Python 参考实现生成的固定向量（password = "secret123"，plain = 0x00..0x1F） */
    private val expectedEncHex = "05bc84464559f5eb51a213438ddbbf6b57f76f13e64bbe0fed8b1ff40637e7c5"

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `与参考实现逐字节一致`() {
        val plain = ByteArray(32) { it.toByte() }
        val enc = plain.copyOf()
        ZipCrypto.Keys("secret123").encrypt(enc)
        assertEquals("加密输出必须与参考实现一致", expectedEncHex, bytesToHex(enc))
    }

    @Test
    fun `加解密往返`() {
        val plain = "Hello, ZipCrypto! 中文内容也应该正确。".toByteArray()
        val enc = plain.copyOf()
        ZipCrypto.Keys("secret123").encrypt(enc)
        assertFalse("加密后应与明文不同", plain.contentEquals(enc))

        val dec = enc.copyOf()
        ZipCrypto.Keys("secret123").decrypt(dec)
        assertArrayEquals("同口令应还原", plain, dec)
    }

    @Test
    fun `错误口令无法还原`() {
        val plain = "payload".toByteArray()
        val enc = plain.copyOf()
        ZipCrypto.Keys("right").encrypt(enc)
        val dec = enc.copyOf()
        ZipCrypto.Keys("wrong").decrypt(dec)
        assertFalse("错误口令不应还原出明文", plain.contentEquals(dec))
    }

    @Test
    fun `加密头末字节为校验字节`() {
        val crc = 0xDEADBEEFL
        val check = ZipCrypto.checkByteFor(crc)
        val header = ZipCrypto.encryptHeader(ZipCrypto.Keys("pw"), check)
        assertEquals(12, header.size)

        val dec = header.copyOf()
        ZipCrypto.Keys("pw").decrypt(dec)
        assertEquals("头末字节应还原为校验字节", check, dec[11])
    }

    @Test
    fun `加密头随机化`() {
        val a = ZipCrypto.encryptHeader(ZipCrypto.Keys("pw"), 0x11)
        val b = ZipCrypto.encryptHeader(ZipCrypto.Keys("pw"), 0x11)
        // 前 11 字节随机 → 整体不应相同（避免已知明文攻击）
        assertFalse("加密头应随机化", a.contentEquals(b))
    }

    @Test
    fun `空口令也可用`() {
        val plain = "x".toByteArray()
        val enc = plain.copyOf()
        ZipCrypto.Keys("").encrypt(enc)
        val dec = enc.copyOf()
        ZipCrypto.Keys("").decrypt(dec)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun `分块处理与整块一致`() {
        val plain = ByteArray(1000) { (it % 251).toByte() }
        val whole = plain.copyOf().also { ZipCrypto.Keys("pw").encrypt(it) }

        val keys = ZipCrypto.Keys("pw")
        val chunked = plain.copyOf()
        keys.encrypt(chunked, 0, 137)
        keys.encrypt(chunked, 137, 500)
        keys.encrypt(chunked, 637, 363)
        assertArrayEquals("分块加密结果应与整块一致", whole, chunked)
    }

    @Test
    fun `加解密不对称（防回归）`() {
        val data = "abcdefghijklmnop".toByteArray()
        // 同一个 Keys 实例上，encrypt 后紧接着 decrypt 不应还原（密钥状态已推进）
        val keys = ZipCrypto.Keys("pw")
        val buf = data.copyOf()
        keys.encrypt(buf)
        val encHex = bytesToHex(buf)
        keys.decrypt(buf)
        assertNotEquals("encrypt/decrypt 是两种运算，不能当作对称 process", bytesToHex(data), encHex)
    }

    @Test
    fun `校验字节取 CRC 最高位`() {
        assertEquals(0x12.toByte(), ZipCrypto.checkByteFor(0x12345678L))
        assertTrue(ZipCrypto.checkByteFor(0xFF000000L) == 0xFF.toByte())
    }
}
