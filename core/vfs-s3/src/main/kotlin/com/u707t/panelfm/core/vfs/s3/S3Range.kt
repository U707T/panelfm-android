package com.u707t.panelfm.core.vfs.s3

import com.u707t.panelfm.core.vfs.VfsException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

internal fun validateS3RangeResponse(code: Int, contentRange: String?, offset: Long, requestedLength: Long) {
    if (offset < 0L || requestedLength <= 0L) throw IllegalArgumentException("Range bounds must be positive")
    if (code == 200 && offset == 0L) return
    if (code != 206) throw VfsException.ProtocolError("S3 Range 响应状态异常：HTTP $code")
    val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(contentRange.orEmpty())
        ?: throw VfsException.ProtocolError("S3 Range 响应缺少有效 Content-Range")
    val start = match.groupValues[1].toLongOrNull()
        ?: throw VfsException.ProtocolError("S3 Content-Range 起点无效")
    val end = match.groupValues[2].toLongOrNull()
        ?: throw VfsException.ProtocolError("S3 Content-Range 终点无效")
    if (start != offset || end < start || end - start >= requestedLength) {
        throw VfsException.ProtocolError("S3 Content-Range 与请求偏移/长度不符")
    }
}

internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "Range 长度超出支持范围" }
    val chunkSize = minOf(maxBytes, 16 * 1024L).toInt()
    val out = ByteArrayOutputStream(chunkSize)
    val buffer = ByteArray(chunkSize)
    var zeroReads = 0
    while (out.size().toLong() < maxBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - out.size()).toInt())
        if (count < 0) break
        if (count == 0) {
            if (++zeroReads >= 3) throw IOException("Range 响应连续无进展")
        } else {
            zeroReads = 0
            out.write(buffer, 0, count)
        }
    }
    return out.toByteArray()
}
