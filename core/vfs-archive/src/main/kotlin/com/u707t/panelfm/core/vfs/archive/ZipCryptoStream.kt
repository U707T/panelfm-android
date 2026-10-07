package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.vfs.VfsException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 加密 ZIP（ZipCrypto）的**读侧**。
 *
 * 为什么必须自研：commons-compress 1.27.1 **没有**加密 ZIP 的读实现 ——
 * `ZipFile` / `ZipArchiveInputStream` 都没有 password 参数，只有一个
 * `PasswordRequiredException` 异常类型。所以「打开自己刚加密的包」必须自己解密。
 *
 * 结构（PKWARE APPNOTE 6.1）：
 * ```
 * [12 字节加密头][加密后的 deflate 数据或 STORED 数据]
 * ```
 *  - 加密头前 11 字节随机、第 12 字节是校验字节（bit3 置位时 = DOS 时间高字节，否则 = CRC 高字节）；
 *  - 密钥流**跨头与数据连续**，因此必须用同一个 [ZipCrypto.Keys] 实例依次解密。
 */
internal object ZipCryptoReader {

    /** 加密头长度（与写侧 `ZipCrypto.HEADER_LENGTH` 一致） */
    const val HEADER_LENGTH = 12

    /**
     * 把一个「指向加密条目原始数据」的流，变成「明文流」。
     *
     * @param raw        原始流（**必须包含 12 字节加密头**，即 `ZipFile.getRawInputStream`）
     * @param password   口令
     * @param checkByte  期望的校验字节；null = 跳过校验（老包 / 无法判定时）
     * @param method     压缩方法（0 = STORED，8 = DEFLATED）
     */
    fun decrypt(
        raw: InputStream,
        password: String,
        checkByte: Byte?,
        method: Int,
    ): InputStream {
        val keys = ZipCrypto.Keys(password)
        val header = ByteArray(HEADER_LENGTH)
        var filled = 0
        while (filled < HEADER_LENGTH) {
            val n = raw.read(header, filled, HEADER_LENGTH - filled)
            if (n <= 0) {
                raw.close()
                throw VfsException.ProtocolError("加密条目数据不完整（读取加密头失败）")
            }
            filled += n
        }
        keys.decrypt(header)

        // 校验字节不匹配 = 口令错（ZipCrypto 唯一的「口令是否正确」判定手段）
        if (checkByte != null && header[HEADER_LENGTH - 1] != checkByte) {
            raw.close()
            throw VfsException.Auth("压缩包口令不正确")
        }

        val decrypted = DecryptingInputStream(raw, keys)
        return when (method) {
            METHOD_STORED -> decrypted
            METHOD_DEFLATED -> InflaterInputStream(decrypted, Inflater(true), 64 * 1024)
            else -> {
                raw.close()
                throw VfsException.Unsupported("加密条目使用了不支持的压缩方法：$method")
            }
        }
    }

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8

    /**
     * 边读边解密的流（`ZipCrypto.Keys.decrypt` 是原地解密 + 用明文推进密钥状态）。
     */
    private class DecryptingInputStream(
        source: InputStream,
        private val keys: ZipCrypto.Keys,
    ) : FilterInputStream(source) {

        private val single = ByteArray(1)

        override fun read(): Int {
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n > 0) keys.decrypt(b, off, n)
            return n
        }

        /**
         * 位置前进必须**经过解密**：`FilterInputStream` 的默认 `skip` 直接跳原始密文流、
         * 密钥状态不推进 —— 之后所有字节都会解错（加密 STORED 条目的 seek / readFullyAt
         * 曾静默读出乱码，第 5 批审计 🟡2）。这里读进临时缓冲丢弃，顺带推进密钥。
         */
        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val buf = ByteArray(8 * 1024)
            var skipped = 0L
            while (skipped < n) {
                val want = minOf(buf.size.toLong(), n - skipped).toInt()
                val r = read(buf, 0, want)
                if (r < 0) break
                skipped += r
            }
            return skipped
        }
    }
}
