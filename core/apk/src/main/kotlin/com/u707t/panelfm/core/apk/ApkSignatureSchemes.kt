package com.u707t.panelfm.core.apk

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * APK 用到的签名方案（MT「签名状态 `V1 + V2 + V3`」）。
 *
 * @param v1 JAR 签名（`META-INF` 下的 `.SF` 与 `.RSA`/`.DSA`/`.EC` 同时存在）
 * @param v2 APK Signing Block 里的 `0x7109871a` 块
 * @param v3 APK Signing Block 里的 `0xf05368c0` 块
 * @param v31 `0x1b93ad61`（v3.1，密钥轮换；与 V3 同时出现，MT 不单列，这里保留只为完整）
 */
data class SignatureSchemes(
    val v1: Boolean = false,
    val v2: Boolean = false,
    val v3: Boolean = false,
    val v31: Boolean = false,
) {
    val any: Boolean get() = v1 || v2 || v3 || v31

    /** MT 的写法：`V1 + V2 + V3`；一个都没有 = `未签名` */
    fun label(): String {
        if (!any) return UNSIGNED
        return buildList {
            if (v1) add("V1")
            if (v2) add("V2")
            if (v3) add("V3")
            if (v31) add("V3.1")
        }.joinToString(" + ")
    }

    companion object {
        const val UNSIGNED = "未签名"
        val NONE = SignatureSchemes()
    }
}

/**
 * 只读 zip 结构，判断 APK **用了哪些签名方案**（不校验签名是否有效、也不验证书链）。
 *
 * 为什么不直接用 apksig 的 `ApkVerifier`：那是完整校验 —— 要读全部条目算摘要，
 * 几百 MB 的包要好几秒，而且**改了任意一个字节就整体失败**（分包合并后的包、二次签名的包
 * 都常见）。这里要的只是 MT 那行「V1 + V2 + V3」，属于结构信息：
 *
 *  - V1：中央目录里同时存在 `META-INF` 下的 `.SF` 与 `.RSA`/`.DSA`/`.EC`；
 *  - V2/V3/V3.1：中央目录前有没有 APK Signing Block，块里有哪些 ID-value 对。
 *
 * 已知边界：**存在 ≠ 有效**。签名被破坏的包仍会显示 V2，这与 MT 一致（MT 同样按结构显示）；
 * 要看有效性请用「安装」—— 系统安装器会做真正的校验。
 */
object ApkSignatures {

    private const val EOCD_SIG = 0x06054b50L
    private const val ZIP64_LOCATOR_SIG = 0x07064b50L
    private const val ZIP64_EOCD_SIG = 0x06064b50L
    private const val EOCD_MIN_SIZE = 22
    /** EOCD 后面最多跟 64 KB 注释（zip 规范上限） */
    private const val EOCD_MAX_SEARCH = 0xFFFF + EOCD_MIN_SIZE

    private const val SIGNING_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID = 0x7109871aL
    private const val V3_BLOCK_ID = 0xf05368c0L
    private const val V31_BLOCK_ID = 0x1b93ad61L
    /** 签名块上限：正常几 KB；超过 16 MB 说明结构异常，别把内存押上去 */
    private const val MAX_BLOCK_BYTES = 16L * 1024 * 1024

    /** 读不出来（截断 / 不是 zip）时返回 [SignatureSchemes.NONE]，由调用方显示「未签名」 */
    fun detect(file: File): SignatureSchemes {
        val v1 = runCatching { hasV1(file) }.getOrDefault(false)
        val blockIds = runCatching { signingBlockIds(file) }.getOrDefault(emptySet())
        return SignatureSchemes(
            v1 = v1,
            v2 = V2_BLOCK_ID in blockIds,
            v3 = V3_BLOCK_ID in blockIds,
            v31 = V31_BLOCK_ID in blockIds,
        )
    }

    private fun hasV1(file: File): Boolean = ZipFile(file).use { zip ->
        var sf = false
        var block = false
        for (entry in zip.entries()) {
            val name = entry.name
            if (!name.startsWith("META-INF/")) continue
            val upper = name.uppercase(java.util.Locale.ROOT)
            if (upper.endsWith(".SF")) sf = true
            if (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")) block = true
            if (sf && block) return@use true
        }
        sf && block
    }

    /** APK Signing Block 里出现的块 ID（没有块 = 空集） */
    private fun signingBlockIds(file: File): Set<Long> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length < EOCD_MIN_SIZE) return emptySet()
        val cdOffset = centralDirectoryOffset(raf, length) ?: return emptySet()
        if (cdOffset < 32) return emptySet()

        // 块尾：size(8) + magic(16)，紧贴中央目录
        val tail = ByteArray(24)
        raf.seek(cdOffset - 24)
        raf.readFully(tail)
        if (String(tail, 8, 16, Charsets.US_ASCII) != SIGNING_BLOCK_MAGIC) return emptySet()

        val blockSize = readU64(tail, 0)
        if (blockSize < 24 || blockSize > MAX_BLOCK_BYTES) return emptySet()
        val start = cdOffset - blockSize - 8
        if (start < 0) return emptySet()

        val block = ByteArray(blockSize.toInt())
        raf.seek(start + 8)
        raf.readFully(block)
        // 去掉末尾的 size(8) + magic(16)
        val end = block.size - 24
        val ids = LinkedHashSet<Long>()
        var pos = 0
        while (pos + 12 <= end) {
            val pairLength = readU64(block, pos)
            if (pairLength < 4 || pos + 8 + pairLength > end) break
            ids += readU32(block, pos + 8)
            pos += 8 + pairLength.toInt()
        }
        ids
    }

    /** 中央目录起始偏移（支持 zip64：EOCD 里写 0xFFFFFFFF 时去 zip64 记录里取） */
    private fun centralDirectoryOffset(raf: RandomAccessFile, length: Long): Long? {
        val tailLength = minOf(length, EOCD_MAX_SEARCH.toLong()).toInt()
        val tail = ByteArray(tailLength)
        raf.seek(length - tailLength)
        raf.readFully(tail)
        val eocd = lastIndexOf(tail, EOCD_SIG) ?: return null
        val eocdOffset = length - tailLength + eocd
        val cdOffset = readU32(tail, eocd + 16)
        if (cdOffset != 0xFFFFFFFFL) return cdOffset

        // zip64：EOCD 前 20 字节是 locator（sig + 盘号 + zip64 EOCD 偏移 + 总盘数）
        if (eocdOffset < 20) return null
        val locator = ByteArray(20)
        raf.seek(eocdOffset - 20)
        raf.readFully(locator)
        if (readU32(locator, 0) != ZIP64_LOCATOR_SIG) return null
        val zip64Offset = readU64(locator, 8)
        if (zip64Offset < 0 || zip64Offset + 56 > length) return null
        val record = ByteArray(56)
        raf.seek(zip64Offset)
        raf.readFully(record)
        if (readU32(record, 0) != ZIP64_EOCD_SIG) return null
        return readU64(record, 48)
    }

    /** 从尾部往前找 4 字节签名（注释里可能也出现同样的字节，所以取最后一个合法位置） */
    private fun lastIndexOf(bytes: ByteArray, signature: Long): Int? {
        var i = bytes.size - EOCD_MIN_SIZE
        while (i >= 0) {
            if (readU32(bytes, i) == signature) return i
            i--
        }
        return null
    }

    private fun readU32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun readU64(b: ByteArray, o: Int): Long {
        val low = readU32(b, o)
        val high = readU32(b, o + 4)
        // 高位非 0 说明长度已经荒谬（> 4 GB），按负数交给调用方判掉
        if (high > Int.MAX_VALUE) return -1
        return low or (high shl 32)
    }
}
