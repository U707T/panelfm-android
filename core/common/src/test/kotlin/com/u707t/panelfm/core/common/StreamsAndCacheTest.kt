package com.u707t.panelfm.core.common

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 物化缓存清理：只删超龄的，本次要用的目标永远留。
 *
 * 这条策略原来在 APK / PDF 两条链路各写一份（连 3 天窗口都是硬编码），
 * 改一边忘另一边就会出现「PDF 清了、APK 没清」。
 */
class CacheFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun aged(dir: File, name: String, ageMs: Long): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - ageMs)
        }

    @Test
    fun `删掉超龄文件，保留新文件与目标`() {
        val dir = tmp.newFolder("cache")
        val stale = aged(dir, "stale.apk", 4L * 24 * 3600 * 1000)
        val fresh = aged(dir, "fresh.apk", 1L * 24 * 3600 * 1000)
        // 目标本身也超龄：仍然不能删（正在用）
        val target = aged(dir, "target.apk", 30L * 24 * 3600 * 1000)

        val removed = sweepStaleFiles(dir, keep = target)

        assertEquals(1, removed)
        assertTrue("超龄的应删掉", !stale.exists())
        assertTrue("新鲜的应留下", fresh.exists())
        assertTrue("目标必须留下", target.exists())
    }

    @Test
    fun `keep 为 null 时按超龄全删`() {
        val dir = tmp.newFolder("cache2")
        val stale = aged(dir, "a.apk", 10L * 24 * 3600 * 1000)
        val fresh = aged(dir, "b.apk", 0)
        assertEquals(1, sweepStaleFiles(dir, keep = null))
        assertTrue(!stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `自定义窗口生效`() {
        val dir = tmp.newFolder("cache3")
        val oneHour = aged(dir, "h.apk", 3600_000L)
        // 窗口 1 分钟 → 一小时前的算超龄
        assertEquals(1, sweepStaleFiles(dir, maxAgeMs = 60_000L))
        assertTrue(!oneHour.exists())
    }

    @Test
    fun `目录不存在时不抛异常`() {
        assertEquals(0, sweepStaleFiles(File(tmp.root, "nope")))
    }
}

/**
 * java.io 侧的整段复制：读满就停、0 字节读不空转、进度是累计值。
 */
class StreamsTest {

    private class FakeInput(private val chunks: List<ByteArray>, private val zeroEvery: Int = 0) : InputStream() {
        private var index = 0
        private var reads = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            if (zeroEvery > 0 && reads % zeroEvery == 0) return 0
            if (index >= chunks.size) return -1
            val chunk = chunks[index++]
            chunk.copyInto(b, off, 0, minOf(chunk.size, len))
            return minOf(chunk.size, len)
        }
    }

    private class CollectingOutput : OutputStream() {
        val bytes = java.io.ByteArrayOutputStream()
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
    }

    @Test
    fun `整段复制并回调累计字节数`() = runTest {
        val out = CollectingOutput()
        val progress = mutableListOf<Long>()
        val total = FakeInput(listOf(ByteArray(3) { 1 }, ByteArray(5) { 2 })).copyStreamTo(out, onProgress = { progress += it })
        assertEquals(8, total)
        assertEquals(8, out.bytes.size())
        assertEquals(listOf(3L, 8L), progress)
    }

    @Test
    fun `偶发 0 字节读不算结束`() = runTest {
        val out = CollectingOutput()
        // 每第 2 次读返回 0：不能提前结束，也不能死循环
        val total = FakeInput(listOf(ByteArray(4) { 7 }, ByteArray(4) { 8 }), zeroEvery = 2).copyStreamTo(out)
        assertEquals(8, total)
    }

    @Test
    fun `连续 0 字节读抛 IOException 而不是死循环`() = runTest {
        val out = CollectingOutput()
        val dead = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        val error = runCatching { dead.copyStreamTo(out) }.exceptionOrNull()
        assertTrue("应抛 IOException，实际 $error", error is IOException)
    }

    @Test
    fun `空输入返回 0`() = runTest {
        val out = CollectingOutput()
        assertEquals(0, FakeInput(emptyList()).copyStreamTo(out))
        assertEquals(0, out.bytes.size())
    }
}
