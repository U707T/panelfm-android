package com.u707t.panelfm.core.vfs.local

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.sortFileItems
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.partNameOf
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * 本地文件系统（含 /storage/emulated/0、外置卡、/ 与压缩包挂载点）。
 *
 * 关键点：
 *  - 写操作先落到同目录隐藏临时文件（`.name.panelfm.part`），commit 时原子改名，避免半成品可见；
 *  - 权限/属主通过 android.system.Os.stat 读取，chmod 通过 Os.chmod；
 *  - 支持按偏移读写（断点续传）。
 */
class LocalVfs(private val env: VfsEnv) : VirtualFileSystem {

    override val id: String = "local"
    override val scheme: String = "local"
    override val label: String = "本地存储"

    override val capabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = true,
        rangeRead = true,
        rangeWrite = true,
        resumable = Resumability.RANGE,
        permissions = true,
        space = true,
        symlinks = true,
        recursiveDelete = true,
        touch = true,
        setModified = true,
        streamingList = false,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    override suspend fun connect() {
        _state.value = VfsState.Ready
    }

    // ------------------------------------------------------------------ 路径映射

    fun toFile(uri: VfsUri): File = File(absolutePath(uri))

    fun absolutePath(uri: VfsUri): String = when (uri.authority) {
        LocalVolumes.AUTHORITY_EMULATED -> LocalVolumes.emulatedPath + uri.path.trimEnd('/')
        LocalVolumes.AUTHORITY_APP -> env.appDirs.filesDir + uri.path.trimEnd('/')
        LocalVolumes.AUTHORITY_ROOT -> uri.path.trimEnd('/').ifEmpty { "/" }
        else -> {
            // vol:/storage/XXXX-XXXX 形式
            if (uri.authority.startsWith("vol:")) {
                "/" + uri.authority.removePrefix("vol:").replace('_', '/') + uri.path.trimEnd('/')
            } else {
                uri.path.trimEnd('/').ifEmpty { "/" }
            }
        }
    }

    fun uriOf(authority: String, absolutePath: String): VfsUri {
        val path = when (authority) {
            LocalVolumes.AUTHORITY_EMULATED -> absolutePath.removePrefix(LocalVolumes.emulatedPath).ifEmpty { "/" }
            LocalVolumes.AUTHORITY_APP -> absolutePath.removePrefix(env.appDirs.filesDir).ifEmpty { "/" }
            else -> absolutePath
        }
        return VfsUri.of("local", authority, path)
    }

    // ------------------------------------------------------------------ 列表 / 元数据

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> =
        withContext(env.dispatchers.io) {
            val dir = toFile(uri)
            if (!dir.exists()) throw VfsException.NotFound(uri)
            if (!dir.isDirectory) throw VfsException.ProtocolError("不是目录：${uri.path}")
            val children = dir.listFiles()
                ?: throw VfsException.Permission("无法读取目录：${uri.displayPath}（可能需要「所有文件访问」授权）")
            val filter = options.filter
            val items = children.asSequence()
                .filter { options.showHidden || !it.name.startsWith(".") }
                .filter { filter.isNullOrBlank() || it.name.contains(filter, ignoreCase = true) }
                .map { meta(uri, it) }
                .toList()
            sortFileItems(items, options.sort)
        }

    override suspend fun stat(uri: VfsUri): FileMetadata = withContext(env.dispatchers.io) {
        val f = toFile(uri)
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) throw VfsException.NotFound(uri)
        // 注意：这里 uri 已经是**文件自身**的 URI，不能再按「父目录 + 文件名」拼一次
        // （旧实现 meta() 内部统一 child(file.name)，导致 stat 得到 x.jpg/x.jpg → ENOTDIR）
        metaOf(uri, f)
    }

    /** 列表用：父目录 URI + 子项 File → 子项元数据 */
    private fun meta(parentUri: VfsUri, file: File): FileMetadata = metaOf(parentUri.child(file.name), file)

    /** 元数据（uri 必须是该文件/目录**自身**的 URI） */
    private fun metaOf(uri: VfsUri, file: File): FileMetadata {
        val attrs = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        // lstat 才能识别符号链接（Os.stat 会跟随链接）
        val linkAttrs = runCatching { Os.lstat(file.absolutePath) }.getOrNull()
        val isDir = attrs?.let { OsConstants.S_ISDIR(it.st_mode) } ?: file.isDirectory
        val isLink = linkAttrs?.let { OsConstants.S_ISLNK(it.st_mode) }
            ?: runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return FileMetadata(
            uri = uri,
            name = file.name,
            isDirectory = isDir,
            isSymlink = isLink,
            size = if (isDir) -1L else runCatching { file.length() }.getOrDefault(-1L),
            lastModified = runCatching { file.lastModified() }.getOrDefault(-1L),
            mimeType = if (isDir) null else MimeTypes.of(ext),
            // 0xFFF：9 位权限 + setuid/setgid/sticky（属性面板显示 drwxrws---(2770) 需要特殊位）
            permissions = attrs?.let { it.st_mode and 0xFFF },
            owner = attrs?.let { it.st_uid.toString() },
            group = attrs?.let { it.st_gid.toString() },
            symlinkTarget = if (isLink) runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrNull() else null,
        )
    }

    // ------------------------------------------------------------------ 写操作

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = withContext(env.dispatchers.io) {
        val dir = toFile(uri)
        if (dir.exists()) return@withContext
        val ok = if (parents) dir.mkdirs() else dir.mkdir()
        if (!ok) throw VfsException.Io("创建目录失败：${uri.displayPath}")
    }

    override suspend fun touch(uri: VfsUri) = withContext(env.dispatchers.io) {
        val f = toFile(uri)
        if (!f.exists() && !f.createNewFile()) throw VfsException.Io("创建文件失败：${uri.displayPath}")
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = withContext(env.dispatchers.io) {
        var done = 0L
        for (u in uris) {
            val f = toFile(u)
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) continue
            if (!deleteRecursively(f)) throw VfsException.Permission("删除失败：${u.displayPath}")
            done++
            onProgress?.onProgress(done, uris.size.toLong())
        }
    }

    private fun deleteRecursively(f: File): Boolean {
        // 符号链接：只删链接本身，绝不递归进链接指向的目录
        // （旧实现用 File.isDirectory 会跟随链接，可能把链接目标里的文件删光——数据丢失）
        val isLink = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        if (!isLink && f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        }
        return f.delete()
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = withContext(env.dispatchers.io) {
        val src = toFile(from)
        val dst = toFile(to)
        if (src.absolutePath == dst.absolutePath) return@withContext true
        if (dst.exists()) throw VfsException.Conflict(to)
        dst.parentFile?.mkdirs()
        // 同卷直接改名（秒级）
        if (src.renameTo(dst)) return@withContext true
        // 跨卷：Files.move 也会抛（EXDEV）→ 复制 + 删除源（此前注释说「退化」但实际不可用，重命名直接失败）
        runCatching {
            if (src.isDirectory) {
                java.nio.file.Files.walk(src.toPath()).use { stream ->
                    stream.forEach { p ->
                        val target = dst.toPath().resolve(src.toPath().relativize(p))
                        if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(target)
                        else java.nio.file.Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } else {
                java.nio.file.Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            deleteRecursively(src)
            true
        }.getOrDefault(false)
    }

    override suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean = withContext(env.dispatchers.io) {
        val src = toFile(from)
        val dst = toFile(to)
        if (dst.exists()) throw VfsException.Conflict(to)
        runCatching {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) {
                // 目录：递归复制（本地秒级，无需走「读流→写流」慢路径）
                java.nio.file.Files.walk(src.toPath()).use { stream ->
                    stream.forEach { p ->
                        val target = dst.toPath().resolve(src.toPath().relativize(p))
                        if (java.nio.file.Files.isDirectory(p)) {
                            java.nio.file.Files.createDirectories(target)
                        } else {
                            java.nio.file.Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            } else {
                java.nio.file.Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrDefault(false)
    }

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader = LocalReader(toFile(uri), offset)

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        val target = toFile(uri)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, partNameOf(target.name))
        return LocalWriter(target, part, offset)
    }

    override suspend fun space(uri: VfsUri): SpaceInfo? = withContext(env.dispatchers.io) {
        val f = toFile(uri)
        val root = if (f.isDirectory) f else f.parentFile ?: f
        runCatching {
            val stat = StatFs(root.absolutePath)
            SpaceInfo(total = stat.totalBytes, free = stat.availableBytes)
        }.getOrNull()
    }

    override suspend fun setPermissions(uri: VfsUri, mode: Int) = withContext(env.dispatchers.io) {
        runCatching { Os.chmod(absolutePath(uri), mode) }
            .getOrElse { throw VfsException.Io("chmod 失败：${it.message}") }
    }

    override suspend fun setModified(uri: VfsUri, epochMillis: Long): Unit = withContext(env.dispatchers.io) {
        runCatching {
            java.nio.file.Files.setLastModifiedTime(
                toFile(uri).toPath(),
                java.nio.file.attribute.FileTime.fromMillis(epochMillis),
            )
        }.getOrElse { throw VfsException.Io("设置修改时间失败：${it.message}") }
        Unit
    }

    override fun close() {
        _state.value = VfsState.Idle
    }

    // ------------------------------------------------------------------ 流实现

    private inner class LocalReader(private val file: File, startOffset: Long) : VfsReader {
        private val raf: RandomAccessFile = try {
            RandomAccessFile(file, "r")
        } catch (e: Exception) {
            // 面向用户的错误文案：不要暴露原始异常（ENOTDIR / EACCES 之类对用户无意义），
            // 按 errno 给出可执行的下一步；调试细节交给 Logx。
            val reason = when {
                !file.exists() -> "文件不存在（可能已被移动或删除）"
                file.isDirectory -> "这是一个文件夹，不是文件"
                e is java.io.FileNotFoundException && (e.message?.contains("EACCES") == true) ->
                    "没有读取权限（可能需要「所有文件访问」授权）"
                else -> "无法打开文件（${e.message?.substringBefore(':')?.trim() ?: "读取失败"}）"
            }
            Logx.w("LocalVfs", "openRead failed: ${file.absolutePath}", e)
            throw VfsException.Permission("$reason：${file.name}")
        }

        private var pos: Long = startOffset

        override val size: Long = runCatching { raf.length() }.getOrDefault(-1L)
        override val supportsSeek: Boolean get() = true
        override val position: Long get() = pos
        override val lastModified: Long get() = file.lastModified()

        init {
            runCatching { raf.seek(startOffset) }
        }

        override suspend fun seek(position: Long) {
            pos = position.coerceAtLeast(0)
            raf.seek(pos)
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = raf.read(buffer, offset, length)
            if (n > 0) pos += n
            return n
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray = withContext(env.dispatchers.io) {
            val data = ByteArray(length)
            raf.seek(position)
            var read = 0
            while (read < length) {
                val n = raf.read(data, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read == length) data else data.copyOf(read)
        }

        override fun close() {
            runCatching { raf.close() }
        }
    }

    private inner class LocalWriter(
        private val target: File,
        private val part: File,
        startOffset: Long,
    ) : VfsWriter {

        private val raf: RandomAccessFile = try {
            RandomAccessFile(part, "rw")
        } catch (e: Exception) {
            throw VfsException.Permission("无法写入：${part.name}（${e.message}）")
        }

        private var written: Long = 0L
        private var closed = false

        init {
            if (startOffset > 0) {
                raf.seek(startOffset)
                // 断点续传：截掉偏移之后的残留尾巴，避免最终文件比源文件长
                if (raf.length() > startOffset) runCatching { raf.setLength(startOffset) }
                written = startOffset
            } else {
                raf.setLength(0)
            }
        }

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.RANGE

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            raf.write(buffer, offset, length)
            written += length
        }

        override suspend fun flush() {
            runCatching { raf.fd.sync() }
        }

        /**
         * 提交：`.name.panelfm.part` → 正式名。
         *
         * **必须原子替换**。旧实现是「先 `target.delete()` 再 `renameTo()`」——两步之间
         * 进程被杀 / 断电，用户的原文件就没了（只剩一个隐藏的 `.part`），
         * 而注释里却写着「commit 时原子改名」。现在先走 `ATOMIC_MOVE`（同卷 rename(2)，
         * POSIX 语义下直接替换目标），只有文件系统不支持时才退化为「删目标 + 改名」，
         * 且删除失败要报错，不能带着半成品继续。
         */
        override suspend fun commit() {
            if (closed) return
            runCatching { raf.close() }
            closed = true
            val atomic = runCatching {
                Files.move(
                    part.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            }.getOrDefault(false)
            if (!atomic) {
                if (target.exists() && !target.delete()) {
                    throw VfsException.Io("保存失败：${target.name}（无法替换已有文件）")
                }
                if (!part.renameTo(target)) {
                    runCatching { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        .getOrElse { throw VfsException.Io("保存失败：${target.name}") }
                }
            }
            Logx.d("LocalVfs", "commit ${target.absolutePath} ($written bytes, atomic=$atomic)")
        }

        override suspend fun abort() {
            runCatching { raf.close() }
            closed = true
            runCatching { part.delete() }
        }

        override fun close() {
            if (!closed) runCatching { raf.close() }
        }
    }
}