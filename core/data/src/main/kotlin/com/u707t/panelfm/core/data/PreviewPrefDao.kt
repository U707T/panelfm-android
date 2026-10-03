package com.u707t.panelfm.core.data

import android.content.ContentValues

/**
 * 「打开方式」的默认选择（MT 的 openMethod）：
 * 按扩展名记住用户选的打开方式，点文件时直接用它打开；长按可换、可删除。
 */
class PreviewPrefDao(private val db: PanelDb) {

    fun get(extension: String): String? {
        if (extension.isBlank()) return null
        db.readableDatabase.query(
            "preview_pref", arrayOf("handler_id"), "ext = ?", arrayOf(extension.lowercase()), null, null, null,
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun set(extension: String, handlerId: String) {
        if (extension.isBlank()) return
        db.writableDatabase.insertWithOnConflict(
            "preview_pref", null,
            ContentValues().apply {
                put("ext", extension.lowercase())
                put("handler_id", handlerId)
                put("chosen_at", System.currentTimeMillis())
            },
            5,
        )
    }

    fun clear(extension: String) {
        db.writableDatabase.delete("preview_pref", "ext = ?", arrayOf(extension.lowercase()))
    }

    fun all(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        db.readableDatabase.query("preview_pref", arrayOf("ext", "handler_id"), null, null, null, null, "chosen_at DESC")
            .use { c ->
                while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
            }
        return out
    }
}
