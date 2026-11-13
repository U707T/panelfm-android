package com.u707t.panelfm.core.apk

// 小端读写（AXML / 清单里全是小端；Kotlin 没有无符号类型，这里统一按 Int 处理，够用）

internal fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

internal fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

internal fun u32(b: ByteArray, o: Int): Int =
    u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

internal fun putU16(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
}

internal fun putU32(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    b[o + 2] = ((v ushr 16) and 0xFF).toByte()
    b[o + 3] = ((v ushr 24) and 0xFF).toByte()
}

/** 无符号 32 位：0xFFFFFFFF 在 Int 里是 -1，转 Long 要补 0xFFFFFFFF */
internal fun u32u(b: ByteArray, o: Int): Long = u32(b, o).toLong() and 0xFFFFFFFFL
