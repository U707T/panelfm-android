package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/**
 * ZIP 内部写操作（对齐 MT 的「压缩文件相关」）：
 *  - 添加新文件到 ZIP（和复制文件一样：从另一窗格选文件 → 加进当前 ZIP）
 *  - 删除 ZIP 内文件
 *  - 重命名（完整路径，可改父目录 = 移动）
 *
 * 实现方式：整包重写到临时文件 → 回传到压缩包本体（本地直接改名，网络走流式写 + commit）。
 * 大 ZIP 会 O(size) 重写，UI 上会显示进度提示。
 */
class ZipEditor(
    private val archive: ArchiveVfs,
    private val locator: VfsLocator,
) {

    suspend fun rewrite(
        remove: Set<String> = emptySet(),
        additions: List<Pair<String, VfsUri>> = emptyList(),
        rename: Map<String, String> = emptyMap(),
    ) {
        val host = archive.host
        val hostVfs = locator.find(host) ?: throw VfsException.Unsupported("压缩包所在位置不可写")
        if (!hostVfs.capabilities.writable) throw VfsException.Unsupported("该位置不可写，无法修改压缩包")

        val tmp = File.createTempFile("panelfm-zip-", ".zip")
        try {
            withContext(Dispatchers.IO) {
                ZipArchiveOutputStream(tmp).use { out ->
                    out.setEncoding("UTF-8")
                    val src = ZipFile.builder().setFile(archive.localFile).get()
                    src.use { zf ->
                        zf.entries.asSequence().forEach { entry ->
                            val name = entry.name
                            if (matchesPrefix(name, remove)) return@forEach
                            val target = safeEntryName(applyRename(name, rename))
                            if (entry.isDirectory) {
                                out.putArchiveEntry(ZipArchiveEntry("${target.trimEnd('/')}/").apply { time = entry.time })
                                out.closeArchiveEntry()
                            } else {
                                val newEntry = ZipArchiveEntry(target).apply { time = entry.time }
                                out.putArchiveEntry(newEntry)
                                zf.getInputStream(entry).use { input ->
                                    val buf = ByteArray(128 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                    }
                                }
                                out.closeArchiveEntry()
                            }
                        }
                    }
                    // 追加新文件
                    additions.forEach { (entryName, sourceUri) ->
                        val vfs = locator.find(sourceUri) ?: return@forEach
                        addEntry(out, vfs, sourceUri, safeEntryName(entryName))
                    }
                    out.finish()
                }
            }
            // 回传：先写临时副本再原子替换（本地直接改名，网络走 VFS 写）
            withContext(Dispatchers.IO) {
                val writer = hostVfs.openWrite(host, size = tmp.length(), offset = 0L)
                try {
                    tmp.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            writer.write(buf, 0, n)
                        }
                    }
                    writer.commit()
                } catch (e: Exception) {
                    runCatching { writer.abort() }
                    throw e
                }
            }
            Logx.i("ZipEditor", "rewrote ${archive.host.name}: -${remove.size} +${additions.size} ~${rename.size}")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 删除要能作用到「目录及其所有子项」：条目名 == 键 或 以 键+"/" 开头 */
    private fun matchesPrefix(name: String, keys: Set<String>): Boolean {
        if (name in keys) return true
        val normalized = name.trimEnd('/')
        return keys.any { key ->
            val k = key.trimEnd('/')
            k.isNotEmpty() && (normalized == k || normalized.startsWith("$k/"))
        }
    }

    /**
     * 重命名同样作用于整个子树（压缩包内改父目录 = 移动目录）：
     * `rename["olddir"] = "newdir"` 会把 `olddir/` 及其下所有条目一起改名。
     */
    private fun applyRename(name: String, rename: Map<String, String>): String {
        val normalized = name.trimEnd('/')
        val trailingSlash = name.endsWith("/")
        for ((from, to) in rename) {
            val f = from.trimEnd('/')
            if (f.isEmpty()) continue
            val t = to.trimEnd('/')
            val renamed = when {
                normalized == f -> t
                normalized.startsWith("$f/") -> t + "/" + normalized.removePrefix("$f/")
                else -> null
            } ?: continue
            return if (trailingSlash) "$renamed/" else renamed
        }
        return name
    }

    /** 任何写入 ZIP 的条目名都必须是相对安全路径，不能携带 `..` 或绝对根。 */
    private fun safeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.contains('\u0000')) {
            throw VfsException.IllegalArgument("压缩包条目路径非法：$raw")
        }
        val parts = normalized.split('/')
        if (parts.any { it == ".." }) {
            throw VfsException.IllegalArgument("压缩包条目不能包含上级路径：$raw")
        }
        val clean = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (clean.isEmpty()) throw VfsException.IllegalArgument("压缩包条目路径为空")
        return if (normalized.endsWith('/')) "$clean/" else clean
    }

    private suspend fun addEntry(
        out: ZipArchiveOutputStream,
        vfs: VirtualFileSystem,
        source: VfsUri,
        entryName: String,
    ) {
        val safeName = safeEntryName(entryName)
        val meta = vfs.stat(source)
        if (meta.isDirectory) {
            out.putArchiveEntry(ZipArchiveEntry("${safeName.trimEnd('/')}/").apply { time = System.currentTimeMillis() })
            out.closeArchiveEntry()
            vfs.list(source).forEach { child ->
                addEntry(out, vfs, child.uri, "$safeName/${child.name}")
            }
            return
        }
        val entry = ZipArchiveEntry(safeName).apply { if (meta.size > 0) size = meta.size }
        out.putArchiveEntry(entry)
        val reader = vfs.openRead(source)
        try {
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { reader.close() }
            out.closeArchiveEntry()
        }
    }
}
