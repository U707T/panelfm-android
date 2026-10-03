package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地回收站：删除的本地文件/目录先进 `files/trash/`，可还原或彻底删除。
 * 索引是 `files/trash/index.json`（不依赖数据库，避免破坏性迁移）。
 */
class TrashService(
    private val appDirs: AppDirs,
    private val localVfs: LocalVfs,
) {

    data class Entry(
        val id: String,
        val name: String,
        val originalPath: String,
        val trashName: String,
        val size: Long,
        val deletedAt: Long,
        val isDirectory: Boolean,
    )

    private val dir: File get() = File(appDirs.trashDir).apply { mkdirs() }
    private val indexFile: File get() = File(dir, "index.json")

    @Synchronized
    fun list(): List<Entry> {
        if (!indexFile.exists()) return emptyList()
        val text = runCatching { indexFile.readText() }.getOrDefault("[]")
        val arr = runCatching { JSONArray(text) }.getOrDefault(JSONArray())
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Entry(
                id = o.optString("id"),
                name = o.optString("name"),
                originalPath = o.optString("originalPath"),
                trashName = o.optString("trashName"),
                size = o.optLong("size", 0L),
                deletedAt = o.optLong("deletedAt", 0L),
                isDirectory = o.optBoolean("isDirectory", false),
            )
        }
        return out.sortedByDescending { it.deletedAt }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("originalPath", e.originalPath)
                    put("trashName", e.trashName)
                    put("size", e.size)
                    put("deletedAt", e.deletedAt)
                    put("isDirectory", e.isDirectory)
                }
            )
        }
        runCatching { indexFile.writeText(arr.toString()) }
    }

    /** 把本地文件移入回收站；返回成功数量（非本地 URI 会被忽略） */
    suspend fun moveToTrash(uris: List<VfsUri>): Int = withContext(Dispatchers.IO) {
        var ok = 0
        val entries = list().toMutableList()
        uris.forEach { uri ->
            if (uri.scheme != "local") return@forEach
            val src = File(localVfs.absolutePath(uri))
            if (!src.exists()) return@forEach
            val trashName = "${System.currentTimeMillis()}-${src.name}"
            val dest = File(dir, trashName)
            val moved = runCatching { src.renameTo(dest) }.getOrDefault(false) ||
                runCatching {
                    src.copyRecursively(dest, overwrite = true)
                    src.deleteRecursively()
                    true
                }.getOrDefault(false)
            if (moved) {
                entries += Entry(
                    id = trashName,
                    name = src.name,
                    originalPath = localVfs.absolutePath(uri),
                    trashName = trashName,
                    size = runCatching { dest.length() }.getOrDefault(0L),
                    deletedAt = System.currentTimeMillis(),
                    isDirectory = dest.isDirectory,
                )
                ok++
            }
        }
        save(entries)
        ok
    }

    suspend fun restore(entry: Entry): Boolean = withContext(Dispatchers.IO) {
        val src = File(dir, entry.trashName)
        if (!src.exists()) return@withContext false
        val dest = File(entry.originalPath)
        dest.parentFile?.mkdirs()
        val ok = runCatching {
            if (dest.exists()) dest.deleteRecursively()
            src.renameTo(dest)
        }.getOrDefault(false)
        if (ok) save(list().filterNot { it.id == entry.id })
        ok
    }

    suspend fun purge(entry: Entry) = withContext(Dispatchers.IO) {
        runCatching { File(dir, entry.trashName).deleteRecursively() }
        save(list().filterNot { it.id == entry.id })
    }

    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        list().forEach { runCatching { File(dir, it.trashName).deleteRecursively() } }
        save(emptyList())
    }

    fun describe(entry: Entry): String = "${entry.name} · ${Fmt.size(entry.size)}"
}
