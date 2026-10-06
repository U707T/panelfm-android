package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.model.SortSpec

/** 进度回调：total 未知时为 -1。引擎层负责节流（~5Hz）。 */
fun interface ProgressCallback {
    fun onProgress(done: Long, total: Long)
}

object NoProgress : ProgressCallback {
    override fun onProgress(done: Long, total: Long) = Unit
}

/**
 * 「未提交写入」的落地名：`.<名字>.panelfm.part`。
 *
 * **引擎与各协议实现必须用同一个名字**：`TransferTask` 的断点续传要按这个名字去 `stat`
 * 临时文件的大小（`partSize >= entry.offset` 才敢从断点续写）。这个名字以前在 4 处各写一遍
 * 字符串字面量（本地 / SFTP / SMB 写侧 + 传输引擎），任何一处改动都会让续传悄悄失效。
 */
const val PART_SUFFIX = ".panelfm.part"

fun partNameOf(name: String): String = ".$name$PART_SUFFIX"

data class SpaceInfo(val total: Long, val free: Long)

data class ListOptions(
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = true,
    val filter: String? = null,
)

/**
 * 随机访问读：所有协议一套接口 —— 上有进度/续传/流媒体，下有 Range/seek/块读取。
 */
interface VfsReader : AutoCloseable {
    val size: Long?
    val supportsSeek: Boolean
    val etag: String? get() = null
    val lastModified: Long get() = -1L
    val position: Long

    suspend fun seek(position: Long)
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    suspend fun readFullyAt(position: Long, length: Int): ByteArray
    override fun close()
}

/** 随机访问写：commit 前的内容对最终用户不可见（临时文件 / 分片上传）。 */
interface VfsWriter : AutoCloseable {
    val writtenBytes: Long
    val resumable: Resumability get() = Resumability.NONE

    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun flush()
    suspend fun commit()
    suspend fun abort()

    override fun close() = Unit
}

/** 便于单测/小文件的内存 Reader。 */
class ByteArrayReader(private val data: ByteArray) : VfsReader {
    private var pos = 0L
    override val size: Long get() = data.size.toLong()
    override val supportsSeek: Boolean get() = true
    override val position: Long get() = pos

    override suspend fun seek(position: Long) {
        pos = position.coerceIn(0, data.size.toLong())
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(length.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buffer, offset, n)
        pos += n
        return n
    }

    override suspend fun readFullyAt(position: Long, length: Int): ByteArray {
        val start = position.toInt().coerceIn(0, data.size)
        val end = (start + length).coerceAtMost(data.size)
        return data.copyOfRange(start, end)
    }

    override fun close() = Unit
}
