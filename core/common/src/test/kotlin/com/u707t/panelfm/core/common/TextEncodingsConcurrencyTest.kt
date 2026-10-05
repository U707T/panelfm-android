package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 编码识别的**并发**回归（B01）。
 *
 * 背景：`TextEncodings` 曾把 `CharsetDecoder` 缓存在单例里复用，而 `CharsetDecoder`
 * 明确不是线程安全（`reset()` + `decode()` 会改内部状态）。预览 / 搜索 / 缩略图 / 传输
 * 会并发调用 `decode()`，症状是**随机**把合法 UTF-8 判成非 UTF-8（误回退 GBK → 乱码）
 * 或直接抛 `IllegalStateException`。
 *
 * 这里用多线程同时解码，断言结果稳定且不抛异常；同时保证单线程语义不变。
 */
class TextEncodingsConcurrencyTest {

    private val utf8Samples = listOf(
        "你好，世界 UTF-8 中文".toByteArray(Charsets.UTF_8),
        "emoji 🚀 混排 abc".toByteArray(Charsets.UTF_8),
        "plain ascii only".toByteArray(Charsets.UTF_8),
        "a".repeat(4096).toByteArray(Charsets.UTF_8),
        "中文".repeat(2000).toByteArray(Charsets.UTF_8),
    )

    private val gbkSamples = listOf(
        "中文 GBK 编码".toByteArray(java.nio.charset.Charset.forName("GBK")),
    )

    @Test
    fun `并发解码 UTF-8 结果稳定且不抛异常`() {
        val threads = 8
        val rounds = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger(0)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            val futures = (0 until threads).map {
                pool.submit {
                    start.await()
                    repeat(rounds) { r ->
                        val sample = utf8Samples[r % utf8Samples.size]
                        try {
                            val decoded = TextEncodings.decode(sample)
                            if (decoded.charset != "UTF-8") failures.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertTrue("并发解码抛异常：$errors", errors.isEmpty())
        assertEquals("并发下把合法 UTF-8 判成了其它编码", 0, failures.get())
    }

    @Test
    fun `并发解码 GBK 不被误判为 UTF-8`() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val bad = AtomicInteger(0)
        try {
            val futures = (0 until 4).map {
                pool.submit {
                    start.await()
                    repeat(200) {
                        val sample = gbkSamples[0]
                        if (TextEncodings.decode(sample).charset == "UTF-8") bad.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("GBK 被并发误判为 UTF-8", 0, bad.get())
    }

    @Test
    fun `单线程语义保持不变`() {
        assertEquals("UTF-8", TextEncodings.decode(utf8Samples[0]).charset)
        assertEquals("你好，世界 UTF-8 中文", TextEncodings.decode(utf8Samples[0]).text)
        // BOM 识别
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("UTF-8 (BOM)", TextEncodings.decode(bom).charset)
        // 非 UTF-8 的二进制不会崩溃
        val binary = ByteArray(256) { it.toByte() }
        assertFalse(TextEncodings.isValidUtf8(binary))
    }
}
