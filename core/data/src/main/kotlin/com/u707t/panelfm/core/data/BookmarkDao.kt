package com.u707t.panelfm.core.data

import android.content.ContentValues
import android.database.Cursor
import com.u707t.panelfm.core.vfs.VfsUri

data class Bookmark(val id: Long, val connectionId: Long?, val uri: VfsUri, val name: String, val createdAt: Long)

class BookmarkDao(private val db: PanelDb) {

    fun all(): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        db.readableDatabase.query("bookmark", null, null, null, null, null, "created_at DESC").use { c ->
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

    /** 路径历史：记录访问过的目录（用于「最近使用」与书签建议） */
    fun recordVisit(uri: VfsUri, connectionId: Long?) {
        db.writableDatabase.execSQL(
            "INSERT INTO path_history(uri, connection_id, visited_at, hits) VALUES(?,?,?,1) " +
                "ON CONFLICT(uri) DO UPDATE SET visited_at = excluded.visited_at, hits = hits + 1",
            arrayOf<Any?>(uri.toString(), connectionId, System.currentTimeMillis()),
        )
    }

    fun recentPaths(limit: Int = 40): List<VfsUri> {
        val out = ArrayList<VfsUri>()
        db.readableDatabase.query(
            "path_history", arrayOf("uri"), null, null, null, null, "visited_at DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) out.add(VfsUri.parse(c.getString(0)))
        }
        return out
    }

    private fun Cursor.toBookmark(): Bookmark {
        val connIndex = getColumnIndexOrThrow("connection_id")
        return Bookmark(
            id = getLong(getColumnIndexOrThrow("id")),
            connectionId = if (isNull(connIndex)) null else getLong(connIndex),
            uri = VfsUri.parse(getString(getColumnIndexOrThrow("uri"))),
            name = getString(getColumnIndexOrThrow("name")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
        )
    }
}
