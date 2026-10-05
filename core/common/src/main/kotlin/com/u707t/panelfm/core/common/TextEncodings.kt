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

    /** 粗略判断二进制（用于「未知类型默认用 Hex 还是文本」的启发式） */
    fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        if (sample.isEmpty()) return false
        val nul = sample.count { it == 0.toByte() }
        return nul > 0
    }
}
