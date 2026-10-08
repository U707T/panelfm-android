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
        assertFalse(FileSearch.matches("ab.txt", "a?.txt"))
        assertTrue(FileSearch.matches("foo-bar.txt", "foo*bar*"))
    }

    @Test
    fun `反斜杠转义后按字面匹配不再当通配`() {
        assertTrue(FileSearch.matches("a*b", "a\\*b"))
        assertFalse(FileSearch.matches("axb", "a\\*b"))
        assertFalse(FileSearch.containsWildcard("a\\*b"))
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
}
