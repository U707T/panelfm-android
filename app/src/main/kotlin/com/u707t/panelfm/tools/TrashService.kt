package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /**
     * 索引读-改-写互斥（第 8 批 🟡1）：
     * `moveToTrash / restore / purge / purgeAll` 都是「读索引 → 改文件/条目 → 写索引」的三段式，
     * 旧实现没有跨段互斥，两个并行操作（例如大文件移入回收站的同时在回收站里清空）
     * 各按旧快照回写，后写的会覆盖先写的 —— 索引丢条目 = 文件在盘上却在回收站列表里消失。
     */
    private val indexMutex = kotlinx.coroutines.sync.Mutex()

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

    private fun save(entries: List<Entry>): Boolean {
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
        // 原子写：先写 index.json.tmp 再改名。
        // 旧实现直接 writeText：进程在写一半时被杀 → index.json 变成半截 JSON，
        // 下次启动 list() 解析失败会返回空表 —— 用户会看到「回收站空了」（文件其实还在盘上）。
        val tmp = File(dir, "index.json.tmp")
        return runCatching {
            tmp.writeText(arr.toString())
            try {
                Files.move(
                    tmp.toPath(), indexFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.onFailure {
            runCatching { tmp.delete() }
        }.getOrDefault(false)
    }

    /** 把本地文件移入回收站；返回成功数量（非本地 URI 会被忽略） */
    suspend fun moveToTrash(uris: List<VfsUri>): Int = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val entries = list().toMutableList()
            val pending = ArrayList<Entry>()      // 本轮新增
            val movedPairs = ArrayList<Pair<File, File>>()   // (源, 回收站内位置) 用于失败回滚

            uris.forEach { uri ->
                if (uri.scheme != "local") return@forEach
                val src = File(localVfs.absolutePath(uri))
                if (!src.exists()) return@forEach
                // 同名多选时用递增后缀，避免同一毫秒内互相覆盖（trashName 曾是纯时间戳 + 原名）
                var trashName = "${System.currentTimeMillis()}-${src.name}"
                var seq = 1
                while (File(dir, trashName).exists() && seq < 1000) {
                    trashName = "${System.currentTimeMillis()}-$seq-${src.name}"
                    seq++
                }
                val dest = File(dir, trashName)
                val moved = moveFile(src, dest)
                if (moved) {
                    pending += Entry(
                        id = trashName,
                        name = src.name,
                        originalPath = localVfs.absolutePath(uri),
                        trashName = trashName,
                        size = runCatching { dest.length() }.getOrDefault(0L),
                        deletedAt = System.currentTimeMillis(),
                        isDirectory = dest.isDirectory,
                    )
                    movedPairs += src to dest
                }
            }
            if (pending.isEmpty()) return@withLock 0

            // 一次性写索引（旧实现每个文件写一次 = O(n²) IO）。
            // 写失败必须把已移动的文件**全部放回原处**：否则文件既不在原目录、也不在索引里，
            // 用户看到的是「删掉了但回收站里没有」= 事实上的静默丢数据。
            if (save(entries + pending)) {
                pending.size
            } else {
                // 回滚用 moveFile：只试 renameTo 时，跨卷（外部存储 → 内部回收站）回滚必失败，
                // 恰好是它最该起作用的场景 —— 必须带复制兜底。
                movedPairs.asReversed().forEach { (src, dest) -> moveFile(dest, src) }
                Logx.e("TrashService", "index save failed, rolled back ${movedPairs.size} item(s)")
                0
            }
        }
    }

    /**
     * 同卷 rename / 跨卷复制+删除的通用移动（第 8 批 🟡1：正反向共用，
     * 旧实现的回滚路径只有 renameTo，跨卷时静默失败）。
     */
    private fun moveFile(from: File, to: File): Boolean =
        runCatching { from.renameTo(to) }.getOrDefault(false) ||
            runCatching {
                from.copyRecursively(to, overwrite = true)
                if (!from.deleteRecursively()) error("删除源失败")
                true
            }.getOrDefault(false)

    suspend fun restore(entry: Entry): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val src = File(dir, entry.trashName)
            if (!src.exists()) return@withLock false
            var dest = File(entry.originalPath)
            dest.parentFile?.mkdirs()
            // 原位置已有同名文件时**不要覆盖**（旧实现会先删掉再还原，等于静默销毁用户数据）；
            // 改为还原成「name (1).ext」保留两者
            if (dest.exists()) dest = uniqueSibling(dest)
            val ok = moveFile(src, dest)
            if (!ok) return@withLock false
            val remaining = list().filterNot { it.id == entry.id }
            if (save(remaining)) return@withLock true
            // 索引落盘失败：把已还原的文件放回回收站，避免出现「文件已离开但索引仍指向旧位置」。
            val rolledBack = moveFile(dest, src)
            if (!rolledBack) Logx.e("TrashService", "restore index failed and rollback failed: ${entry.name}")
            false
        }
    }

    /** 为还原生成一个不冲突的兄弟文件名（name (1).ext） */
    private fun uniqueSibling(file: File): File {
        val parent = file.parentFile ?: return file
        val name = file.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = File(parent, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(parent, "$base (${System.currentTimeMillis()})$ext")
    }

    suspend fun purge(entry: Entry) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            when (removeEntryFile(entry)) {
                Removal.FAILED -> Logx.w("TrashService", "purge failed: ${entry.name}")
                // DELETED / MISSING 都要把条目从索引摘掉：MISSING 是「盘上已经没了」的死条目，
                // 旧实现只在删除成功时更新索引 → 死条目永远留在列表里、还占着容量统计。
                else -> if (!save(list().filterNot { it.id == entry.id })) {
                    Logx.w("TrashService", "purge index update failed: ${entry.name}")
                }
            }
        }
    }

    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            // 只保留「真的删不掉」的（权限 / 占用）；已经不存在的条目直接摘掉
            val remaining = list().filter { removeEntryFile(it) == Removal.FAILED }
            if (!save(remaining)) Logx.w("TrashService", "purgeAll index update failed")
        }
    }

    /**
     * 自动清理：先按时间（[retentionDays] > 0），再按容量（[maxBytes] > 0，最旧优先）。
     *
     * 与手动 purge 共用同一把锁：清理过程中用户新删的条目不会被旧快照覆盖。
     * 返回 (删除数量, 释放字节)。
     *
     * 索引里已经对不上磁盘的「死条目」会顺手摘掉（不计入删除数），避免列表与容量长期虚高。
     */
    suspend fun autoPurge(retentionDays: Int, maxBytes: Long, now: Long = System.currentTimeMillis()): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            if (retentionDays <= 0 && maxBytes <= 0) return@withContext 0 to 0L
            indexMutex.withLock {
                val entries = list()
                if (entries.isEmpty()) return@withLock 0 to 0L
                val keep = ArrayList<Entry>(entries.size)
                var removed = 0
                var dropped = 0
                var freed = 0L
                val cutoff = if (retentionDays > 0) now - retentionDays * 24L * 60 * 60 * 1000 else Long.MIN_VALUE
                // 1) 按时间
                entries.forEach { e ->
                    if (e.deletedAt !in 1..cutoff) {
                        keep += e
                        return@forEach
                    }
                    when (removeEntryFile(e)) {
                        Removal.DELETED -> {
                            removed++
                            freed += e.size.coerceAtLeast(0)
                        }
                        // 盘上已经没了：从索引摘掉（不计数、不计释放空间）
                        Removal.MISSING -> dropped++
                        Removal.FAILED -> keep += e
                    }
                }
                // 2) 按容量（最旧优先）
                if (maxBytes > 0) {
                    var total = keep.sumOf { it.size.coerceAtLeast(0) }
                    if (total > maxBytes) {
                        // 最旧优先删，直到回到上限以内
                        val victims = mutableSetOf<String>()
                        for (e in keep.sortedBy { it.deletedAt }) {
                            if (total <= maxBytes) break
                            val size = e.size.coerceAtLeast(0)
                            when (removeEntryFile(e)) {
                                Removal.DELETED -> {
                                    victims += e.id
                                    total -= size
                                    freed += size
                                    removed++
                                }
                                Removal.MISSING -> {
                                    // 死条目：也要从容量里扣掉，否则它会一直占着名额
                                    victims += e.id
                                    total -= size
                                    dropped++
                                }
                                Removal.FAILED -> Unit
                            }
                        }
                        keep.removeAll { it.id in victims }
                    }
                }
                if ((removed > 0 || dropped > 0) && !save(keep)) {
                    Logx.w("TrashService", "autoPurge index save failed (removed=$removed dropped=$dropped)")
                }
                removed to freed
            }
        }

    /** 删除结果：真的删掉了 / 盘上本来就没有（死条目）/ 删不掉（权限、占用） */
    private enum class Removal { DELETED, MISSING, FAILED }

    /**
     * 删除一个回收站条目对应的磁盘文件。
     *
     * 必须区分「已经不存在」和「删不掉」：前者是索引里的死条目（要摘掉，否则容量统计永远偏高），
     * 后者要保留索引，下次再试。
     */
    private fun removeEntryFile(entry: Entry): Removal = runCatching {
        val file = File(dir, entry.trashName)
        when {
            !file.exists() -> Removal.MISSING
            file.deleteRecursively() -> Removal.DELETED
            else -> Removal.FAILED
        }
    }.getOrDefault(Removal.FAILED)

    fun describe(entry: Entry): String = "${entry.name} · ${Fmt.size(entry.size)}"
}
