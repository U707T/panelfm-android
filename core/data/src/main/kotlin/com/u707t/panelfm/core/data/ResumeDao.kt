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

    /**
     * 行 → [ResumeEntry]。
     *
     * 注意：本方法必须在**非法行**上返回 null 而不是抛异常 —— 断点表由进程被杀 / 断电
     * 等场景写入，出现半截记录并不罕见；一条坏记录不能把整个续传链路打崩（旧实现直接
     * `VfsUri.parse`，异常会冒到传输任务的 catch-all，表现为「续传永远失败」）。
     */
    private fun Cursor.toEntry(): ResumeEntry? {
        val source = runCatching { VfsUri.parse(getString(getColumnIndexOrThrow("source"))) }.getOrNull()
            ?: return null
        val dest = runCatching { VfsUri.parse(getString(getColumnIndexOrThrow("dest"))) }.getOrNull()
            ?: return null
        return ResumeEntry(
            taskId = getString(getColumnIndexOrThrow("task_id")) ?: return null,
            itemIndex = getInt(getColumnIndexOrThrow("item_index")),
            source = source,
            dest = dest,
            tempUri = getString(getColumnIndexOrThrow("temp_uri"))?.let { runCatching { VfsUri.parse(it) }.getOrNull() },
            offset = getLong(getColumnIndexOrThrow("offset_done")),
            total = getLong(getColumnIndexOrThrow("total_size")),
            validator = getString(getColumnIndexOrThrow("validator")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        )
    }
}
