package com.u707t.panelfm.core.vfs.archive

/**
 * ZIP 传统加密（ZipCrypto / PKWARE，`ZipArchiveOutputStream` 写不出来的那套）。
 *
 * 背景：commons-compress 1.27 **只能读**加密 ZIP（`ZipArchiveInputStream(in, password)`），
 * 没有写侧 API —— `addRawArchiveEntry` 见到 encryption 标志会抛
 * `UnsupportedZipFeatureException: encryption`，`putArchiveEntry` 又会把 compressedSize 算错
 * （少算 12 字节加密头）。所以写侧由 [EncryptedZipWriter] 自研，本文件只负责密钥流。
 *
 * 算法（PKWARE APPNOTE 6.1）：
 *  - 密钥由口令初始化出 3 个 32 位状态字（key0/1/2）
 *  - 每字节：`明文 ^= decryptByte(); updateKeys(明文)`
 *  - 加密头 12 字节：前 11 随机，末 1 = 校验字节（CRC32 高字节）
 *
 * 加解密对称，同一份代码可读可写；读侧仍优先用 commons-compress 交叉验证。
 */
object ZipCrypto {

    private const val HEADER_SIZE = 12

    private val CRC_TABLE: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -306674912 else c ushr 1 } // 0xEDB88320
        c
    }

    /** 加/解密状态（对称运算） */
    class Keys(password: String) {
        private var k0 = 0x12345678
        private var k1 = 0x23456789
        private var k2 = 0x34567890

        init {
            // 口令按 **UTF-8 字节**参与密钥流（第 5 批审计 🔵10）。
            // 旧实现 `ch.code.toByte()` 是 UTF-16 码元截断：与注释声称的「平台默认编码」不符，
            // 且非 ASCII 口令与外部工具（Python zipfile / 7-Zip 等按 UTF-8 取字节）互不兼容 ——
            // 对方打的加密包即便口令正确也会被判「口令不正确」。
            password.toByteArray(Charsets.UTF_8).forEach { updateKeys(it) }
        }

        private fun crc32Update(crc: Int, b: Byte): Int =
            (crc ushr 8) xor CRC_TABLE[(crc xor b.toInt()) and 0xFF]

        private fun updateKeys(b: Byte) {
            k0 = crc32Update(k0, b)
            k1 = k1 + (k0 and 0xFF)
            k1 = k1 * 134775813 + 1
            k2 = crc32Update(k2, (k1 ushr 24).toByte())
        }

        private fun streamByte(): Byte {
            val temp = (k2 or 2) and 0xFFFF
            return ((temp * (temp xor 1)) ushr 8).toByte()
        }

        /**
         * 加密：[data] 是明文；密钥用**明文**字节推进。
         * 注意加解密不对称——解密要用解出来的明文字节推进（见 [decrypt]）。
         */
        fun encrypt(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
            for (i in off until off + len) {
                val p = data[i]
                data[i] = ((p.toInt() and 0xFF) xor (streamByte().toInt() and 0xFF)).toByte()
                updateKeys(p)
            }
        }

        /** 解密：先用密钥流解出明文，再用该明文字节推进密钥 */
        fun decrypt(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
            for (i in off until off + len) {
                val p = (((data[i].toInt() and 0xFF) xor (streamByte().toInt() and 0xFF)) and 0xFF).toByte()
                data[i] = p
                updateKeys(p)
            }
        }
    }

    /** 传统加密的校验字节 = CRC32 最高字节 */
    fun checkByteFor(crc: Long): Byte = ((crc ushr 24) and 0xFF).toByte()

    /**
     * 生成并加密 12 字节头。
     * 前 11 字节随机（同明文口令下每次输出不同，避免已知明文攻击），末字节为 [checkByte]。
     */
    fun encryptHeader(keys: Keys, checkByte: Byte, random: java.util.Random = java.util.Random()): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        random.nextBytes(header)
        header[HEADER_SIZE - 1] = checkByte
        keys.encrypt(header)
        return header
    }

    /** 加密头长度（计入 compressedSize） */
    const val HEADER_LENGTH: Int = HEADER_SIZE
}
