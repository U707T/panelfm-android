package com.u707t.panelfm.core.common

/**
 * SQLite 数据库文件（只读浏览）支持矩阵。
 *
 * 用 Android 自带的 `SQLiteDatabase` 以 **只读** 方式打开：表 / 列 / 前 N 行；
 * 不做编辑、不执行用户 SQL（避免在文件管理器里开一个 SQL 控制台的口子）。
 */
object SqliteFormats {

    const val PREVIEW_CELL_CHARS = 1024
    const val PREVIEW_MAX_COLUMNS = 64
    const val PREVIEW_TABLE_CHARS = 256 * 1024

    /** Truncate in SQLite, before raw TEXT / BLOB can enter CursorWindow or JVM strings. */
    fun previewQuery(table: String, columns: List<String>, limit: Int): String {
        require(columns.isNotEmpty() && columns.size <= PREVIEW_MAX_COLUMNS)
        require(limit in 1..200)
        val projection = columns.joinToString(",") { name ->
            val q = quoteIdentifier(name)
            "CASE typeof($q) WHEN 'blob' THEN 'BLOB' WHEN 'null' THEN 'NULL' " +
                "ELSE substr(CAST($q AS TEXT),1,${PREVIEW_CELL_CHARS + 1}) END"
        }
        return "SELECT $projection FROM ${quoteIdentifier(table)} LIMIT $limit"
    }

    val EXTENSIONS = setOf("db", "sqlite", "sqlite3")

    fun isSqlite(extension: String): Boolean = extension.lowercase() in EXTENSIONS

    /**
     * SQL 标识符加引号：`name` → `"name"`，内部的双引号按 SQL 规则翻倍。
     *
     * 表名来自数据库文件本身（不可信输入），拼进 `SELECT … FROM …` 前必须走这里，
     * 否则一个带 `"` 或空格的表名就能把语句拼坏（只读也会报一堆看不懂的错）。
     */
    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
