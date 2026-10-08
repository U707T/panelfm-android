package com.u707t.panelfm.ui.preview

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.SqliteFormats
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SQLite 数据库只读浏览（表 / 行 / 单元格）：
 *  - **只读**：`SQLiteDatabase.openDatabase(..., OPEN_READONLY)`，不提供 SQL 控制台、不改数据；
 *  - 本地文件直接打开；网络 / 压缩包内的数据库先流式复制到缓存临时文件（上限 [SQLITE_MAX_BYTES]），
 *    退出页面时删除；
 *  - 每个表最多显示 [ROW_LIMIT] 行（文件管理器里浏览用；要看全表请导出到桌面工具）。
 */
private const val SQLITE_MAX_BYTES = 256L * 1024 * 1024
private const val ROW_LIMIT = 200
private const val CELL_WIDTH_DP = 160

private class SqliteHandle(val db: SQLiteDatabase, val tempFile: File?)

private data class SqliteTable(val name: String, val rowCount: Long?)

private data class SqliteRows(val columns: List<String>, val rows: List<List<String>>)

@Composable
fun SqliteScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var handle by remember(item.uri) { mutableStateOf<SqliteHandle?>(null) }
    var tables by remember(item.uri) { mutableStateOf<List<SqliteTable>?>(null) }
    var selectedTable by remember(item.uri) { mutableStateOf<String?>(null) }
    var rows by remember(item.uri) { mutableStateOf<SqliteRows?>(null) }
    var cellDialog by remember(item.uri) { mutableStateOf<Pair<String, String>?>(null) }

    // 退出页面：关连接 + 删临时副本（网络数据库的副本可能几百 MB）
    DisposableEffect(item.uri) {
        onDispose {
            runCatching { handle?.db?.close() }
            runCatching { handle?.tempFile?.delete() }
        }
    }

    LaunchedEffect(item.uri) {
        try {
            val opened = withContext(Dispatchers.IO) { openSqlite(container, item) }
            handle = opened
            val listed = withContext(Dispatchers.IO) {
                listTables(opened.db).map { SqliteTable(it, null) }
            }
            tables = listed
            // 行数单独补：大库 COUNT(*) 可能慢，先让列表出来，再逐张表回填
            listed.forEach { table ->
                val count = withContext(Dispatchers.IO) { countRows(opened.db, table.name) }
                tables = tables?.map { if (it.name == table.name) it.copy(rowCount = count) else it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "打开数据库失败"
        }
    }

    LaunchedEffect(selectedTable) {
        val table = selectedTable ?: return@LaunchedEffect
        val db = handle?.db ?: return@LaunchedEffect
        rows = null
        runCatching {
            withContext(Dispatchers.IO) { readRows(db, table, ROW_LIMIT) }
        }.onSuccess { rows = it }
            .onFailure { error = it.message ?: "读取表失败" }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = item.name,
            subtitle = when {
                selectedTable != null -> "表 ${selectedTable} · 前 $ROW_LIMIT 行 · 只读"
                tables != null -> "SQLite · ${tables!!.size} 张表 · 只读"
                else -> "SQLite · 只读"
            },
            onBack = {
                // 表内返回 → 回到表列表；再返回 → 退出预览
                if (selectedTable != null) {
                    selectedTable = null
                    rows = null
                } else {
                    onBack()
                }
            },
        )
        when {
            error != null -> ErrorState(error!!)
            tables == null -> LoadingState("正在打开数据库…")
            selectedTable == null -> TableList(tables!!) { name -> selectedTable = name }
            rows == null -> LoadingState("正在读取数据…")
            else -> RowTable(rows!!) { column, value -> cellDialog = column to value }
        }
    }

    cellDialog?.let { (column, value) ->
        AlertDialog(
            onDismissRequest = { cellDialog = null },
            title = { Text(column, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cellDialog = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun TableList(tables: List<SqliteTable>, onOpen: (String) -> Unit) {
    if (tables.isEmpty()) {
        LoadingState("数据库里没有表")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tables.size) { index ->
            val table = tables[index]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(table.name) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    table.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    table.rowCount?.let { "$it 行" } ?: "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowTable(data: SqliteRows, onCellClick: (String, String) -> Unit) {
    val hScroll = rememberScrollState()
    Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
        Column(
            Modifier
                .width(CELL_WIDTH_DP.dp * data.columns.size.coerceAtLeast(1))
                .fillMaxHeight(),
        ) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                data.columns.forEach { name -> CellText(name, header = true) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(data.rows.size) { index ->
                    val row = data.rows[index]
                    Row {
                        row.forEachIndexed { i, value ->
                            val column = data.columns.getOrElse(i) { "?" }
                            CellText(value) { onCellClick(column, value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellText(text: String, header: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(CELL_WIDTH_DP.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ 打开与查询（IO 线程）

/** 打开为只读数据库：本地直开；远程 / 包内先流式复制到缓存临时文件。 */
private suspend fun openSqlite(container: AppContainer, item: FileMetadata): SqliteHandle {
    val localPath = if (item.uri.scheme == "local") {
        runCatching { container.localVfs.absolutePath(item.uri) }.getOrNull()
    } else {
        null
    }
    val path: String
    var temp: File? = null
    if (localPath != null && File(localPath).isFile) {
        path = localPath
    } else {
        if (item.size > SQLITE_MAX_BYTES) {
            throw IllegalStateException("数据库超过 ${Fmt.size(SQLITE_MAX_BYTES)}，请先复制到本地再打开。")
        }
        val vfs = container.resolveSession(item.uri) ?: throw IllegalStateException("会话不可用")
        val target = File(container.appDirs.tmpDir, "sqlite-${System.currentTimeMillis()}-${item.name.takeLast(40)}")
        try {
            val reader = vfs.openRead(item.uri)
            try {
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = reader.read(buf, 0, buf.size)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } finally {
                runCatching { reader.close() }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        temp = target
        path = target.absolutePath
    }
    val db = try {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        runCatching { temp?.delete() }
        throw IllegalStateException("不是有效的 SQLite 数据库（或已加密）：${e.message ?: "无法打开"}")
    }
    return SqliteHandle(db, temp)
}

private fun listTables(db: SQLiteDatabase): List<String> =
    db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0) ?: "")
        }
    }

private fun countRows(db: SQLiteDatabase, table: String): Long? = runCatching {
    db.rawQuery("SELECT COUNT(*) FROM ${SqliteFormats.quoteIdentifier(table)}", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }
}.getOrNull()

private fun readRows(db: SQLiteDatabase, table: String, limit: Int): SqliteRows {
    db.rawQuery("SELECT * FROM ${SqliteFormats.quoteIdentifier(table)} LIMIT $limit", null).use { cursor ->
        val columns = cursor.columnNames.toList()
        val rows = ArrayList<List<String>>(minOf(limit, 256))
        while (cursor.moveToNext()) {
            rows.add(columns.indices.map { i -> cursorValue(cursor, i) })
        }
        return SqliteRows(columns, rows)
    }
}

/** 单元格取值：BLOB 不读取内容（避免大字段撑爆内存），只标类型与长度未知。 */
private fun cursorValue(cursor: Cursor, index: Int): String = when (cursor.getType(index)) {
    Cursor.FIELD_TYPE_NULL -> "NULL"
    Cursor.FIELD_TYPE_BLOB -> "BLOB"
    Cursor.FIELD_TYPE_FLOAT -> runCatching { cursor.getString(index) }.getOrNull() ?: ""
    else -> runCatching { cursor.getString(index) }.getOrNull() ?: ""
}
