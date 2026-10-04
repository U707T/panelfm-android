package com.u707t.panelfm.core.data

import android.content.ContentValues
import android.database.Cursor
import com.u707t.panelfm.core.transfer.ResumeEntry
import com.u707t.panelfm.core.transfer.ResumeStore
import com.u707t.panelfm.core.vfs.VfsUri

/** 断点续传状态持久化：进程被杀也能继续（按 source→dest 路径续传，见 [ResumeStore] 注释）。 */
class ResumeDao(private val db: PanelDb) : ResumeStore {

    override suspend fun save(entry: ResumeEntry) {
        val values = ContentValues().apply {
            put("task_id", entry.taskId)
            put("item_index", entry.itemIndex)
            put("source", entry.source.toString())
            put("dest", entry.dest.toString())
            put("temp_uri", entry.tempUri?.toString())
            put("offset_done", entry.offset)
            put("total_size", entry.total)
            put("validator", entry.validator)
            put("updated_at", entry.updatedAt)
        }
        db.writableDatabase.insertWithOnConflict("resume_entry", null, values, 5 /* CONFLICT_REPLACE */)
    }

    override suspend fun findFor(source: VfsUri, dest: VfsUri): ResumeEntry? {
        db.readableDatabase.query(
            "resume_entry", null, "source = ? AND dest = ?",
            arrayOf(source.toString(), dest.toString()), null, null, "updated_at DESC", "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.toEntry()
        }
    }

    override suspend fun clearFor(source: VfsUri, dest: VfsUri) {
        db.writableDatabase.delete(
            "resume_entry", "source = ? AND dest = ?",
            arrayOf(source.toString(), dest.toString()),
        )
    }

    override suspend fun purgeStale(before: Long) {
        db.writableDatabase.delete("resume_entry", "updated_at < ?", arrayOf(before.toString()))
    }

    private fun Cursor.toEntry(): ResumeEntry = ResumeEntry(
        taskId = getString(getColumnIndexOrThrow("task_id")),
        itemIndex = getInt(getColumnIndexOrThrow("item_index")),
        source = VfsUri.parse(getString(getColumnIndexOrThrow("source"))),
        dest = VfsUri.parse(getString(getColumnIndexOrThrow("dest"))),
        tempUri = getString(getColumnIndexOrThrow("temp_uri"))?.let { VfsUri.parse(it) },
        offset = getLong(getColumnIndexOrThrow("offset_done")),
        total = getLong(getColumnIndexOrThrow("total_size")),
        validator = getString(getColumnIndexOrThrow("validator")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )
}
