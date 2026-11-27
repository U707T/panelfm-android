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

    @Test fun `preview query bounds values before cursor materialization`() {
        val sql = SqliteFormats.previewQuery("t\"x", listOf("c\"x"), 200)
        assertTrue(sql.contains("CASE typeof(\"c\"\"x\")"))
        assertTrue(sql.contains("WHEN 'blob' THEN 'BLOB'"))
        assertTrue(sql.contains("substr(CAST(\"c\"\"x\" AS TEXT),1,1025)"))
        assertTrue(sql.endsWith("FROM \"t\"\"x\" LIMIT 200"))
        assertTrue(runCatching { SqliteFormats.previewQuery("t", List(65) { "c$it" }, 200) }.isFailure)
        assertTrue(runCatching { SqliteFormats.previewQuery("t", listOf("c"), 201) }.isFailure)
    }
}
