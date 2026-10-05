package com.u707t.panelfm.core.vfs.archive

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 加密 ZIP 写侧（传统 ZipCrypto）。
 *
 * 为什么自研：commons-compress 1.27 **只有**加密读侧（`ZipArchiveInputStream(in, pwd)`），
 * 没有写侧 API（`addRawArchiveEntry` 要求传入的是「已经是 zip 格式」的流，喂裸数据会抛
 * `UnsupportedZipFeatureException: encryption`）。MT 的「密码（不加密请留空）」对 zip
 * 用的正是传统加密，所以这里按 APPNOTE 6.1 手写容器格式。
 *
 * 采用**流式**写法（Data Descriptor，general purpose bit 3）：
 *  - 本地头先写占位 0，数据边压边写，最后补 Data Descriptor（crc / 压缩后大小 / 原大小）
 *  - bit 3 置位时，加密头末字节用 **DOS 时间高字节**（APPNOTE 允许；避免为了拿 CRC 而缓冲整个文件）
 *  - 条目大小未知也不影响：中央目录里用计数得到的大小
 *
 * 局限：不写 Zip64（单文件 / 总偏移 ≥ 4GB 时抛错，提示改用 7z）。
 * 目录条目不加密（与主流实现一致）。
 */
class EncryptedZipWriter(
    private val out: OutputStream,
    password: String,
    /** Deflater 压缩级别（0–9）；[store] 为 true 时忽略 */
    private val deflateLevel: Int = Deflater.DEFAULT_COMPRESSION,
    /** 「仅存储」：条目以 STORED（method 0）写入，不做压缩 */
    private val store: Boolean = false,
) {
    private val password = password
    private var keys = ZipCrypto.Keys(password)
    private val central = mutableListOf<CentralRecord>()

    private data class CentralRecord(
        val nameBytes: ByteArray,
        val method: Int,
        val flags: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private var offset = 0L

    private fun write(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        out.write(b, off, len)
        offset += len
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )

    /** DOS 时间（低 16 位）/ 日期（高 16 位），由 epoch millis 转换 */
    private fun dosDateTime(epochMillis: Long): Pair<Int, Int> {
        val z = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        val year = (z.year - 1980).coerceIn(0, 127)
        val time = (z.hour shl 11) or (z.minute shl 5) or (z.second / 2)
        val date = (year shl 9) or (z.monthValue shl 5) or z.dayOfMonth
        return time to date
    }

    private fun check4GB(what: String, value: Long) {
        if (value >= 0xFFFFFFFFL) {
            throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("$what 超过 4GB，加密 ZIP 请改用 7z 格式")
        }
    }

    /** 写一个目录条目（不加密） */
    fun putDirectory(name: String, epochMillis: Long) {
        val entryName = if (name.endsWith("/")) name else "$name/"
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        val (time, date) = dosDateTime(epochMillis)
        val localOffset = offset
        check4GB("压缩包", localOffset)

        write(u32(0x04034b50L)); write(u16(20)); write(u16(0x0800)); write(u16(0))
        write(u16(time)); write(u16(date)); write(u32(0)); write(u32(0)); write(u32(0))
        write(u16(nameBytes.size)); write(u16(0)); write(nameBytes)

        central += CentralRecord(nameBytes, 0, 0x0800, time, date, 0, 0, 0, localOffset)
    }

    /**
     * 写一个文件条目（加密）。
     * 流式：调用方通过 [writeChunk] 提供明文，内部边压边加密边写。
     */
    fun putFileStreaming(name: String, epochMillis: Long, uncompressedSize: Long, reader: (ByteArray) -> Int): Long {
        // 关键：ZIP 传统加密的密钥流是 **per-entry** 的（每个条目用同口令重新初始化），
        // 不像 RAR/7z 那样全流连续。曾误以为要跨条目推进，导致第二个条目起全部解不开。
        keys = ZipCrypto.Keys(password)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val (time, date) = dosDateTime(epochMillis)
        val localOffset = offset
        check4GB("压缩包", localOffset)

        // bit0 = 加密，bit3 = data descriptor，bit11 = UTF-8 名字
        val flags = 0x0001 or 0x0008 or 0x0800
        val method = if (store) METHOD_STORED else METHOD_DEFLATED
        write(u32(0x04034b50L)); write(u16(20)); write(u16(flags)); write(u16(method))
        write(u16(time)); write(u16(date)); write(u32(0)); write(u32(0)); write(u32(0))
        write(u16(nameBytes.size)); write(u16(0)); write(nameBytes)

        // 加密头：bit3 置位 → 校验字节用 DOS 时间高字节
        val check = ((time ushr 8) and 0xFF).toByte()
        val header = ZipCrypto.encryptHeader(keys, check)
        write(header)

        val crc = CRC32()
        var uncompressed = 0L
        var compressed = 0L
        val plainBuf = ByteArray(64 * 1024)

        if (store) {
            // 「仅存储」：明文直接加密搬运。注意顺序 —— 必须**先 CRC 后加密**，
            // 因为 encrypt() 会原地改写 plainBuf，先加密会把 CRC 算在密文上。
            while (true) {
                val n = reader(plainBuf)
                if (n < 0) break
                crc.update(plainBuf, 0, n)
                uncompressed += n
                keys.encrypt(plainBuf, 0, n)
                write(plainBuf, 0, n)
                compressed += n
            }
        } else {
            // nowrap = true：ZIP 里存的是**裸 deflate**，不能带 zlib 头（否则解出来是 78 9c 开头 → inflate 失败）
            val deflater = Deflater(deflateLevel, true)
            val defBuf = ByteArray(DEFLATE_CHUNK)
            try {
                while (true) {
                    // reader 把明文写进 plainBuf 并返回字节数；-1 表示 EOF。
                    // （早期版本用 () -> Int 让调用方写自己的缓冲区，结果写侧永远压到全零——务必保持此签名）
                    val n = reader(plainBuf)
                    if (n < 0) break
                    crc.update(plainBuf, 0, n)
                    uncompressed += n
                    deflater.setInput(plainBuf, 0, n)
                    while (!deflater.needsInput()) {
                        val m = deflater.deflate(defBuf)
                        if (m <= 0) break
                        keys.encrypt(defBuf, 0, m)
                        write(defBuf, 0, m)
                        compressed += m
                    }
                }
                deflater.finish()
                while (!deflater.finished()) {
                    val m = deflater.deflate(defBuf)
                    if (m <= 0) break
                    keys.encrypt(defBuf, 0, m)
                    write(defBuf, 0, m)
                    compressed += m
                }
            } finally {
                deflater.end()
            }
        }

        val crcValue = crc.value
        // Data Descriptor
        write(u32(0x08074b50L)); write(u32(crcValue))
        write(u32(compressed + ZipCrypto.HEADER_LENGTH)); write(u32(uncompressed))

        check4GB("条目 $name", compressed + ZipCrypto.HEADER_LENGTH)
        check4GB("条目 $name", uncompressed)
        central += CentralRecord(
            nameBytes, method, flags, time, date, crcValue,
            compressed + ZipCrypto.HEADER_LENGTH, uncompressed, localOffset,
        )
        if (uncompressedSize >= 0 && uncompressed != uncompressedSize) {
            // 只作提示：源文件在压缩过程中被改动
            com.u707t.panelfm.core.common.Logx.w(
                "EncryptedZipWriter",
                "size mismatch for $name: declared=$uncompressedSize actual=$uncompressed",
            )
        }
        return uncompressed
    }

    /** 收尾：中央目录 + EOCD */
    fun finish() {
        val cdStart = offset
        check4GB("中央目录", cdStart)
        central.forEach { r ->
            write(u32(0x02014b50L))
            write(u16((3 shl 8) or 20))          // version made by: UNIX(3) + 20
            write(u16(20))
            write(u16(r.flags)); write(u16(r.method))
            write(u16(r.dosTime)); write(u16(r.dosDate))
            write(u32(r.crc))
            write(u32(r.compressedSize)); write(u32(r.uncompressedSize))
            write(u16(r.nameBytes.size)); write(u16(0)); write(u16(0))
            write(u16(0)); write(u16(0)); write(u32(0))
            write(u32(r.localHeaderOffset))
            write(r.nameBytes)
        }
        val cdSize = offset - cdStart
        check4GB("中央目录", cdSize)
        write(u32(0x06054b50L))
        write(u16(0)); write(u16(0))
        write(u16(central.size)); write(u16(central.size))
        write(u32(cdSize)); write(u32(cdStart))
        write(u16(0))
        out.flush()
    }

    private companion object {
        const val DEFLATE_CHUNK = 64 * 1024
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
    }
}
