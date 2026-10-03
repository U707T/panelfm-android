package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsFactory
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * FTP / FTPS 协议实现（commons-net）。
 *
 *  - 控制连接串行（[controlMutex]），数据连接独立
 *  - MLSD 优先，失败回退 LIST（commons-net 自带解析器，覆盖 UNIX/IIS/NetWare）
 *  - 下载断点续传：REST + RETR（rangeRead = true）
 *  - 上传不做偏移续写（服务器行为不一致，避免写坏文件）
 *  - FTPS：显式 AUTH TLS / 隐式 990 + PBSZ 0 / PROT P
 */
class FtpVfs(
    private val cfg: FtpConfig,
    private val env: VfsEnv,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + env.dispatchers.vfs),
) : VirtualFileSystem {

    override val id: String = "${if (cfg.tls) "ftps" else "ftp"}:${cfg.host}:${cfg.port}"
    override val scheme: String get() = if (cfg.tls) "ftps" else "ftp"
    override val label: String = "${cfg.host}:${cfg.port}"

    override val capabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = false,
        rangeRead = true,          // 下载续传（REST）
        rangeWrite = false,
        resumable = Resumability.NONE,
        permissions = true,        // SITE CHMOD（部分服务器支持）
        space = false,
        symlinks = false,
        recursiveDelete = true,
        touch = true,
        streamingList = true,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val controlMutex = Mutex()
    private var client: FTPClient? = null

    // ------------------------------------------------------------------ 连接

    private fun newClient(): FTPClient {
        val c: FTPClient = when {
            cfg.implicitTls -> FTPSClient(true)
            cfg.tls -> FTPSClient(false)
            else -> FTPClient()
        }
        c.connectTimeout = cfg.timeoutMs
        c.defaultTimeout = cfg.timeoutMs
        c.controlEncoding = "UTF-8"
        c.listHiddenFiles = true
        if (c is FTPSClient && cfg.trustSelfSigned) {
            c.trustManager = InsecureTrustManager
        }
        return c
    }

    private suspend fun connected(): FTPClient = controlMutex.withLock {
        client?.takeIf { it.isConnected }?.let { return@withLock it }
        withContext(env.dispatchers.vfs) {
            val c = newClient()
            try {
                c.connect(cfg.host, cfg.port)
                if (!FTPReply.isPositiveCompletion(c.replyCode)) throw VfsException.Auth("FTP 服务拒绝连接（${c.replyCode}）")
                if (c is FTPSClient) {
                    c.execPBSZ(0)
                    c.execPROT("P")
                    c.execCCC()
                }
                if (!c.login(cfg.user, cfg.password ?: "")) {
                    throw VfsException.Auth("登录失败（${c.replyCode} ${c.replyString}）")
                }
                c.setFileType(FTP.BINARY_FILE_TYPE)
                if (cfg.passive) {
                    c.enterLocalPassiveMode()
                    c.setUseEPSVwithIPv4(true)
                } else {
                    c.enterLocalActiveMode()
                }
                if (cfg.basePath.isNotBlank() && cfg.basePath != "/") {
                    runCatching { c.changeWorkingDirectory(cfg.basePath) }
                }
                client = c
                _state.value = VfsState.Ready
                Logx.i("FtpVfs", "connected ${cfg.host}:${cfg.port} tls=${cfg.tls}")
                c
            } catch (e: Exception) {
                runCatching { c.disconnect() }
                _state.value = VfsState.Error(e.message ?: "连接失败")
                throw wrap(e)
            }
        }
    }

    private fun disconnect() {
        runCatching { client?.logout() }
        runCatching { client?.disconnect() }
        client = null
    }

    override suspend fun connect() {
        _state.value = VfsState.Connecting
        connected()
    }

    private suspend fun <T> withControl(block: suspend (FTPClient) -> T): T {
        try {
            return block(connected())
        } catch (e: IOException) {
            disconnect()
            throw wrap(e)
        }
    }

    private fun wrap(e: Exception): VfsException = when (e) {
        is VfsException -> e
        is UnknownHostException -> VfsException.Network(VfsException.Network.Kind.DNS, "域名解析失败：${cfg.host}", e)
        is SocketTimeoutException -> VfsException.Network(VfsException.Network.Kind.TIMEOUT, "FTP 超时：${cfg.host}:${cfg.port}", e)
        is IOException -> VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "网络错误", e)
        else -> VfsException.ProtocolError(e.message ?: "FTP 错误", e)
    }

    // ------------------------------------------------------------------ 列表

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> = withControl { c ->
        withContext(env.dispatchers.vfs) {
            val path = uri.path
            val files: Array<FTPFile> = try {
                c.mlistDir(path) ?: c.listFiles(path)
            } catch (e: IOException) {
                c.listFiles(path)
            }
            val items = files
                .filter { it.name != "." && it.name != ".." }
                .filter { options.showHidden || !it.name.startsWith(".") }
                .filter { options.filter.isNullOrBlank() || it.name.contains(options.filter!!, ignoreCase = true) }
                .map { toMeta(uri, it) }
            sortItems(items, options.sort)
        }
    }

    override suspend fun stat(uri: VfsUri): FileMetadata = withControl { c ->
        withContext(env.dispatchers.vfs) {
            val parent = uri.parent
            if (parent == null) {
                return@withContext FileMetadata.dir(uri, uri.name.ifEmpty { "/" })
            }
            val files = runCatching { c.mlistDir(parent.path) }.getOrNull()
                ?: c.listFiles(parent.path)
            val found = files.firstOrNull { it.name == uri.name }
                ?: run {
                    // 某些服务器 MLSD 不返回目录自身，做一次 CWD 探测
                    if (c.changeWorkingDirectory(uri.path)) {
                        c.changeWorkingDirectory(parent.path.ifEmpty { "/" })
                        return@withContext FileMetadata.dir(uri, uri.name)
                    }
                    throw VfsException.NotFound(uri)
                }
            toMeta(parent, found)
        }
    }

    private fun toMeta(parent: VfsUri, f: FTPFile): FileMetadata {
        val uri = parent.child(f.name)
        val isDir = f.isDirectory
        val perms = runCatching { ftpPermissions(f) }.getOrNull()
        val time = runCatching { f.timestampInstant.toEpochMilli() }.getOrElse { -1L }
        return FileMetadata(
            uri = uri,
            name = f.name,
            isDirectory = isDir,
            isSymlink = f.isSymbolicLink,
            symlinkTarget = f.link,
            size = if (isDir) -1L else f.size,
            lastModified = time,
            mimeType = if (isDir) null else MimeTypes.of(f.name.substringAfterLast('.', "")),
            permissions = perms,
            owner = f.user,
            group = f.group,
        )
    }

    private fun ftpPermissions(f: FTPFile): Int? {
        if (!f.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION) &&
            !f.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION)
        ) return null
        var mode = 0
        if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION)) mode = mode or 0b100_000_000
        if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION)) mode = mode or 0b010_000_000
        if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION)) mode = mode or 0b001_000_000
        if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION)) mode = mode or 0b000_100_000
        if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION)) mode = mode or 0b000_010_000
        if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION)) mode = mode or 0b000_001_000
        if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION)) mode = mode or 0b000_000_100
        if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION)) mode = mode or 0b000_000_010
        if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)) mode = mode or 0b000_000_001
        return mode
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

    // ------------------------------------------------------------------ 写

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = withControl { c ->
        withContext(env.dispatchers.vfs) {
            if (parents) {
                var current = ""
                for (seg in uri.path.trim('/').split('/').filter { it.isNotEmpty() }) {
                    current = "$current/$seg"
                    runCatching { c.makeDirectory(current) }
                }
            } else if (!c.makeDirectory(uri.path)) {
                throw VfsException.Io("创建目录失败：${uri.path}")
            }
        }
    }

    override suspend fun touch(uri: VfsUri) = withControl { c ->
        withContext(env.dispatchers.vfs) {
            c.storeFile(uri.path, ByteArray(0).inputStream())
            Unit
        }
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = withControl { c ->
        withContext(env.dispatchers.vfs) {
            var done = 0L
            for (u in uris) {
                deleteRecursive(c, u.path)
                done++
                onProgress?.onProgress(done, uris.size.toLong())
            }
        }
    }

    private suspend fun deleteRecursive(c: FTPClient, path: String) {
        val files = runCatching { c.mlistDir(path) }.getOrNull() ?: c.listFiles(path)
        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            val childPath = path.trimEnd('/') + "/" + f.name
            if (f.isDirectory) deleteRecursive(c, childPath) else c.deleteFile(childPath)
        }
        if (!c.removeDirectory(path) && !c.deleteFile(path)) {
            throw VfsException.Permission("删除失败：$path")
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = withControl { c ->
        withContext(env.dispatchers.vfs) {
            to.parent?.let { parent -> runCatching { mkdirBlocking(c, parent.path) } }
            val ok = c.rename(from.path, to.path)
            if (!ok) throw VfsException.ProtocolError("重命名失败（${c.replyCode} ${c.replyString}）")
            true
        }
    }

    private fun mkdirBlocking(c: FTPClient, path: String) {
        if (path.isBlank() || path == "/") return
        var current = ""
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            current = "$current/$seg"
            runCatching { c.makeDirectory(current) }
        }
    }

    override suspend fun setPermissions(uri: VfsUri, mode: Int) = withControl { c ->
        withContext(env.dispatchers.vfs) {
            val octal = Integer.toOctalString(mode and 0x1FF)
            val ok = c.sendCommand("SITE CHMOD", "$octal ${uri.path}")
            if (c.replyCode >= 400) throw VfsException.Unsupported("服务器不支持 SITE CHMOD")
            Unit
        }
    }

    // ------------------------------------------------------------------ 流

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
        FtpReader(uri, offset)

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        if (offset > 0L) throw VfsException.Unsupported("FTP 上传断点续传不稳定，已改为整文件重传")
        return FtpWriter(uri)
    }

    override fun close() {
        disconnect()
        _state.value = VfsState.Idle
    }

    companion object {
        private const val SEQUENTIAL_SNIFF = 64 * 1024
    }

    /** 下载：REST offset + RETR，整个生命周期持有控制锁（FTP 语义决定）。 */
    private inner class FtpReader(private val uri: VfsUri, private val startOffset: Long) : VfsReader {

        private var locked = false
        private var stream: InputStream? = null
        private var pos = startOffset
        private var bytesRead = 0L

        override var size: Long? = null
            private set

        override val supportsSeek: Boolean get() = false
        override val position: Long get() = pos

        override suspend fun seek(position: Long) {
            if (position != startOffset) throw VfsException.Unsupported("FTP 流不支持任意 seek")
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureOpen()
            val n = stream!!.read(buffer, offset, length)
            if (n > 0) {
                pos += n
                bytesRead += n
            }
            return n
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray = withContext(env.dispatchers.vfs) {
            // 顺序流：重新建立一次 REST + RETR
            if (position == 0L && length <= FtpVfs.SEQUENTIAL_SNIFF) {
                val local = ByteArray(length)
                var read = 0
                ensureOpen()
                while (read < length) {
                    val n = stream!!.read(local, read, length - read)
                    if (n < 0) break
                    read += n
                }
                return@withContext if (read == length) local else local.copyOf(read)
            }
            throw VfsException.Unsupported("FTP 不支持随机读取")
        }

        private suspend fun ensureOpen() {
            if (stream != null) return
            controlMutex.lock()
            locked = true
            val c = try {
                connected()
            } catch (e: Exception) {
                release()
                throw e
            }
            withContext(env.dispatchers.vfs) {
                try {
                    if (startOffset > 0) c.setRestartOffset(startOffset)
                    val fromMlsd = runCatching { c.mlistFile(uri.path)?.size ?: -1L }.getOrDefault(-1L)
                    val fromSize = runCatching { c.size(uri.path).toLong() }.getOrDefault(-1L)
                    val total = if (fromMlsd > 0) fromMlsd else fromSize
                    size = total.takeIf { it > 0 }
                    val s = c.retrieveFileStream(uri.path)
                        ?: throw VfsException.ProtocolError("无法开始下载：${c.replyCode} ${c.replyString}")
                    stream = s
                } catch (e: Exception) {
                    release()
                    throw wrap(e as? Exception ?: IOException(e))
                }
            }
        }

        private fun release() {
            if (locked) {
                locked = false
                controlMutex.unlock()
            }
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
            client?.let { c -> runCatching { c.completePendingCommand() } }
            release()
        }
    }

    /** 上传：STORE 流式写，commit 时 completePendingCommand。 */
    private inner class FtpWriter(private val uri: VfsUri) : VfsWriter {

        private var locked = false
        private var stream: OutputStream? = null
        private var written = 0L

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.NONE

        private suspend fun ensureOpen() {
            if (stream != null) return
            controlMutex.lock()
            locked = true
            val c = try {
                connected()
            } catch (e: Exception) {
                release()
                throw e
            }
            withContext(env.dispatchers.vfs) {
                try {
                    uri.parent?.let { mkdirBlocking(c, it.path) }
                    val s = c.storeFileStream(uri.path)
                        ?: throw VfsException.ProtocolError("无法开始上传：${c.replyCode} ${c.replyString}")
                    stream = s
                } catch (e: Exception) {
                    release()
                    throw wrap(e as? Exception ?: IOException(e))
                }
            }
        }

        private fun release() {
            if (locked) {
                locked = false
                controlMutex.unlock()
            }
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureOpen()
            stream!!.write(buffer, offset, length)
            written += length
        }

        override suspend fun flush() {
            runCatching { stream?.flush() }
        }

        override suspend fun commit() {
            runCatching { stream?.close() }
            stream = null
            val c = client
            if (c != null && !c.completePendingCommand()) {
                release()
                throw VfsException.ProtocolError("上传未完成（${c.replyCode} ${c.replyString}）")
            }
            release()
            Logx.d("FtpVfs", "uploaded ${uri.path} ($written bytes)")
        }

        override suspend fun abort() {
            runCatching { stream?.close() }
            stream = null
            client?.let { c ->
                runCatching { c.abort() }
                runCatching { c.completePendingCommand() }
                runCatching { c.deleteFile(uri.path) }
            }
            release()
        }

        override fun close() = Unit
    }
}

/** 自签证书：仅用于用户显式勾选「信任自签」的 FTPS 连接。 */
object InsecureTrustManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}

class FtpVfsFactory(private val schemeName: String = "ftp") : VfsFactory {
    override val scheme: String get() = schemeName

    override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
        FtpVfs(FtpConfig.from(config, secret), env)
}
