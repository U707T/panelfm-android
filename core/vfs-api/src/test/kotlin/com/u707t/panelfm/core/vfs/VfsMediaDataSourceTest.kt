package com.u707t.panelfm.core.vfs

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * 网络视频取帧的 VFS 桥（v2.3.4）。
 *
 * 这里的每条断言都对应一个真机上的失败形态：
 *  - 窗口命中（逐字节直通 = 每 64 KB 发一发 HTTP Range，NAS 直接被打爆）；
 *  - 文件尾 -1 / 短读（moov 在尾部的 mp4 一定会读到尾）；
 *  - 预算 & 超时（不支持 Range 的服务器 / 坏容器必须快速失败，不能把整片拖下来）。
 */
class VfsMediaDataSourceTest {

    private class CountingReader(
        private val data: ByteArray,
        private val delayMs: Long = 0,
    ) : VfsReader {
        var fetches = 0
        var fetchedBytes = 0L
        private val inner = ByteArrayReader(data)

        override val size: Long get() = data.size.toLong()
        override val supportsSeek: Boolean get() = true
        override val position: Long get() = inner.position

        override suspend fun seek(position: Long) = inner.seek(position)
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            inner.read(buffer, offset, length)

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray {
            fetches++
            fetchedBytes += length
            if (delayMs > 0) delay(delayMs)
            return inner.readFullyAt(position, length)
        }

        override fun close() = Unit
    }

    private val payload = Random(7).nextBytes(3 * 1024 * 1024 + 123)

    /** 测试用小窗口，避免每条断言都搬 512 KB */
    private val window = 64 * 1024

    private fun source(reader: VfsReader, budget: Long = Long.MAX_VALUE, timeout: Long = 4_000L) =
        VfsMediaDataSource(
            reader = reader,
            totalSize = payload.size.toLong(),
            windowBytes = window,
            budgetBytes = budget,
            readTimeoutMs = timeout,
        )

    @Test
    fun `同一窗口内的连续小读只抓一次`() {
        val reader = CountingReader(payload)
        val buf = ByteArray(4096)
        source(reader).use { s ->
            assertEquals(4096, s.readAt(0, buf, 0, buf.size))
            assertTrue("首窗内容不对", buf.contentEquals(payload.copyOfRange(0, 4096)))

            // 窗口 64 KB 内的后续小读（解复用器读 moov 就是这种形态）
            assertEquals(4096, s.readAt(1000, buf, 0, buf.size))
            assertTrue(buf.contentEquals(payload.copyOfRange(1000, 1000 + 4096)))
            assertEquals("同一窗口内只该抓一次", 1, reader.fetches)
        }
    }

    @Test
    fun `跨窗口读取内容正确且各抓一次`() {
        val reader = CountingReader(payload)
        val buf = ByteArray(1024)
        source(reader).use { s ->
            s.readAt(window * 2L + 10, buf, 0, buf.size)
            assertTrue(buf.contentEquals(payload.copyOfRange(window * 2 + 10, window * 2 + 10 + 1024)))
            // 跨到下一窗（+5000 仍在同一窗内，这里要的是真正的换窗）
            s.readAt(window * 3L + 10, buf, 0, buf.size)
            assertTrue(buf.contentEquals(payload.copyOfRange(window * 3 + 10, window * 3 + 10 + 1024)))
            assertEquals(2, reader.fetches)
        }
    }

    @Test
    fun `文件尾返回 -1 且最后一窗是短读`() {
        val reader = CountingReader(payload)
        source(reader).use { s ->
            val buf = ByteArray(4096)
            assertEquals(-1, s.readAt(payload.size.toLong(), buf, 0, buf.size))
            assertEquals(10, s.readAt(payload.size - 10L, buf, 0, buf.size))
            assertTrue(buf.copyOfRange(0, 10).contentEquals(payload.copyOfRange(payload.size - 10, payload.size)))
        }
    }

    @Test
    fun `超出读取预算直接失败`() {
        val reader = CountingReader(payload)
        // 预算只够一窗：读到第三窗就该失败（不支持 Range 的源会一直要新窗口）
        source(reader, budget = window.toLong()).use { s ->
            s.readAt(0, ByteArray(16), 0, 16)
            val ex = runCatching { s.readAt(window * 3L, ByteArray(16), 0, 16) }.exceptionOrNull()
            assertTrue("应为 IOException，实际 $ex", ex is IOException)
        }
    }

    @Test
    fun `单次读超时被转成 IOException`() {
        val reader = CountingReader(payload, delayMs = 500)
        source(reader, timeout = 50).use { s ->
            val ex = runCatching { s.readAt(0, ByteArray(16), 0, 16) }.exceptionOrNull()
            assertTrue("应为 IOException，实际 $ex", ex is IOException)
        }
    }

    @Test
    fun `关闭后再读抛出 IOException`() {
        val s = source(CountingReader(payload))
        s.close()
        assertTrue(runCatching { s.readAt(0, ByteArray(16), 0, 16) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `getSize 如实返回文件大小`() {
        val s = source(CountingReader(payload))
        assertEquals(payload.size.toLong(), s.getSize())
        s.close()
    }
}
