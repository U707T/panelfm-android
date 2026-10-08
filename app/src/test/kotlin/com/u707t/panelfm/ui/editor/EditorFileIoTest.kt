package com.u707t.panelfm.ui.editor

import com.u707t.panelfm.core.common.TextEncodings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页窗口的相邻页不变式（2026-10-08 重审 §3 · 🟡3 回归）。
 *
 * 旧实现页尾不裁剪：`displayEnd = start + PAGE_SIZE + SLACK`，而下一页从
 * `start + PAGE_SIZE` 之后第一个换行开始 → 相邻页重复展示最多一个 SLACK。
 * 本测试用「逐页拼回原文」直接锁住「不重叠、不漏内容」。
 */
class EditorFileIoTest {

    private fun readPage(bytes: ByteArray, page: Int, pageSize: Long, slack: Long): ByteArray {
        val start = page.toLong() * pageSize
        if (start >= bytes.size) return ByteArray(0)
        val end = minOf(start + pageSize + slack, bytes.size.toLong()).toInt()
        return bytes.copyOfRange(start.toInt(), end)
    }

    /** 逐页取窗口：断言相邻页严格相接（displayEnd(N) == displayStart(N+1)）且拼回后与原文一致。 */
    private fun assertPartition(
        text: String,
        bytes: ByteArray,
        charset: String,
        pageSize: Long = 128,
        slack: Long = 32,
    ) {
        val pages = ((bytes.size + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
        val rebuilt = StringBuilder()
        var prevEnd = -1
        for (page in 0 until pages) {
            val buf = readPage(bytes, page, pageSize, slack)
            val w = pageWindow(buf, buf.size, page, bytes.size.toLong(), charset, pageSize, slack)
            // 末页允许为空段：上一页的行对齐边界正好落在文件尾时，[skip, tail) 就是空的
            assertTrue("窗口不应为负（tail >= skip）", w.tail >= w.skip)
            val absStart = (page * pageSize + w.skip).toInt()
            if (prevEnd >= 0) {
                assertEquals("页 $page 应与上一页严格相接", prevEnd, absStart)
            }
            rebuilt.append(TextEncodings.decodeWith(charset, buf.copyOfRange(w.skip, w.tail)))
            prevEnd = (page * pageSize + w.tail).toInt()
        }
        assertEquals("最后一页应覆盖到文件末尾", bytes.size, prevEnd)
        assertEquals("逐页拼回应等于原文", text, rebuilt.toString())
    }

    @Test
    fun `普通多行文本：页与页相接且不漏内容`() {
        val text = buildString {
            repeat(400) { i -> append("line ").append(i).append(' ').append("x".repeat(i % 17)).append('\n') }
        }
        assertPartition(text, text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    @Test
    fun `CRLF 文本同样相接`() {
        val text = buildString {
            repeat(300) { i -> append("行 ").append(i).append("\r\n") }
        }
        assertPartition(text, text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    @Test
    fun `单行超过页+冗余：退回名义边界分割`() {
        val text = "a".repeat(300) + "\n" + "b".repeat(300) + "\n"
        assertPartition(text, text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    @Test
    fun `UTF-16LE：按 2 字节码元对齐，不产生错位`() {
        val text = buildString {
            repeat(200) { i -> append("行").append(i).append("数据\n") }
        }
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertPartition(text, bytes, "UTF-16LE")
    }

    @Test
    fun `UTF-32LE：按 4 字节码元对齐，不产生错位`() {
        val text = buildString {
            repeat(200) { i -> append("行").append(i).append("数据\n") }
        }
        assertPartition(text, TextEncodings.encode(text, "UTF-32LE").bytes, "UTF-32LE")
    }

    @Test
    fun `UTF-32BE：按 4 字节码元对齐，不产生错位`() {
        val text = buildString {
            repeat(200) { i -> append("行").append(i).append("数据\n") }
        }
        assertPartition(text, TextEncodings.encode(text, "UTF-32BE").bytes, "UTF-32BE")
    }
}
