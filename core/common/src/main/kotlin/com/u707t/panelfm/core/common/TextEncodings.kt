package com.u707t.panelfm.core.common

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编码识别（零依赖）：BOM → 严格 UTF-8 校验 → GBK → Latin-1 兜底。
 * Android 平台自带 GBK/GB18030/Big5 等 Charset（ICU 支撑），无需额外库。
 */
object TextEncodings {

    data class Decoded(val charset: String, val text: String)

    /**
     * ⚠️ `CharsetDecoder` **不是线程安全**的（内部持有可变状态）。
     *
     * 旧实现把它缓存在单例里复用，而解码会被预览 / 搜索 / 缩略图 / 传输多个线程同时调用，
     * 并发时会读到彼此的中间状态（表现为随机判定「不是 UTF-8」而误回退 GBK，或直接抛
     * `IllegalStateException`）。这里改为每次新建：一次解码只建一个 decoder，开销可忽略。
     */
    private fun strictUtf8Decoder() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun decode(bytes: ByteArray): Decoded = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Decoded("UTF-8 (BOM)", String(bytes, 3, bytes.size - 3, Charsets.UTF_8))

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Decoded("UTF-16LE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Decoded("UTF-16BE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))

        isValidUtf8(bytes) -> Decoded("UTF-8", String(bytes, Charsets.UTF_8))

        else -> {
            val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
            if (gbk != null && !gbk.contains('\uFFFD')) Decoded("GBK", gbk)
            else Decoded("ISO-8859-1", String(bytes, Charsets.ISO_8859_1))
        }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        strictUtf8Decoder().decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    // ------------------------------------------------------------------ 特定编码的读写
    // 下面两个函数与 decode() 共用同一套编码名；「编码名 → 编解码器」的映射只此一处维护，
    // 编辑器的分段读取 / 保存写回都走这里（避免读 / 写 / 识别三张表各自漂移）。

    /**
     * 按识别出的 [charset] 解码一段字节（分段浏览用）。
     * UTF-8 系与 UTF-16 系会剥离可能存在的 BOM（首页分段带 BOM 时用）。
     */
    fun decodeWith(charset: String, bytes: ByteArray): String = when {
        charset.startsWith("UTF-8") -> {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
        charset == "GBK" -> String(bytes, Charset.forName("GBK"))
        charset == "UTF-16LE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16LE)
        }
        charset == "UTF-16BE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16BE)
        }
        charset == "ISO-8859-1" -> String(bytes, Charsets.ISO_8859_1)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** [encode] 的产物。[charset] 是**实际写入**的编码名（无法表示时已回退 UTF-8）。 */
    data class Encoded(val bytes: ByteArray, val charset: String)

    /**
     * 按 [charset] 编码写回（保存用）：
     * - `"UTF-8 (BOM)"` / `"UTF-16LE/BE"` **重建 BOM**——UTF-16 这类文件完全靠 BOM 被识别，
     *   丢 BOM 保存后连本应用自己都读不回来（会走 UTF-8 / GBK 启发式 → 乱码）；
     * - GBK / ISO-8859-1 做往返校验：无法表示的字符（编码器会写成 `?`）改为回退 UTF-8 输出，
     *   由 [Encoded.charset] 告知调用方实际编码，状态栏据此提示「已转存」。
     */
    fun encode(text: String, charset: String): Encoded = when (charset) {
        "UTF-8 (BOM)" -> Encoded(BOM_UTF8 + text.toByteArray(Charsets.UTF_8), charset)
        "UTF-8" -> Encoded(text.toByteArray(Charsets.UTF_8), charset)
        "UTF-16LE" -> Encoded(BOM_UTF16LE + text.toByteArray(Charsets.UTF_16LE), charset)
        "UTF-16BE" -> Encoded(BOM_UTF16BE + text.toByteArray(Charsets.UTF_16BE), charset)
        "GBK" -> encodeChecked(text, Charset.forName("GBK"), charset)
        "ISO-8859-1" -> encodeChecked(text, Charsets.ISO_8859_1, charset)
        else -> Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun encodeChecked(text: String, cs: Charset, name: String): Encoded {
        val bytes = text.toByteArray(cs)
        return if (String(bytes, cs) == text) Encoded(bytes, name)
        else Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }
}
