package com.u707t.panelfm.core.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「带上限的顺序读」的契约（编辑器 / Office / Markdown / EPUB 共用一份实现）。
 *
 * 这一组用例存在的理由：同一段逻辑以前在本仓有三份手写实现，其中两份把「读满上限」
 * 当成读完 —— 远端 `size = -1` 时用户拿到的是**被截断的文档**，而且没有任何报错。
 * 所以「超过上限必须返回 null」这条边界要逐字节锁住。
 */
class BoundedReadTest {

    /** 模拟 VfsReader：按 [chunk] 分块吐出 [data]，EOF 返回 -1 */
    private class FakeReader(private val data: ByteArray, private val chunk: Int) {
        private var pos = 0
        suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, buf, off, n)
            pos += n
            return n
        }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun read(max: Long, data: ByteArray, chunk: Int = data.size.coerceAtLeast(1)) = runBlocking {
        val r = FakeReader(data, chunk)
        readBounded(max) { b, o, l -> r.read(b, o, l) }
    }

    @Test
    fun `小于上限：原样返回`() {
        val data = bytes(50)
        assertArrayEquals(data, read(64, data))
    }

    @Test
    fun `正好等于上限：返回全部内容（边界不能误判成超限）`() {
        val data = bytes(64)
        assertArrayEquals(data, read(64, data))
    }

    @Test
    fun `超过上限 1 字节：必须返回 null，不能返回截断内容`() {
        assertNull(read(64, bytes(65)))
    }

    @Test
    fun `碎块读也不能漏判超限`() {
        // 每块 1 字节：最容易在「正好读满就收手」的实现里漏判
        assertNull(read(10, bytes(11), chunk = 1))
        assertArrayEquals(bytes(10), read(10, bytes(10), chunk = 1))
    }

    @Test
    fun `空文件返回空数组而不是 null`() {
        assertArrayEquals(ByteArray(0), read(64, ByteArray(0)))
    }

    @Test
    fun `读端一直返回 0 不会变成死循环`() {
        var calls = 0
        val out = runBlocking { readBounded(1024) { _, _, _ -> calls++; 0 } }
        assertArrayEquals(ByteArray(0), out)
        assertTrue("必须靠「连续 0 读」退出，实际调用 $calls 次", calls <= 3)
    }

    @Test
    fun `负上限视为非法，返回 null`() {
        assertNull(runBlocking { readBounded(-1) { _, _, _ -> -1 } })
    }

    @Test
    fun `size 未知不能当成超限`() {
        // -1 = 未知（远端 / 压缩包条目常见）：必须放行，交给边读边判
        assertFalse(definitelyExceeds(-1, 16L * 1024 * 1024))
        assertFalse(definitelyExceeds(0, 1024))
        assertFalse(definitelyExceeds(1024, 1024))
        assertTrue(definitelyExceeds(1025, 1024))
    }
}
