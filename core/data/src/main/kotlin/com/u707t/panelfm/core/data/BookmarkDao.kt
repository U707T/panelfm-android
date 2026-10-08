package com.u707t.panelfm.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.u707t.panelfm.core.vfs.VfsUri

data class Bookmark(val id: Long, val connectionId: Long?, val uri: VfsUri, val name: String, val createdAt: Long)

class BookmarkDao(private val db: PanelDb) {

    fun all(): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        db.readableDatabase.query("bookmark", null, null, null, null, null, "sort_order ASC, created_at DESC").use { c ->
            while (c.moveToNext()) out.add(c.toBookmark())
        }
        return out
    }

    fun add(bookmark: Bookmark): Long = db.writableDatabase.insert(
        "bookmark",
        null,
        ContentValues().apply {
            if (bookmark.connectionId != null) put("connection_id", bookmark.connectionId) else putNull("connection_id")
            put("uri", bookmark.uri.toString())
            put("name", bookmark.name)
            put("created_at", System.currentTimeMillis())
        },
    )

    fun delete(id: Long) {
        db.writableDatabase.delete("bookmark", "id = ?", arrayOf(id.toString()))
    }

    /**
     * 长按后拖动排序（MT `0x7f110140`）：按给定 id 顺序写回 `sort_order`。
     * 未在列表中的书签保持原值（排在其后），避免误伤。
     */
    fun reorder(orderedIds: List<Long>) {
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                dbw.execSQL("UPDATE bookmark SET sort_order = ? WHERE id = ?", arrayOf<Any?>(index, id))
            }
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    /**
     * 路径历史：记录访问过的目录（用于「最近使用」与书签建议）。
     *
     * 不用 UPSERT（`ON CONFLICT ... DO UPDATE` 需要 SQLite ≥ 3.24；API 26–29 的设备只有
     * 3.18–3.22，旧写法在这些设备上直接语法错误——调用方虽有 runCatching 兜底不崩，
     * 但「最近使用」会永远为空。第 10 批 🟡1：改为 update → 不存在才 insert。
     */
    fun recordVisit(uri: VfsUri, connectionId: Long?) {
        val uriStr = uri.toString()
        val now = System.currentTimeMillis()
        val dbw = db.writableDatabase
        val updated = dbw.update(
            "path_history",
            ContentValues().apply {
                put("visited_at", now)
                put("connection_id", connectionId)
            },
            "uri = ?", arrayOf(uriStr),
        )
        if (updated == 0) {
            dbw.insertWithOnConflict(
                "path_history", null,
                ContentValues().apply {
                    put("uri", uriStr)
                    put("connection_id", connectionId)
                    put("visited_at", now)
                    put("hits", 1)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            dbw.execSQL("UPDATE path_history SET hits = hits + 1 WHERE uri = ?", arrayOf<Any?>(uriStr))
        }
    }

    fun recentPaths(limit: Int = 40): List<VfsUri> {
        val out = ArrayList<VfsUri>()
        db.readableDatabase.query(
            "path_history", arrayOf("uri"), null, null, null, null, "visited_at DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                // 历史表可能残留旧版本 / 手改的非法 URI；一条坏数据不能让整个「最近使用」崩掉
                val raw = c.getString(0) ?: continue
                runCatching { VfsUri.parse(raw) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun Cursor.toBookmark(): Bookmark {
        val connIndex = getColumnIndexOrThrow("connection_id")
        return Bookmark(
            id = getLong(getColumnIndexOrThrow("id")),
            connectionId = if (isNull(connIndex)) null else getLong(connIndex),
            // 同上：非法 URI 退化为「根目录」而不是抛异常（书签列表必须能打开）
            uri = runCatching { VfsUri.parse(getString(getColumnIndexOrThrow("uri"))) }
                .getOrDefault(VfsUri.of("local", "emulated", "/")),
            name = getString(getColumnIndexOrThrow("name")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
        )
    }
}
