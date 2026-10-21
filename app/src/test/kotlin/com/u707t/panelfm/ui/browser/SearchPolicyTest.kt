package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索预算 / 内容扫描判定 / 大小解析的回归锁（v2.0.24 搜索修复）。
 *
 * 三条都是「机制看着齐、接线却失效」的坑：
 *  - 300 条弹「继续搜索」，但 limit 还是 300 → 按钮永远没用；
 *  - size == -1（未知）被当成「太大」→ 压缩包 / 部分 FTP 文件永远搜不到；
 *  - 非法大小输入返回 -1，与「不限制」无法区分。
 */
class SearchPolicyTest {

    @Test
    fun `确认继续后预算真的变大`() {
        val budget = SearchBudget(300, 300)
        assertTrue(budget.reached(300))
        budget.continueAfterAsk(300)
        assertEquals(600, budget.limit)
        assertFalse(budget.reached(300))
        assertFalse(budget.capped)
    }

    @Test
    fun `预算只增不减`() {
        val budget = SearchBudget(300, 300)
        budget.continueAfterAsk(50)
        assertEquals(600, budget.limit)
    }

    @Test
    fun `步长非法时退回默认步长`() {
        val budget = SearchBudget(100, 0)
        budget.continueAfterAsk(100)
        assertEquals(400, budget.limit)
    }

    @Test
    fun `未知大小仍值得内容扫描`() {
        // size = -1 是「未知」，不是「超限」：压缩包 gz 条目 / 部分 FTP 列表都是 -1
        assertTrue(contentScanWorthReading(-1, "txt"))
        assertTrue(contentScanWorthReading(-1, "kt"))
        // 已知超过 2MB 才放弃
        assertFalse(contentScanWorthReading(CONTENT_SCAN_MAX_FILE + 1, "txt"))
        assertTrue(contentScanWorthReading(CONTENT_SCAN_MAX_FILE, "txt"))
        // 已知二进制照旧不读
        assertFalse(contentScanWorthReading(-1, "png"))
    }

    @Test
    fun `大小解析：空串是不限制`() {
        assertEquals(-1L, parseSizeText(""))
        assertEquals(-1L, parseSizeText("   "))
    }

    @Test
    fun `大小解析：K M G 与 B 后缀`() {
        assertEquals(1024L, parseSizeText("1K"))
        assertEquals(10L * 1024, parseSizeText("10KB"))
        assertEquals(1536L * 1024, parseSizeText("1.5M"))
        assertEquals(1536L * 1024, parseSizeText("1.5MB"))
        assertEquals(2L * 1024 * 1024 * 1024, parseSizeText("2G"))
        assertEquals(4096L, parseSizeText("4096"))
    }

    @Test
    fun `大小解析：非法输入返回 null 而不是不限制`() {
        assertNull(parseSizeText("abc"))
        assertNull(parseSizeText("-5"))
        assertNull(parseSizeText("B"))
        assertNull(parseSizeText("10XB"))
        assertNull(parseSizeText("1.2.3M"))
    }
}
