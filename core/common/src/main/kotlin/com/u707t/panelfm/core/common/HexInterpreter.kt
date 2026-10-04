package com.u707t.panelfm.core.common

/**
 * Hex 的「数值解释」（复刻 MT 编辑器检查面板 `0x7f0c002a`，文案 `0x7f1102e0…2e6`）。
 *
 * MT 的面板：☑ 大端模式 + 逐类型解释（字节 / 字节(无符号) / 短整数 / 短整数(无符号) /
 * 整数 / 整数(无符号) / 长整数 / 单精度浮点 / 双精度浮点 / UTF8 字符串 / Unicode 字符串）。
 *
 * 本项目只做**只读**解释（不做 Hex 编辑），所以这里只负责「给定偏移处的字节 → 各类型取值」。
 */
object HexInterpreter {

    /** 一种解释方式（[label] 直接来自 MT 的文案） */
    enum class Kind(val label: String, val width: Int) {
        BYTE("字节", 1),
        BYTE_UNSIGNED("字节(无符号)", 1),
        SHORT("短整数", 2),
        SHORT_UNSIGNED("短整数(无符号)", 2),
        INT("整数", 4),
        INT_UNSIGNED("整数(无符号)", 4),
        LONG("长整数", 8),
        FLOAT("单精度浮点数", 4),
        DOUBLE("双精度浮点数", 8),
        UTF8("UTF8 字符串", 0),
        UNICODE("Unicode 字符串", 0),
        ;
    }

    /**
     * 在 [bytes] 的 [offset] 处按 [kind] 解释。
     * 越界 / 非法时返回 null（UI 显示「—」）。
     */
    fun interpret(bytes: ByteArray, offset: Int, kind: Kind, bigEndian: Boolean): String? {
        if (offset < 0 || offset >= bytes.size) return null
        return when (kind) {
            Kind.BYTE -> bytes[offset].toInt().toString()
            Kind.BYTE_UNSIGNED -> (bytes[offset].toInt() and 0xFF).toString()
            Kind.SHORT -> readInt(bytes, offset, 2, bigEndian)?.toShort()?.toString()
            Kind.SHORT_UNSIGNED -> readInt(bytes, offset, 2, bigEndian)?.let { (it and 0xFFFF).toString() }
            Kind.INT -> readInt(bytes, offset, 4, bigEndian)?.toString()
            Kind.INT_UNSIGNED -> readInt(bytes, offset, 4, bigEndian)?.let { (it.toLong() and 0xFFFFFFFFL).toString() }
            Kind.LONG -> readLong(bytes, offset, 8, bigEndian)?.toString()
            Kind.FLOAT -> readInt(bytes, offset, 4, bigEndian)?.let { Float.fromBits(it).toString() }
            Kind.DOUBLE -> readLong(bytes, offset, 8, bigEndian)?.let { Double.fromBits(it).toString() }
            Kind.UTF8 -> readUtf8(bytes, offset)
            Kind.UNICODE -> readUnicode(bytes, offset, bigEndian)
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int, width: Int, bigEndian: Boolean): Int? {
        if (offset + width > bytes.size) return null
        var v = 0
        for (i in 0 until width) {
            val b = bytes[offset + if (bigEndian) i else width - 1 - i].toInt() and 0xFF
            v = (v shl 8) or b
        }
        return v
    }

    private fun readLong(bytes: ByteArray, offset: Int, width: Int, bigEndian: Boolean): Long? {
        if (offset + width > bytes.size) return null
        var v = 0L
        for (i in 0 until width) {
            val b = bytes[offset + if (bigEndian) i else width - 1 - i].toLong() and 0xFF
            v = (v shl 8) or b
        }
        return v
    }

    /** UTF8 字符串：从 [offset] 起读到第一个 0x00 或非法字节（最多 64 字节） */
    fun readUtf8(bytes: ByteArray, offset: Int, max: Int = 64): String? {
        if (offset >= bytes.size) return null
        var end = offset
        val limit = minOf(bytes.size, offset + max)
        while (end < limit && bytes[end] != 0.toByte()) end++
        if (end == offset) return null
        return runCatching { String(bytes, offset, end - offset, Charsets.UTF_8) }
            .getOrNull()
            ?.filter { it.code in 32..126 || it.code >= 0x4E00 }
            ?.takeIf { it.isNotBlank() }
    }

    /** Unicode 字符串：按 UTF-16 解码到第一个 0x0000（最多 32 个字符） */
    fun readUnicode(bytes: ByteArray, offset: Int, bigEndian: Boolean, maxChars: Int = 32): String? {
        val sb = StringBuilder()
        var i = offset
        var count = 0
        while (i + 1 < bytes.size && count < maxChars) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            val code = if (bigEndian) (lo shl 8) or hi else (hi shl 8) or lo
            if (code == 0) break
            if (code < 0x20 && code != 0x09 && code != 0x0A && code != 0x0D) return sb.toString().takeIf { it.isNotBlank() }
            sb.append(code.toChar())
            i += 2
            count++
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 一次性把 [offset] 处的全部解释算出来（UI 直接渲染列表） */
    fun interpretAll(bytes: ByteArray, offset: Int, bigEndian: Boolean): List<Pair<Kind, String>> =
        Kind.entries.mapNotNull { kind ->
            interpret(bytes, offset, kind, bigEndian)?.let { kind to it }
        }
}
