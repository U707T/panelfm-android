package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private fun query(
        needle: String,
        regex: Boolean = false,
        matchCase: Boolean = true,
        wholeWord: Boolean = false,
    ) = TextSearchQuery(needle, TextSearchOptions(regex, matchCase, wholeWord))

    @Test
    fun `普通文本查找使用循环且不漏掉起点命中`() {
        val q = query("foo")
        assertEquals(TextSearchMatch(2, 5), TextSearch.findNext("x foo foo", q, 1).match)
        assertEquals(TextSearchMatch(0, 3), TextSearch.findNext("foo x", q, 4).match)
        assertEquals(TextSearchMatch(0, 3), TextSearch.findNext("foo x foo", q, 3, backward = true).match)
    }

    @Test
    fun `大小写与全词匹配在查找和替换中一致`() {
        val q = query("cat", matchCase = false, wholeWord = true)
        val found = TextSearch.findAll("Cat scatter CAT cat2", q)
        assertEquals(listOf(TextSearchMatch(0, 3), TextSearchMatch(12, 15)), found.matches)
        val replaced = TextSearch.replaceAll("Cat scatter CAT cat2", q, "dog")
        assertEquals("dog scatter dog cat2", replaced.text)
        assertEquals(2, replaced.count)
    }

    @Test
    fun `正则查找尊重大小写选项`() {
        val q = query("foo", regex = true, matchCase = false)
        assertEquals(2, TextSearch.findAll("FOO foo", q).matches.size)
        val replaced = TextSearch.replaceAll("FOO foo", q, "bar")
        assertEquals("bar bar", replaced.text)
        assertEquals(2, replaced.count)
    }

    @Test
    fun `正则全词过滤不会替换单词内部命中`() {
        val q = query("foo", regex = true, wholeWord = true)
        val result = TextSearch.replaceAll("foobar foo", q, "x")
        assertEquals("foobar x", result.text)
        assertEquals(1, result.count)
    }

    @Test
    fun `正则替换支持分组且普通替换中的美元符号是字面量`() {
        val regex = TextSearch.replaceAll("a12 b34", query("([a-z])(\\d+)", regex = true), "$2-$1")
        assertEquals("12-a 34-b", regex.text)
        val plain = TextSearch.replaceAll("a\$b", query("\$"), "\$\$")
        assertEquals("a\$\$b", plain.text)
    }

    @Test
    fun `替换当前只接受完整命中并返回替换长度`() {
        val q = query("foo")
        val result = TextSearch.replaceOne("foo foo", q, 4, 7, "long")
        assertEquals("foo long", result.text)
        assertEquals(1, result.count)
        assertEquals(4, result.replacementLength)
        assertEquals(0, TextSearch.replaceOne("foo foo", q, 4, 6, "x").count)
    }

    @Test
    fun `非法正则返回 MT 文案而不是抛异常`() {
        val result = TextSearch.findNext("abc", query("[", regex = true), 0)
        assertNull(result.match)
        assertEquals(TextSearch.INVALID_REGEX_MESSAGE, result.error)
        assertEquals(TextSearch.INVALID_REGEX_MESSAGE, TextSearch.replaceAll("abc", query("[", regex = true), "x").error)
    }

    @Test
    fun `空查找内容不产生命中`() {
        val result = TextSearch.findAll("abc", query(""))
        assertTrue(result.matches.isEmpty())
        assertNull(result.error)
    }
}
