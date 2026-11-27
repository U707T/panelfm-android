package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.vfs.VfsException
import java.io.InputStream
import java.io.IOException

internal fun validateDavRangeResponse(code: Int, contentRange: String?, offset: Long, requestedLength: Long) {
    if (offset < 0L || requestedLength <= 0L) throw VfsException.ProtocolError("Range 长度或偏移无效")
    if (code == 200 && offset == 0L) return
    if (code != 206) throw VfsException.ProtocolError("Range 读取失败：HTTP $code")
    val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(contentRange.orEmpty())
        ?: throw VfsException.ProtocolError("Range 响应缺少有效 Content-Range")
    val start = match.groupValues[1].toLongOrNull()
        ?: throw VfsException.ProtocolError("Content-Range 起点无效")
    val end = match.groupValues[2].toLongOrNull()
        ?: throw VfsException.ProtocolError("Content-Range 终点无效")
    if (start != offset || end < start || end - start >= requestedLength) {
        throw VfsException.ProtocolError("Content-Range 与请求偏移/长度不符")
    }
}

internal fun readDavBounded(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val buffer = ByteArray(minOf(maxBytes, 16 * 1024))
    val output = java.io.ByteArrayOutputStream(buffer.size)
    var zeroReads = 0
    while (output.size() < maxBytes) {
        val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
        if (n < 0) break
        if (n == 0) {
            if (++zeroReads >= 3) throw IOException("Range 响应连续无进展")
        } else {
            zeroReads = 0
            output.write(buffer, 0, n)
        }
    }
    return output.toByteArray()
}
