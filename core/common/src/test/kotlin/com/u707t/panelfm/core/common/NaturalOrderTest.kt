package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自然排序：数字段按数值，其余按小写码点。 */
class NaturalOrderTest {

    private fun sorted(vararg names: String): List<String> = names.sortedWith(NaturalOrder)

    @Test
    fun `数字按数值而不是字符串`() {
        assertEquals(listOf("1", "2", "10"), sorted("10", "2", "1"))
        assertEquals(listOf("img2.png", "img10.png"), sorted("img10.png", "img2.png"))
    }

    @Test
    fun `多段数字逐段比较`() {
        assertEquals(
            listOf("v1.2.9", "v1.2.10", "v1.10.1"),
            sorted("v1.10.1", "v1.2.10", "v1.2.9"),
        )
    }

    @Test
    fun `前导零不改变数值序，只做稳定次序`() {
        assertEquals(listOf("a7", "a007", "a8"), sorted("a8", "a007", "a7"))
        // 数值相同：前导零少的在前
        assertTrue(NaturalOrder.compare("a7", "a007") < 0)
        assertTrue(NaturalOrder.compare("a007", "a7") > 0)
    }

    @Test
    fun `大小写不敏感且前缀短的在前`() {
        assertTrue(NaturalOrder.compare("A2", "a10") < 0)
        assertTrue(NaturalOrder.compare("abc", "abcd") < 0)
        assertEquals(0, NaturalOrder.compare("ABC", "abc"))
    }

    @Test
    fun `中文与数字混排`() {
        assertEquals(listOf("文件2", "文件10", "文件a"), sorted("文件10", "文件a", "文件2"))
    }

    @Test
    fun `超长数字不溢出`() {
        val a = "x" + "9".repeat(30)
        val b = "x" + "1" + "0".repeat(30)
        assertTrue(NaturalOrder.compare(a, b) < 0)
    }

    @Test
    fun `空串与前缀`() {
        assertTrue(NaturalOrder.compare("", "a") < 0)
        assertEquals(0, NaturalOrder.compare("", ""))
    }
}
