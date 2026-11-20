package com.u707t.panelfm.core.vfs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VFS → 本地输出流的唯一实现（物化落盘）。
 *
 * 三个契约各一条用例：读完才结束、0 字节读不空转、进度是累计值。
 * 这些正是 9 处手写副本最容易漏的（其中「读满缓冲当结束」曾经导致远端文件被静默截断）。
 */
class VfsCopyTest {

    private class FakeReader(
        private val chunks: List<ByteArray>,
        private val zeroEvery: Int = 0,
        private val declaredSize: Long? = null,
    ) : VfsReader {
        private var index = 0
        private var reads = 0
        override val size: Long? get() = declaredSize
        override val supportsSeek: Boolean = false
        override val position: Long get() = 0L
        override suspend fun seek(position: Long) = throw UnsupportedOperationException()
        override suspend fun readFullyAt(position: Long, length: Int): ByteArray =
            throw UnsupportedOperationException()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (zeroEvery > 0 && reads % zeroEvery == 0) return 0
            if (index >= chunks.size) return -1
            val chunk = chunks[index++]
            val n = minOf(chunk.size, length)
            chunk.copyInto(buffer, offset, 0, n)
            return n
        }
        override fun close() = Unit
    }

    private class CollectingOutput : OutputStream() {
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
    }

    @Test
    fun `整段复制到输出流并返回总字节`() = runTest {
        val out = CollectingOutput()
        val progress = mutableListOf<Long>()
        val total = FakeReader(listOf(ByteArray(10) { 1 }, ByteArray(6) { 2 }))
            .copyTo(out, onProgress = { progress += it })
        assertEquals(16, total)
        assertEquals(16, out.bytes.size())
        assertEquals(listOf(10L, 16L), progress)
    }

    @Test
    fun `偶发 0 字节读继续读，不提前结束`() = runTest {
        val out = CollectingOutput()
        val total = FakeReader(listOf(ByteArray(8) { 3 }, ByteArray(8) { 4 }), zeroEvery = 2).copyTo(out)
        assertEquals(16, total)
        assertEquals(16, out.bytes.size())
    }

    @Test
    fun `连续 0 字节读抛 IOException`() = runTest {
        val out = CollectingOutput()
        val dead = object : VfsReader {
            override val size: Long? = null
            override val supportsSeek: Boolean = false
            override val position: Long = 0
            override suspend fun seek(position: Long) = Unit
            override suspend fun readFullyAt(position: Long, length: Int) = ByteArray(0)
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            override fun close() = Unit
        }
        val error = runCatching { dead.copyTo(out) }.exceptionOrNull()
        assertTrue("应抛 IOException，实际 $error", error is IOException)
    }

    @Test
    fun `空文件返回 0 且不写任何字节`() = runTest {
        val out = CollectingOutput()
        assertEquals(0, FakeReader(emptyList(), declaredSize = 0).copyTo(out))
        assertEquals(0, out.bytes.size())
    }

    @Test
    fun `缓冲区大小必须为正`() = runTest {
        val out = CollectingOutput()
        val error = runCatching { FakeReader(emptyList()).copyTo(out, bufferSize = 0) }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $error", error is IllegalArgumentException)
    }
}
