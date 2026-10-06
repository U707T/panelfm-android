package com.u707t.panelfm.core.vfs.sftp

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.vfs.sortFileItems
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.HostKeyStore
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
import com.u707t.panelfm.core.vfs.partNameOf
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.IOException
import java.util.EnumSet

/**
 * SFTP 实现（Apache MINA SSHD）。
 *
 *  - 元数据操作走共享 channel（串行），文件传输各开独立 channel（大文件不阻塞列目录）
 *  - **上传/下载都支持按偏移读写** → 真断点续传（SFTP 的 fstat/fsetstat 天然支持）
 *  - 写文件先用 `.name.panelfm.part` 落地，commit 时服务端 rename（秒级、原子）
 *  - 主机指纹 TOFU：首次自动信任并记录，指纹变化直接拒绝并给出原因
 *  - 跳板机：堡垒机 local port forwarding（见 [SftpSession]）
 */
class SftpVfs(
    private val cfg: SftpConfig,
    private val env: VfsEnv,
    hostKeys: HostKeyStore,
) : VirtualFileSystem {

    override val id: String = "sftp:${cfg.host}:${cfg.port}:${cfg.user}"
    override val scheme: String = "sftp"
    override val label: String = "${cfg.host}:${cfg.port}"

    override val capabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = false,        // SFTP 无标准服务端复制
        rangeRead = true,
        rangeWrite = true,
        resumable = Resumability.RANGE,
        permissions = true,
        space = false,
        symlinks = true,
        recursiveDelete = false,       // 由本实现自己递归
        touch = true,
        setModified = true,
        streamingList = false,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val session = SftpSession(cfg, env, hostKeys)

    override suspend fun connect() {
        _state.value = VfsState.Connecting
        try {
            session.connect()
            _state.value = VfsState.Ready
        } catch (e: Exception) {
            val msg = (e as? VfsException)?.userMessage ?: (e.message ?: "连接失败")
            _state.value = VfsState.Error(msg)
            throw if (e is VfsException) e else VfsException.Network(VfsException.Network.Kind.UNREACHABLE, msg, e)
        }
    }

    // ------------------------------------------------------------------ 元数据

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> =
        session.metaMutex.withLock {
            val client = session.meta()
            withContext(env.dispatchers.vfs) {
                val entries = try {
                    client.readDir(uri.path).toList()
                } catch (e: SftpException) {
                    throw mapSftp(e, uri)
                }
                val filter = options.filter
                entries.asSequence()
                    .filter { it.filename != "." && it.filename != ".." }
                    .filter { options.showHidden || !it.filename.startsWith(".") }
                    .filter { filter.isNullOrBlank() || it.filename.contains(filter, ignoreCase = true) }
                    .map { toMeta(uri.child(it.filename), it.filename, it.attributes) }
                    .toList()
                    .let { sortFileItems(it, options.sort) }
            }
        }

    override suspend fun stat(uri: VfsUri): FileMetadata = session.metaMutex.withLock {
        val client = session.meta()
        withContext(env.dispatchers.vfs) {
            if (uri.isRoot) {
                val attrs = runCatching { client.stat("/") }.getOrNull()
                return@withContext if (attrs != null) {
                    toMeta(uri, uri.name.ifEmpty { "/" }, attrs).copy(isDirectory = true)
                } else {
                    FileMetadata.dir(uri, "/")
                }
            }
            val attrs = try {
                client.stat(uri.path)
            } catch (e: SftpException) {
                throw mapSftp(e, uri)
            }
            val linkTarget = if (isLink(attrs)) runCatching { client.readLink(uri.path) }.getOrNull() else null
            toMeta(uri, uri.name, attrs).copy(symlinkTarget = linkTarget)
        }
    }

    // 用 MINA 的属性便捷方法判定（内部已处理 S_IFMT 位与缺失 flags 的服务器差异）
    private fun isLink(attrs: SftpClient.Attributes): Boolean = attrs.isSymbolicLink

    private fun isDir(attrs: SftpClient.Attributes): Boolean = attrs.isDirectory

    private fun toMeta(uri: VfsUri, name: String, attrs: SftpClient.Attributes): FileMetadata {
        val dir = isDir(attrs)
        val mtime = runCatching { attrs.modifyTime?.toMillis() ?: -1L }.getOrDefault(-1L)
        return FileMetadata(
            uri = uri,
            name = name,
            isDirectory = dir,
            isSymlink = isLink(attrs),
            size = if (dir) -1L else attrs.size,
            lastModified = mtime,
            mimeType = if (dir) null else MimeTypes.of(name.substringAfterLast('.', "")),
            permissions = runCatching { attrs.permissions and 0x1FF }.getOrNull()?.takeIf { it != 0 },
            owner = runCatching { attrs.owner }.getOrNull(),
            group = runCatching { attrs.group }.getOrNull(),
        )
    }

    // ------------------------------------------------------------------ 写操作

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = session.metaMutex.withLock {
        val client = session.meta()
        withContext(env.dispatchers.vfs) {
            if (parents) {
                var current = ""
                for (seg in uri.path.trim('/').split('/').filter { it.isNotEmpty() }) {
                    current = "$current/$seg"
                    runCatching { client.mkdir(current) }
                }
            } else {
                try {
                    client.mkdir(uri.path)
                } catch (e: SftpException) {
                    throw mapSftp(e, uri)
                }
            }
            Unit
        }
    }

    override suspend fun touch(uri: VfsUri) {
        val writer = openWrite(uri, size = 0L, offset = 0L)
        try {
            writer.commit()
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw e
        }
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?): Unit =
        session.metaMutex.withLock {
            val client = session.meta()
            withContext(env.dispatchers.vfs) {
                var done = 0L
                for (u in uris) {
                    deleteRecursive(client, u)
                    done++
                    onProgress?.onProgress(done, uris.size.toLong())
                }
            }
        }

    private suspend fun deleteRecursive(client: SftpClient, uri: VfsUri) {
        val attrs = runCatching { client.stat(uri.path) }.getOrNull()
        if (attrs != null && isDir(attrs)) {
            val children = runCatching { client.readDir(uri.path).toList() }.getOrNull().orEmpty()
            for (child in children) {
                if (child.filename == "." || child.filename == "..") continue
                deleteRecursive(client, uri.child(child.filename))
            }
            runCatching { client.rmdir(uri.path) }.getOrElse { throw mapSftp(it as? Exception ?: IOException("rmdir 失败"), uri) }
        } else {
            runCatching { client.remove(uri.path) }.getOrElse { throw mapSftp(it as? Exception ?: IOException("remove 失败"), uri) }
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = session.metaMutex.withLock {
        val client = session.meta()
        withContext(env.dispatchers.vfs) {
            try {
                client.rename(from.path, to.path, SftpClient.CopyMode.Overwrite)
                true
            } catch (e: SftpException) {
                throw mapSftp(e, from)
            }
        }
    }

    override suspend fun setPermissions(uri: VfsUri, mode: Int) = session.metaMutex.withLock {
        val client = session.meta()
        withContext(env.dispatchers.vfs) {
            try {
                client.setStat(uri.path, SftpClient.Attributes().apply { permissions = mode })
            } catch (e: SftpException) {
                throw mapSftp(e, uri)
            }
        }
    }

    /** MT「保留文件时间」：SFTP 的 mtime 走 setStat（SSH_FXP_SETSTAT）。 */
    override suspend fun setModified(uri: VfsUri, epochMillis: Long) = session.metaMutex.withLock {
        val client = session.meta()
        withContext(env.dispatchers.vfs) {
            try {
                client.setStat(
                    uri.path,
                    SftpClient.Attributes().modifyTime(
                        java.nio.file.attribute.FileTime.fromMillis(epochMillis)
                    ),
                )
            } catch (e: SftpException) {
                throw mapSftp(e, uri)
            }
        }
    }

    /** 容量：SFTP 需要 statvfs@openssh.com 扩展，M4.1 再补；当前如实返回 null */
    override suspend fun space(uri: VfsUri): SpaceInfo? = null

    // ------------------------------------------------------------------ 流

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader = SftpReader(uri, offset)

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter = SftpWriter(uri, offset)

    override fun close() {
        session.close()
        _state.value = VfsState.Idle
    }

    // ------------------------------------------------------------------ 错误映射

    private fun mapSftp(e: Exception, uri: VfsUri): VfsException {
        val status = (e as? SftpException)?.status
        return when (status) {
            SftpConstants.SSH_FX_NO_SUCH_FILE, SftpConstants.SSH_FX_NO_SUCH_PATH -> VfsException.NotFound(uri)
            SftpConstants.SSH_FX_PERMISSION_DENIED -> VfsException.Permission("没有权限：${uri.displayPath}")
            SftpConstants.SSH_FX_FILE_ALREADY_EXISTS -> VfsException.Conflict(uri)
            SftpConstants.SSH_FX_NO_SPACE_ON_FILESYSTEM, SftpConstants.SSH_FX_QUOTA_EXCEEDED ->
                VfsException.Quota("服务器空间不足")
            else -> when (e) {
                is VfsException -> e
                is IOException -> VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "SFTP 错误", e)
                else -> VfsException.ProtocolError(e.message ?: "SFTP 错误", e)
            }
        }
    }

    /** 随机访问读：独立 channel + handle，按 offset 读（天然支持续传与流媒体）。 */
    private inner class SftpReader(private val uri: VfsUri, startOffset: Long) : VfsReader {

        private var channel: SftpClient? = null
        private var handle: SftpClient.CloseableHandle? = null
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
                channel!!.read(handle!!, pos, buffer, offset, length)
            }
            if (n > 0) pos += n
            return n
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray = withContext(env.dispatchers.vfs) {
            ensureOpen()
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = channel!!.read(handle!!, position + read, out, read, length - read)
                if (n <= 0) break
                read += n
            }
            if (read == length) out else out.copyOf(read)
        }

        private suspend fun ensureOpen() {
            if (handle != null) return
            val ch = session.newChannel()
            channel = ch
            handle = try {
                withContext(env.dispatchers.vfs) { ch.open(uri.path, EnumSet.of(SftpClient.OpenMode.Read)) }
            } catch (e: Exception) {
                runCatching { ch.close() }
                channel = null
                throw mapSftp(e, uri)
            }
            if (size == null) {
                size = runCatching {
                    session.meta().stat(uri.path).size
                }.getOrNull()?.takeIf { it >= 0 }
            }
        }

        override fun close() {
            runCatching { handle?.close() }
            runCatching { channel?.close() }
            handle = null
            channel = null
        }
    }

    /** 随机访问写：先写 `.name.panelfm.part`，commit 时服务端 rename。 */
    private inner class SftpWriter(private val target: VfsUri, private val startOffset: Long) : VfsWriter {

        private val partUri: VfsUri =
            target.parent?.child(partNameOf(target.name)) ?: target

        private var channel: SftpClient? = null
        private var handle: SftpClient.CloseableHandle? = null
        private var written: Long = startOffset
        private var finished = false

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.RANGE

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureOpen()
            withContext(env.dispatchers.vfs) {
                channel!!.write(handle!!, written, buffer, offset, length)
            }
            written += length
        }

        override suspend fun flush() = Unit

        override suspend fun commit() {
            if (finished) return
            finished = true
            // 0 字节文件（touch）可能从未 write → 这里补一次 ensureOpen，
            // 保证 part 文件真实存在后再 rename（旧实现会「成功」但不产生文件）
            runCatching { ensureOpen() }
            runCatching { handle?.close() }
            handle = null
            val ch = channel
            channel = null
            try {
                withContext(env.dispatchers.vfs) {
                    ch?.rename(partUri.path, target.path, SftpClient.CopyMode.Overwrite)
                }
            } catch (e: Exception) {
                runCatching { ch?.close() }
                throw mapSftp(e, target)
            }
            runCatching { ch?.close() }
            Logx.d("SftpVfs", "commit ${target.path} ($written bytes)")
        }

        override suspend fun abort() {
            finished = true
            runCatching { handle?.close() }
            runCatching { channel?.remove(partUri.path) }
            runCatching { channel?.close() }
            handle = null
            channel = null
        }

        override fun close() {
            runCatching { handle?.close() }
            runCatching { channel?.close() }
        }

        private suspend fun ensureOpen() {
            if (handle != null) return
            val ch = session.newChannel()
            channel = ch
            val modes = EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create)
            if (startOffset == 0L) modes.add(SftpClient.OpenMode.Truncate)
            handle = try {
                withContext(env.dispatchers.vfs) { ch.open(partUri.path, modes) }
            } catch (e: Exception) {
                runCatching { ch.close() }
                channel = null
                throw mapSftp(e, partUri)
            }
        }
    }

    class Factory(private val hostKeys: HostKeyStore) : VfsFactory {
        override val scheme: String get() = "sftp"

        override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
            SftpVfs(SftpConfig.from(config, secret, env.timeoutMs), env, hostKeys)
    }
}
