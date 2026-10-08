package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteFormatsTest {

    @Test
    fun `sqlite 后缀识别`() {
        listOf("db", "sqlite", "sqlite3", "DB", "SQLite").forEach {
            assertTrue("$it 应为 SQLite", SqliteFormats.isSqlite(it))
        }
        listOf("txt", "db3", "").forEach {
            assertFalse("$it 不应为 SQLite", SqliteFormats.isSqlite(it))
        }
        assertEquals(setOf("db", "sqlite", "sqlite3"), SqliteFormats.EXTENSIONS)
    }

    @Test
    fun `标识符加引号与转义`() {
        assertEquals("\"users\"", SqliteFormats.quoteIdentifier("users"))
        assertEquals("\"my table\"", SqliteFormats.quoteIdentifier("my table"))
        // 内部双引号按 SQL 规则翻倍（防把语句拼坏）
        assertEquals("\"a\"\"b\"", SqliteFormats.quoteIdentifier("a\"b"))
    }
}
