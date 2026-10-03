package com.u707t.panelfm.core.vfs.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsFactory
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 实现（SMBJ）。
 *
 * 路径模型：`smb://host/share/a/b.txt` → authority = host[:port]，path = `/share/a/b.txt`。
 *  - 协议协商：3.1.1 → 3.0.2 → 2.1（SMBJ 自动）
 *  - NTLMv2（domain\user），SMB3 加密如服务器要求则自动启用
 *  - **偏移读写**：`File.read(buf, offset)` / `File.write(buf, offset)` → 断点续传 + 局域网流媒体
 *  - 写文件先落 `.name.panelfm.part`，commit 时服务端 rename
 *  - DFS 关闭（避免 JNA 本地库依赖），因此不走 \\domain\dfs 路径
 */
class SmbVfs(
    private val cfg: SmbConfig,
    private val env: VfsEnv,
) : VirtualFileSystem {

    override val id: String = "smb:${cfg.host}:${cfg.port}:${cfg.user}"
    override val scheme: String = "smb"
    override val label: String = "${cfg.host}:${cfg.port}"

    override val capabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = false,
        rangeRead = true,
        rangeWrite = true,
        resumable = Resumability.RANGE,
        permissions = false,
        space = false,
        symlinks = false,
        recursiveDelete = false,
        touch = true,
        streamingList = false,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shareCache = mutableMapOf<String, DiskShare>()

    override suspend fun connect() {
        _state.value = VfsState.Connecting
        try {
            withContext(env.dispatchers.vfs) { connectBlocking() }
            _state.value = VfsState.Ready
        } catch (e: Exception) {
            val msg = (e as? VfsException)?.userMessage ?: (e.message ?: "连接失败")
            _state.value = VfsState.Error(msg)
            throw if (e is VfsException) e else mapError(e, "连接失败")
        }
    }

    private fun connectBlocking() {
        client?.let { if (connection?.isConnected == true) return }
        val smbConfig = SmbjConfig.builder()
            .withDfsEnabled(false)                       // 关闭 DFS：避免 JNA 本地库依赖
            .withTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .withSoTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val c = SMBClient(smbConfig)
        val conn = try {
            c.connect(cfg.host, cfg.port)                 // SMBJ 直接返回 Connection
        } catch (e: Exception) {
            runCatching { c.close() }
            throw mapError(e, "无法连接 ${cfg.host}:${cfg.port}")
        }
        val auth = if (cfg.user.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(cfg.user, cfg.password?.toCharArray() ?: CharArray(0), cfg.domain.ifBlank { null })
        }
        val sess = try {
            conn.authenticate(auth)
        } catch (e: Exception) {
            runCatching { conn.close() }
            runCatching { c.close() }
            throw VfsException.Auth("SMB 认证失败：${e.message}")
        }
        client = c
        connection = conn
        session = sess
        shareCache.clear()
        Logx.i("SmbVfs", "connected ${cfg.user}@${cfg.host}:${cfg.port} (domain=${cfg.domain})")
    }

    private fun shareOf(shareName: String): DiskShare {
        shareCache[shareName]?.let { return it }
        val sess = session ?: throw VfsException.Network(VfsException.Network.Kind.DISCONNECTED, "SMB 会话已关闭")
        val share = try {
            sess.connectShare(shareName) as? DiskShare
                ?: throw VfsException.ProtocolError("不是磁盘共享：$shareName")
        } catch (e: Exception) {
            throw mapError(e, "打开共享失败：$shareName")
        }
        shareCache[shareName] = share
        return share
    }

    /** `/share/a/b` → (share, "a\\b") */
    private fun split(uri: VfsUri): Pair<String, String> {
        val trimmed = uri.path.trim('/')
        if (trimmed.isEmpty()) {
            val share = cfg.defaultShare ?: throw VfsException.ProtocolError("请在连接设置里指定共享名（如 public / media）")
            return share to ""
        }
        val parts = trimmed.split('/')
        val share = parts.first()
        val rest = parts.drop(1).joinToString("\\")
        return share to rest
    }

    private fun isRoot(uri: VfsUri): Boolean = uri.path.trim('/').isEmpty()

    // ------------------------------------------------------------------ 列表

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> = mutex.withLock {
        connectIfNeeded()
        val (shareName, rel) = split(uri)
        withContext(env.dispatchers.vfs) {
            if (rel.isEmpty() && cfg.defaultShare == null) {
                throw VfsException.Unsupported("请在连接设置里填写「共享名」（如 public / media / 共享），或在地址里用 /共享名 进入")
            }
            val share = shareOf(shareName)
            val entries = try {
                share.list(rel)
            } catch (e: Exception) {
                throw mapError(e, "列目录失败：${uri.displayPath}")
            }
            val filter = options.filter
            entries.asSequence()
                .filter { it.fileName != "." && it.fileName != ".." }
                .filter { options.showHidden || !it.fileName.startsWith(".") }
                .filter { filter.isNullOrBlank() || it.fileName.contains(filter, ignoreCase = true) }
                .map { toMeta(uri, it) }
                .toList()
                .let { sortItems(it, options.sort) }
        }
    }

    private fun toMeta(parent: VfsUri, info: FileIdBothDirectoryInformation): FileMetadata {
        val attrs = info.fileAttributes                       // long 位掩码
        val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        val isHidden = (attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L
        val uri = parent.child(info.fileName)
        return FileMetadata(
            uri = uri,
            name = info.fileName,
            isDirectory = isDir,
            size = if (isDir) -1L else info.endOfFile,
            lastModified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(-1L),
            mimeType = if (isDir) null else MimeTypes.of(info.fileName.substringAfterLast('.', "")),
            extra = if (isHidden) mapOf("hidden" to "true") else emptyMap(),
        )
    }

    private fun sortItems(items: List<FileMetadata>, spec: SortSpec): List<FileMetadata> {
        val cmp: Comparator<FileMetadata> = when (spec.by) {
            SortBy.NAME -> compareBy<FileMetadata> { it.name.lowercase() }
            SortBy.SIZE -> compareBy<FileMetadata> { if (it.isDirectory) -1L else it.size }
            SortBy.TIME -> compareBy<FileMetadata> { it.lastModified }
            SortBy.TYPE -> compareBy<FileMetadata> { it.extension.ifEmpty { it.name.lowercase() } }
        }
        val sorted = items.sortedWith(cmp)
        val withDirs = if (spec.dirsFirst) sorted.sortedByDescending { it.isDirectory } else sorted
        return if (spec.ascending) withDirs else withDirs.reversed()
    }

    // ------------------------------------------------------------------ 元数据

    override suspend fun stat(uri: VfsUri): FileMetadata = mutex.withLock {
        connectIfNeeded()
        val (shareName, rel) = split(uri)
        withContext(env.dispatchers.vfs) {
            if (isRoot(uri) && cfg.defaultShare == null) {
                return@withContext FileMetadata.dir(uri, shareName.ifEmpty { "/" })
            }
            val share = shareOf(shareName)
            if (rel.isEmpty()) return@withContext FileMetadata.dir(uri, shareName)
            val info = try {
                share.getFileInformation(rel)
            } catch (e: Exception) {
                throw mapError(e, "读取属性失败")
            }
            val std = info.standardInformation
            val isDir = std.isDirectory
            FileMetadata(
                uri = uri,
                name = uri.name,
                isDirectory = isDir,
                size = if (isDir) -1L else std.endOfFile,
                lastModified = runCatching { info.basicInformation.lastWriteTime.toEpochMillis() }.getOrDefault(-1L),
                mimeType = if (isDir) null else MimeTypes.of(uri.name.substringAfterLast('.', "")),
            )
        }
    }

    // ------------------------------------------------------------------ 写操作

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = mutex.withLock {
        connectIfNeeded()
        val (shareName, rel) = split(uri)
        if (rel.isEmpty()) return@withLock
        withContext(env.dispatchers.vfs) {
            val share = shareOf(shareName)
            if (parents) {
                var current = ""
                for (seg in rel.split('\\').filter { it.isNotEmpty() }) {
                    current = if (current.isEmpty()) seg else "$current\\$seg"
                    if (!runCatching { share.folderExists(current) }.getOrDefault(false)) {
                        runCatching { share.mkdir(current) }
                    }
                }
            } else {
                try {
                    share.mkdir(rel)
                } catch (e: Exception) {
                    throw mapError(e, "创建目录失败")
                }
            }
        }
    }

    override suspend fun touch(uri: VfsUri) {
        val writer = openWrite(uri, 0L, 0L)
        try {
            writer.commit()
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw e
        }
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = mutex.withLock {
        connectIfNeeded()
        withContext(env.dispatchers.vfs) {
            var done = 0L
            for (u in uris) {
                val (shareName, rel) = split(u)
                val share = shareOf(shareName)
                deleteRecursive(share, rel, u)
                done++
                onProgress?.onProgress(done, uris.size.toLong())
            }
        }
    }

    private fun deleteRecursive(share: DiskShare, rel: String, uri: VfsUri) {
        if (rel.isEmpty()) throw VfsException.Permission("不能删除共享根目录")
        val isDir = runCatching { share.folderExists(rel) }.getOrDefault(false)
        if (isDir) {
            runCatching { share.list(rel) }.getOrNull()?.forEach { child ->
                deleteRecursive(share, "$rel\\${child.fileName}", uri.child(child.fileName))
            }
            runCatching { share.rmdir(rel, false) }.getOrElse { throw mapError(it, "删除目录失败：$rel") }
        } else {
            runCatching { share.rm(rel) }.getOrElse { throw mapError(it, "删除文件失败：$rel") }
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = mutex.withLock {
        connectIfNeeded()
        val (shareFrom, relFrom) = split(from)
        val (shareTo, relTo) = split(to)
        if (shareFrom != shareTo) return@withLock false
        withContext(env.dispatchers.vfs) {
            val share = shareOf(shareFrom)
            val file = try {
                openHandle(share, relFrom, write = true)
            } catch (e: Exception) {
                throw mapError(e, "重命名失败")
            }
            file.use {
                runCatching { it.rename(relTo, true) }
                    .getOrElse { e -> throw mapError(e, "重命名失败") }
            }
            true
        }
    }

    override suspend fun space(uri: VfsUri): SpaceInfo? = runCatching {
        mutex.withLock {
            connectIfNeeded()
            val (shareName, _) = split(uri)
            withContext(env.dispatchers.vfs) {
                val info = shareOf(shareName).shareInformation
                SpaceInfo(total = info.totalSpace, free = info.freeSpace)
            }
        }
    }.getOrNull()

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader = SmbReader(uri, offset)

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter = SmbWriter(uri, offset)

    override fun close() {
        runCatching { shareCache.values.forEach { it.close() } }
        shareCache.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
        _state.value = VfsState.Idle
    }

    // ------------------------------------------------------------------ 内部

    private fun connectIfNeeded() {
        if (connection?.isConnected != true) connectBlocking()
    }

    private fun openHandle(share: DiskShare, rel: String, write: Boolean, offsetZero: Boolean = true): File {
        val access = if (write) EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ) else EnumSet.of(AccessMask.GENERIC_READ)
        val disposition = if (write) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OPEN
        // 续写时不能截断：FILE_OPEN_IF 保留已有内容，由调用方按偏移写
        val options = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        return share.openFile(rel, access, null, SMB2ShareAccess.ALL, disposition, options)
    }

    private fun mapError(e: Throwable, prefix: String): VfsException = when (e) {
        is VfsException -> e
        is com.hierynomus.mssmb2.SMBApiException -> when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCESS_DENIED,
            -> VfsException.Auth("$prefix：认证/权限被拒绝")
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            -> VfsException.NotFound(VfsUri.of("smb", cfg.host, "/"))
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> VfsException.Conflict(VfsUri.of("smb", cfg.host, "/"))
            NtStatus.STATUS_DISK_FULL -> VfsException.Quota("共享空间不足")
            else -> VfsException.ProtocolError("$prefix：${e.status}")
        }
        is java.net.UnknownHostException -> VfsException.Network(VfsException.Network.Kind.DNS, "$prefix：域名解析失败")
        is java.net.ConnectException -> VfsException.Network(VfsException.Network.Kind.REFUSED, "$prefix：连接被拒绝")
        is java.net.SocketTimeoutException -> VfsException.Network(VfsException.Network.Kind.TIMEOUT, "$prefix：连接超时")
        is IOException -> VfsException.Network(VfsException.Network.Kind.UNREACHABLE, "$prefix：${e.message}", e)
        else -> VfsException.ProtocolError("$prefix：${e.message}", e)
    }

    /** 偏移读：独立 handle，可 seek（流媒体 / 断点续传） */
    private inner class SmbReader(private val uri: VfsUri, startOffset: Long) : VfsReader {

        private var file: File? = null
        private var pos: Long = startOffset

        override var size: Long? = null
            private set

        override val supportsSeek: Boolean get() = true
        override val position: Long get() = pos

        override suspend fun seek(position: Long) {
            pos = position.coerceAtLeast(0)
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureOpen()
            val n = withContext(env.dispatchers.vfs) {
                val tmp = ByteArray(length)
                val read = file!!.read(tmp, pos)
                if (read > 0) System.arraycopy(tmp, 0, buffer, offset, read)
                read
            }
            if (n > 0) pos += n
            return n
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray = withContext(env.dispatchers.vfs) {
            ensureOpen()
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val chunk = ByteArray(length - read)
                val n = file!!.read(chunk, position + read)
                if (n <= 0) break
                System.arraycopy(chunk, 0, out, read, n)
                read += n
            }
            if (read == length) out else out.copyOf(read)
        }

        private suspend fun ensureOpen() {
            if (file != null) return
            mutex.withLock {
                connectIfNeeded()
                val (shareName, rel) = split(uri)
                withContext(env.dispatchers.vfs) {
                    file = openHandle(shareOf(shareName), rel, write = false)
                    size = runCatching { shareOf(shareName).getFileInformation(rel).standardInformation.endOfFile }.getOrNull()
                }
            }
        }

        override fun close() {
            runCatching { file?.close() }
            file = null
        }
    }

    /** 偏移写：先写 `.name.panelfm.part`，commit 时 rename */
    private inner class SmbWriter(private val target: VfsUri, private val startOffset: Long) : VfsWriter {

        private var file: File? = null
        private var written: Long = startOffset
        private var finished = false

        private val partRel: Pair<String, String> by lazy {
            val (share, rel) = split(target)
            val name = rel.substringAfterLast('\\', rel)
            val dir = rel.removeSuffix(name)
            share to (dir + ".$name.panelfm.part")
        }

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.RANGE

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureOpen()
            withContext(env.dispatchers.vfs) {
                val chunk = ByteArray(length)
                System.arraycopy(buffer, offset, chunk, 0, length)
                file!!.write(chunk, written)
            }
            written += length
        }

        override suspend fun flush() = Unit

        override suspend fun commit() {
            if (finished) return
            finished = true
            val handle = file
            file = null
            withContext(env.dispatchers.vfs) {
                runCatching { handle?.close() }
                val (shareName, partPath) = partRel
                val (_, targetPath) = split(target)
                val share = shareOf(shareName)
                val tmp = openHandle(share, partPath, write = true)
                tmp.use { it.rename(targetPath, true) }
            }
        }

        override suspend fun abort() {
            finished = true
            val handle = file
            file = null
            withContext(env.dispatchers.vfs) {
                runCatching { handle?.close() }
                val (shareName, partPath) = partRel
                runCatching { shareOf(shareName).rm(partPath) }
            }
        }

        override fun close() {
            runCatching { file?.close() }
        }

        private suspend fun ensureOpen() {
            if (file != null) return
            mutex.withLock {
                connectIfNeeded()
                val (shareName, partPath) = partRel
                withContext(env.dispatchers.vfs) {
                    file = openHandle(shareOf(shareName), partPath, write = true)
                }
            }
        }
    }

    class Factory : VfsFactory {
        override val scheme: String get() = "smb"

        override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
            SmbVfs(SmbConfig.from(config, secret, env.timeoutMs), env)
    }
}
