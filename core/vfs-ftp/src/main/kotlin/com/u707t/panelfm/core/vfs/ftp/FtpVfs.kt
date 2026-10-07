package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.vfs.sortFileItems
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.partNameOf
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
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - 控制连接串行（[controlMutex]）：建连与全部命令共用同一把锁；锁等待超时给「忙」文案
 *  - MLSD 优先，失败回退 LIST（commons-net 自带解析器，覆盖 UNIX/IIS/NetWare）
 *  - 下载断点续传：REST + RETR（rangeRead = true）
 *  - 上传不做偏移续写（服务器行为不一致，避免写坏文件）；写侧先落 `.name.panelfm.part`，
 *    commit 时改名落位——覆盖上传失败不再触碰原文件
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
        setModified = true,        // MFMT（RFC 3659；服务器不支持时静默忽略）
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
        // MT「编码」：文件名编码（非法名称回退 UTF-8，避免连不上；MT 文案「编码名称不存在」）
        c.controlEncoding = runCatching { java.nio.charset.Charset.forName(cfg.encoding).name() }
            .getOrElse { "UTF-8" }
        c.listHiddenFiles = true
        if (c is FTPSClient && cfg.trustSelfSigned) {
            c.trustManager = com.u707t.panelfm.core.vfs.TlsTrust.trustManager
        }
        return c
    }

    private suspend fun connected(): FTPClient = controlMutex.withLock { ensureClientLocked() }

    /** 建立/复用连接（**调用方必须已持有 controlMutex**，否则会死锁） */
    private suspend fun ensureClientLocked(): FTPClient {
        client?.takeIf { it.isConnected }?.let { return it }
        return withContext(env.dispatchers.vfs) {
            val c = newClient()
            try {
                c.connect(cfg.host, cfg.port)
                if (!FTPReply.isPositiveCompletion(c.replyCode)) throw VfsException.Auth("FTP 服务拒绝连接（${c.replyCode}）")
                if (c is FTPSClient) {
                    c.execPBSZ(0)
                    c.execPROT("P")
                    runCatching { c.execCCC() }   // CCC 非必需，部分服务器不支持
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

    /**
     * 获取控制锁；长时间拿不到（有传输 / 播放独占，或异常路径未释放）时报「忙」而不是无限挂起。
     * 锁的语义：整条 FTP 控制连接同一时刻只允许一个使用方（发命令 + 读回复）。
     */
    private suspend fun acquireControlLock() {
        withTimeoutOrNull(CONTROL_WAIT_MS) { controlMutex.lock() }
            ?: throw VfsException.ProtocolError("FTP 控制连接忙（可能正在传输或播放），请稍后重试")
    }

    /**
     * 命令入口：**在锁内**执行 block。旧实现只串行了建连、命令在锁外并发执行——
     * 同一条控制连接上两个命令交错会让回复错配（commons-net 的 FTPClient 非线程安全）。
     */
    private suspend fun <T> withControl(block: suspend (FTPClient) -> T): T {
        acquireControlLock()
        try {
            try {
                return block(ensureClientLocked())
            } catch (e: IOException) {
                disconnect()
                throw wrap(e)
            }
        } finally {
            controlMutex.unlock()
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
            sortFileItems(items, options.sort)
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

    /**
     * MT「保留文件时间」：FTP 用 MFMT（RFC 3659）。
     * 服务器不认时返回 5xx，这里抛 Unsupported 由调用方静默忽略（不中断传输）。
     */
    override suspend fun setModified(uri: VfsUri, epochMillis: Long): Unit = withControl { c ->
        withContext(env.dispatchers.vfs) {
            val stamp = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            c.sendCommand("MFMT", "$stamp ${uri.path}")
            if (c.replyCode >= 400) throw VfsException.Unsupported("服务器不支持 MFMT")
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

        /** 控制锁最长等待：超时报「忙」（旧实现无限挂起） */
        private const val CONTROL_WAIT_MS = 30_000L
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
            // 顺序流协议：只支持「从文件头开始、流尚未被消费」的预读（探测场景）。
            // 其它组合一律拒绝——继续读会把位置带偏（与顺序 read 混用会读到错误位置）；
            // 旧实现对此零校验，且读完不推进 pos。
            if (position != 0L || startOffset != 0L || bytesRead != 0L || length > FtpVfs.SEQUENTIAL_SNIFF) {
                throw VfsException.Unsupported("FTP 顺序流仅支持从 0 起的首次预读")
            }
            val local = ByteArray(length)
            var read = 0
            ensureOpen()
            while (read < length) {
                val n = stream!!.read(local, read, length - read)
                if (n < 0) break
                read += n
            }
            // 数据确实被流消费：同步推进位置，保持 position 诚实
            if (read > 0) {
                pos += read
                bytesRead += read
            }
            if (read == length) local else local.copyOf(read)
        }

        private suspend fun ensureOpen() {
            if (stream != null) return
            acquireControlLock()
            locked = true
            val c = try {
                ensureClientLocked()   // 已持锁：必须用 Locked 版本（connected() 会再上锁 → 自死锁）
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
            val hadStream = stream != null
            runCatching { stream?.close() }
            stream = null
            if (hadStream) client?.let { c -> runCatching { c.completePendingCommand() } }
            release()
        }
    }

    /**
     * 上传：先写同目录 `.name.panelfm.part`，commit 时服务端改名落位（与 SFTP / SMB 同款）——
     * 上传失败 / 取消不再触碰目标文件（旧实现直写目标，中断即毁原文件）。
     */
    private inner class FtpWriter(private val uri: VfsUri) : VfsWriter {

        private val partUri: VfsUri = uri.parent?.child(partNameOf(uri.name)) ?: uri

        private var locked = false
        private var stream: OutputStream? = null
        private var written = 0L
        private var finished = false

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.NONE

        private suspend fun ensureOpen() {
            if (stream != null) return
            acquireControlLock()
            locked = true
            val c = try {
                ensureClientLocked()   // 已持锁：必须用 Locked 版本（connected() 会再上锁 → 自死锁）
            } catch (e: Exception) {
                release()
                throw e
            }
            withContext(env.dispatchers.vfs) {
                try {
                    uri.parent?.let { mkdirBlocking(c, it.path) }
                    val s = c.storeFileStream(partUri.path)
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
            if (finished) return
            finished = true
            // 0 字节文件（touch）可能从未 write：补一次 ensureOpen，保证 part 真实存在后再改名
            if (stream == null) ensureOpen()
            runCatching { stream?.close() }
            stream = null
            val c = client
            if (c == null || !c.completePendingCommand()) {
                // 数据连接未正常结束：part 作废，目标文件自始至终未被触碰
                runCatching { c?.deleteFile(partUri.path) }
                release()
                throw VfsException.ProtocolError("上传未完成（${c?.replyCode} ${c?.replyString}）")
            }
            // part → 正式名。个别服务器对已存在的目标拒绝 RNTO：报错并保持原文件不动
            //（不做「先删旧再重试」——那会给「新旧都没了」留窗口）。半成品由 abort 清理。
            val renamed = c.rename(partUri.path, uri.path)
            if (!renamed) {
                release()
                throw VfsException.ProtocolError("保存失败：服务器拒绝改名（${c.replyCode} ${c.replyString}）")
            }
            release()
            Logx.d("FtpVfs", "uploaded ${uri.path} ($written bytes)")
        }

        override suspend fun abort() {
            // 只清 part；目标文件不再被本实现触碰（旧实现 deleteFile(uri.path)，会把原文件连同半成品一起删）
            finished = true
            runCatching { stream?.close() }
            stream = null
            client?.let { c ->
                runCatching { c.abort() }
                runCatching { c.completePendingCommand() }
                runCatching { c.deleteFile(partUri.path) }
            }
            release()
        }

        /**
         * 非正常结束（失败 / 取消）时兜底：关流、终止挂起命令、**释放控制锁**。
         * 旧实现是空实现——传输失败时控制连接锁永远不释放，整个 FTP 会话死锁。
         * 半成品 part 由 [abort] 清理（引擎的各失败路径统一走 abort）。
         */
        override fun close() {
            if (stream == null && !locked) return
            runCatching { stream?.close() }
            stream = null
            client?.let { c ->
                runCatching { c.abort() }
                runCatching { c.completePendingCommand() }
            }
            release()
        }
    }
}

/** 自签证书：仅用于用户显式勾选「信任自签」的 FTPS 连接。 */
class FtpVfsFactory(private val schemeName: String = "ftp") : VfsFactory {
    override val scheme: String get() = schemeName

    override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
        FtpVfs(FtpConfig.from(config, secret, env.trustSelfSignedDefault()), env)
}
