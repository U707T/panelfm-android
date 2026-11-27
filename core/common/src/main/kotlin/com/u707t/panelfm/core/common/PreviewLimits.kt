package com.u707t.panelfm.core.common

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/** Copies at most [max] bytes and probes one more byte. True means truncated, never complete. */
suspend fun copyPreviewBounded(
    max: Long,
    read: suspend (ByteArray, Int, Int) -> Int,
    write: (ByteArray, Int) -> Unit,
): Boolean {
    require(max >= 0 && max < Long.MAX_VALUE)
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var zeroReads = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer, 0, minOf(buffer.size.toLong(), max - copied + 1).toInt())
        if (count < 0) return false
        if (count == 0) {
            if (++zeroReads >= 3) throw IOException("读取无进展（已读 $copied 字节）")
            continue
        }
        zeroReads = 0
        currentCoroutineContext().ensureActive()
        val accepted = minOf(count.toLong(), max - copied).toInt()
        if (accepted > 0) write(buffer, accepted)
        copied += count
        if (copied > max) return true
    }
}

/** Enforces actual consumed bytes, including one EOF probe, rather than trusting metadata. */
class PreviewLimitedInputStream(
    input: InputStream,
    private val max: Long,
    private val checkCancelled: () -> Unit = {},
) : FilterInputStream(input) {
    private var consumed = 0L
    init { require(max >= 0 && max < Long.MAX_VALUE) }
    override fun read(): Int {
        checkCancelled()
        val value = super.read()
        if (value >= 0 && ++consumed > max) throw IOException("预览输入超过 $max 字节上限")
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        if (length == 0) return 0
        val count = `in`.read(bytes, offset, minOf(length.toLong(), max - consumed + 1).toInt())
        if (count > 0) {
            consumed += count
            if (consumed > max) throw IOException("预览输入超过 $max 字节上限")
        }
        return count
    }
    override fun skip(count: Long): Long {
        if (count <= 0) return 0
        val buffer = ByteArray(minOf(count, 8192).toInt())
        var skipped = 0L
        while (skipped < count) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (n < 0) break
            if (n == 0) throw IOException("读取无进展")
            skipped += n
        }
        return skipped
    }
    override fun markSupported(): Boolean = false
    override fun reset() { throw IOException("不支持重置有界输入") }
}

/** ARGB target: at most 4M pixels (~16 MiB), with no edge above 4096. */
fun pdfRenderSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "PDF 页面尺寸无效" }
    val area = width.toDouble() * height
    val scale = minOf(3.0, 1600.0 / width, 4096.0 / height, sqrt(4_000_000.0 / area))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
