package com.u707t.panelfm.core.common

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编码识别（零依赖）：
 *  BOM（UTF-8 / UTF-16 / UTF-32，手写解码） → 严格 UTF-8 校验 → 东亚编码启发式 → Latin-1 兜底。
 *
 * Android 平台自带 GBK/GB18030/Big5 等 Charset（ICU 支撑），无需额外库；
 * UTF-32 因为部分平台不保证 `Charset.forName("UTF-32LE")` 可用，这里**手写**编解码。
 *
 * 启发式（**只面向中文**，不做日文 / 韩文识别）：
 *  1. GBK ↔ Big5：按「简体 / 繁体特征字」计数判别，无明显特征时**保持 GBK**（与旧行为一致）；
 *  2. 都解不出来 → Latin-1（保证任何字节都有可读文本）。
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
        // ⚠️ UTF-32LE 的 BOM（FF FE 00 00）以 FF FE 开头，必须先于 UTF-16LE 判断
        isBomUtf32Le(bytes) -> Decoded("UTF-32LE", decodeUtf32(bytes, 4, littleEndian = true))
        isBomUtf32Be(bytes) -> Decoded("UTF-32BE", decodeUtf32(bytes, 4, littleEndian = false))

        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Decoded("UTF-8 (BOM)", String(bytes, 3, bytes.size - 3, Charsets.UTF_8))

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Decoded("UTF-16LE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Decoded("UTF-16BE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))

        isValidUtf8(bytes) -> Decoded("UTF-8", String(bytes, Charsets.UTF_8))

        else -> guessLegacy(bytes)
    }

    /** 非 UTF 系的启发式（见类注释的顺序）。 */
    private fun guessLegacy(bytes: ByteArray): Decoded {
        // 旧行为的口径：GBK 宽松解出且没有替换符 —— 大量简体中文场景直接命中
        val gbkLenient = runCatching { String(bytes, charset("GBK")) }.getOrNull()
            ?.takeIf { !it.contains('\uFFFD') }
        // Big5 候选必须**严格**解出（宽松解码会把任何字节都「解出来」，无法比较）
        val big5 = strictDecode(bytes, "Big5")

        // 简 / 繁：按特征字计数；繁体明显占优才推翻 GBK（宁缺勿错）
        val simplified = gbkLenient?.count { it in SIMPLIFIED_SIGNALS } ?: -1
        val traditional = big5?.count { it in TRADITIONAL_SIGNALS } ?: -1
        if (big5 != null && traditional >= 2 && traditional > simplified) return Decoded("Big5", big5)
        gbkLenient?.let { return Decoded("GBK", it) }
        // GBK 也解不出来：Big5 的「严格解出」结果仍优于 Latin-1（乱码），最后才是 Latin-1
        big5?.let { return Decoded("Big5", it) }
        return Decoded("ISO-8859-1", String(bytes, Charsets.ISO_8859_1))
    }

    private fun strictDecode(bytes: ByteArray, charsetName: String): String? = try {
        val decoder = Charset.forName(charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
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
    fun decodeWith(charset: String, bytes: ByteArray): String = when (charset) {
        "UTF-8 (BOM)" -> {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
        "UTF-8" -> String(bytes, Charsets.UTF_8)
        "GBK" -> String(bytes, charset("GBK"))
        "Big5" -> String(bytes, charset("Big5"))
        "UTF-16LE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16LE)
        }
        "UTF-16BE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16BE)
        }
        "UTF-32LE" -> decodeUtf32(bytes, minOf(4, bytes.size), littleEndian = true)
        "UTF-32BE" -> decodeUtf32(bytes, minOf(4, bytes.size), littleEndian = false)
        "ISO-8859-1" -> String(bytes, Charsets.ISO_8859_1)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** [encode] 的产物。[charset] 是**实际写入**的编码名（无法表示时已回退 UTF-8）。 */
    data class Encoded(val bytes: ByteArray, val charset: String)

    /**
     * 按 [charset] 编码写回（保存用）：
     * - `"UTF-8 (BOM)"` / `"UTF-16LE/BE"` / `"UTF-32LE/BE"` **重建 BOM**——这类文件完全靠 BOM 被识别，
     *   丢 BOM 保存后连本应用自己都读不回来（会走 UTF-8 / GBK 启发式 → 乱码）；
     * - GBK / Big5 / ISO-8859-1 做往返校验：无法表示的字符
     *   （编码器会写成 `?`）改为回退 UTF-8 输出，由 [Encoded.charset] 告知调用方实际编码，
     *   状态栏据此提示「已转存」。
     */
    fun encode(text: String, charset: String): Encoded = when (charset) {
        "UTF-8 (BOM)" -> Encoded(BOM_UTF8 + text.toByteArray(Charsets.UTF_8), charset)
        "UTF-8" -> Encoded(text.toByteArray(Charsets.UTF_8), charset)
        "UTF-16LE" -> Encoded(BOM_UTF16LE + text.toByteArray(Charsets.UTF_16LE), charset)
        "UTF-16BE" -> Encoded(BOM_UTF16BE + text.toByteArray(Charsets.UTF_16BE), charset)
        "UTF-32LE" -> Encoded(BOM_UTF32LE + encodeUtf32(text, littleEndian = true), charset)
        "UTF-32BE" -> Encoded(BOM_UTF32BE + encodeUtf32(text, littleEndian = false), charset)
        "GBK" -> encodeChecked(text, charset("GBK"), charset)
        "Big5" -> encodeChecked(text, charset("Big5"), charset)
        "ISO-8859-1" -> encodeChecked(text, Charsets.ISO_8859_1, charset)
        else -> Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val BOM_UTF32LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
    private val BOM_UTF32BE = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())

    private fun encodeChecked(text: String, cs: Charset, name: String): Encoded {
        val bytes = text.toByteArray(cs)
        return if (String(bytes, cs) == text) Encoded(bytes, name)
        else Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    // ------------------------------------------------------------------ UTF-32（手写）

    private fun isBomUtf32Le(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()

    private fun isBomUtf32Be(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()

    /** 从 [offset] 起按 4 字节码元解 UTF-32；非法码点（越界 / 代理区 / 截断）落成 U+FFFD。 */
    private fun decodeUtf32(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - offset).coerceAtLeast(0) / 4)
        var i = offset
        while (i + 3 < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val cp = if (littleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            val valid = cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF
            sb.appendCodePoint(if (valid) cp else 0xFFFD)
            i += 4
        }
        return sb.toString()
    }

    private fun encodeUtf32(text: String, littleEndian: Boolean): ByteArray {
        val out = ByteArrayOutputStream(text.length * 4)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (littleEndian) {
                out.write(cp and 0xFF)
                out.write((cp ushr 8) and 0xFF)
                out.write((cp ushr 16) and 0xFF)
                out.write((cp ushr 24) and 0xFF)
            } else {
                out.write((cp ushr 24) and 0xFF)
                out.write((cp ushr 16) and 0xFF)
                out.write((cp ushr 8) and 0xFF)
                out.write(cp and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 特征字（GBK ↔ Big5 判别）

    /** 简体高辨识度常用字（繁体文本不会出现这些字形）。 */
    private val SIMPLIFIED_SIGNALS =
        "这们为国会说时学对里过发见经头从动两长样现将与进实点无开书车东乐买卖农语译飞马鸟鱼龙门问间闻风区医华图团园围汉体铁银钱关际应还种样据万个".toSet()

    /** 繁体高辨识度常用字（简体文本不会出现这些字形）。 */
    private val TRADITIONAL_SIGNALS =
        "這們為國會說時學對裡過發見經頭從動兩長樣現將與進實點無開書車東樂買賣農語譯飛馬鳥魚龍門問間聞風區醫華圖團園圍漢體鐵銀錢關際應還種據萬個".toSet()
}
