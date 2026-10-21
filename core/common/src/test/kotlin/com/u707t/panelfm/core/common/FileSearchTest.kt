package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchTest {

    @Test
    fun `子串包含忽略大小写`() {
        assertTrue(FileSearch.matches("ReadMe.txt", "readme"))
        assertFalse(FileSearch.matches("notes.md", "readme"))
    }

    @Test
    fun `通配符星号与问号`() {
        assertTrue(FileSearch.matches("app.log", "*.log"))
        assertTrue(FileSearch.matches("a.LOG", "*.log"))
        assertFalse(FileSearch.matches("app.log.bak", "*.log"))
        assertTrue(FileSearch.matches("a1.txt", "a?.txt"))
        // ? = 恰好一个字符：ab.txt 是「a + b + .txt」，命中；abc.txt 多一个字符，不命中
        assertTrue(FileSearch.matches("ab.txt", "a?.txt"))
        assertFalse(FileSearch.matches("abc.txt", "a?.txt"))
        assertTrue(FileSearch.matches("foo-bar.txt", "foo*bar*"))
    }

    @Test
    fun `反斜杠转义后按字面匹配不再当通配`() {
        assertTrue(FileSearch.matches("a*b", "a\\*b"))
        assertFalse(FileSearch.matches("axb", "a\\*b"))
        assertFalse(FileSearch.containsWildcard("a\\*b"))
        // \? 同理：字面问号
        assertTrue(FileSearch.matches("what?", "what\\?"))
        assertFalse(FileSearch.matches("what1", "what\\?"))
        // \\ = 字面反斜杠
        assertTrue(FileSearch.matches("a\\b", "a\\\\b"))
    }

    @Test
    fun `否定与正则`() {
        assertFalse(FileSearch.matches("cache.tmp", "!*.tmp"))
        assertTrue(FileSearch.matches("notes.md", "!*.tmp"))
        assertTrue(FileSearch.matches("app.log", "/.*\\.log"))
        assertFalse(FileSearch.matches("app.txt", "/.*\\.log"))
        assertFalse(FileSearch.matches("app.log", "!/.*\\.log"))
        // 写错的正则不能再「全命中」
        assertFalse(FileSearch.matches("anything", "/("))
        assertTrue(FileSearch.regexError("/("))
        assertFalse(FileSearch.regexError("readme"))
    }

    @Test
    fun `排序：同名优先于前缀优先于包含`() {
        assertEquals(0, FileSearch.rank("Readme", "readme"))
        assertEquals(1, FileSearch.rank("readme.txt", "readme"))
        assertEquals(2, FileSearch.rank("my-readme-old", "readme"))
        assertNull(FileSearch.rank("notes.md", "readme"))
    }

    @Test
    fun `相对路径`() {
        assertEquals("当前目录", FileSearch.relativeParent("/sdcard/Download", "/sdcard/Download"))
        assertEquals("a/b", FileSearch.relativeParent("/sdcard/Download", "/sdcard/Download/a/b"))
        assertEquals("/other", FileSearch.relativeParent("/sdcard/Download", "/other"))
    }

    @Test
    fun `内容搜索跳过已知二进制`() {
        assertTrue(FileSearch.worthContentScan("txt"))
        assertTrue(FileSearch.worthContentScan("kt"))
        assertTrue(FileSearch.worthContentScan(""))
        assertFalse(FileSearch.worthContentScan("png"))
        assertFalse(FileSearch.worthContentScan("zip"))
        assertFalse(FileSearch.worthContentScan("db"))
        assertFalse(FileSearch.worthContentScan("so"))
    }

    // ---------------- 编译复用（性能修复）：Matcher 必须与一次性接口逐字节等价 ----------------

    @Test
    fun `编译后的匹配器与一次性接口等价`() {
        val queries = listOf(
            "",
            "readme",
            "*.log",
            "a?.txt",
            "!*.tmp",
            "/.*\\.log",
            "!/.*\\.log",
            "a\\*b",
            "what\\?",
            "a\\\\b",
            "!",
            "/",
            "!/",
            "*",
        )
        val names = listOf(
            "readme",
            "README",
            "readme.txt",
            "app.log",
            "a.LOG",
            "cache.tmp",
            "a*b",
            "what?",
            "a\\b",
            "abc",
            "",
            "a\n",
        )
        for (q in queries) {
            val matcher = FileSearch.compile(q)
            for (n in names) {
                assertEquals("q=$q name=$n", FileSearch.matches(n, q), matcher.matches(n))
            }
        }
    }

    @Test
    fun `编译后的排序与一次性接口等价`() {
        val queries = listOf("readme", "readme*", "!readme", "/readme", "a\\*b", "")
        val names = listOf("readme", "Readme", "readme.txt", "my-readme", "notes.md", "a*b")
        for (q in queries) {
            val matcher = FileSearch.compile(q)
            for (n in names) {
                assertEquals("q=$q name=$n", FileSearch.rank(n, q), matcher.rank(n))
            }
        }
    }

    @Test
    fun `glob 是整串匹配：末尾换行不会被当成命中`() {
        // Regex("^a.*$").containsMatchIn("a\n") 为 true，而文件名是允许带换行的
        assertFalse(FileSearch.compile("a*").matches("a\n"))
        assertTrue(FileSearch.compile("a*").matches("a"))
    }
}
