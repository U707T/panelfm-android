package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.vfs.sortFileItems
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * WebDAV 协议实现（自研客户端，OkHttp）。
 *
 *  - PROPFIND Depth 0/1 列目录与 stat
 *  - MOVE / COPY 走服务端快路径（零中转）
 *  - GET + Range 支持断点续传下载与流媒体
 *  - PUT 走管道流式上传（内存友好），落 `.part` 文件后 MOVE 合并（可配置）
 */
class WebDavVfs(
    private val cfg: DavConfig,
    private val env: VfsEnv,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + env.dispatchers.vfs),
) : VirtualFileSystem {

    override val id: String = "dav:${cfg.host}:${cfg.port}${cfg.basePath}"
    override val scheme: String = "dav"
    override val label: String = "${cfg.host}:${cfg.port}"

    override val capabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = true,
        rangeRead = true,
        rangeWrite = false,           // 分片续传留待 M2 增强（当前为整体 PUT）
        resumable = Resumability.NONE,
        permissions = false,
        space = false,
        symlinks = false,
        recursiveDelete = true,
        touch = true,
        streamingList = false,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val http: OkHttpClient by lazy { DavHttp.client(cfg) }

    override suspend fun connect() {
        _state.value = VfsState.Connecting
        try {
            propfind(VfsUri.of(scheme, "${cfg.host}:${cfg.port}", "/"), depth = 0)
            _state.value = VfsState.Ready
        } catch (e: Exception) {
            _state.value = VfsState.Error((e as? VfsException)?.userMessage ?: (e.message ?: "连接失败"))
            throw wrap(e)
        }
    }

    // ------------------------------------------------------------------ 列表 / 元数据

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> =
        withContext(env.dispatchers.vfs) {
            val items = propfind(uri, depth = 1)
                .filter { options.showHidden || !it.name.startsWith(".") }
                .filter { options.filter.isNullOrBlank() || it.name.contains(options.filter!!, ignoreCase = true) }
            sortFileItems(items, options.sort)
        }

    override suspend fun stat(uri: VfsUri): FileMetadata = withContext(env.dispatchers.vfs) {
        val url = DavHttp.url(cfg, uri.path)
        val resp = execute(
            Request.Builder().url(url)
                .method("PROPFIND", BODY_PROPFIND.toRequestBody(XML))
                .header("Depth", "0")
                .build()
        )
        resp.use { r ->
            if (r.code == 404) throw VfsException.NotFound(uri)
            if (!r.isSuccessful) throw httpError(r, uri)
            // stat：保留「自身」节点（skipSelf = false），才能拿到文件的类型与大小
            val parsed = DavXml.parseMultiStatus(
                DavXml.newParser().apply { setInput(r.body.byteStream(), null) },
                uri,
                cfg.basePath,
                skipSelf = false,
            )
            val self = parsed.firstOrNull() ?: FileMetadata(
                uri = uri, name = uri.name.ifEmpty { "/" }, isDirectory = true,
            )
            if (self.uri.path == uri.path) self else self.copy(uri = uri.copy(path = self.uri.path), name = self.name)
        }
    }

    override suspend fun exists(uri: VfsUri): Boolean =
        runCatching { stat(uri) }.isSuccess

    private suspend fun propfind(uri: VfsUri, depth: Int): List<FileMetadata> {
        val url = DavHttp.url(cfg, uri.path)
        val resp = execute(
            Request.Builder().url(url)
                .method("PROPFIND", BODY_PROPFIND.toRequestBody(XML))
                .header("Depth", depth.toString())
                .build()
        )
        resp.use { r ->
            when {
                r.code == 404 -> throw VfsException.NotFound(uri)
                r.code == 401 || r.code == 403 -> throw VfsException.Auth("认证失败或被拒绝（${r.code}）", r.code)
                !r.isSuccessful -> throw httpError(r, uri)
                else -> {
                    val parser = DavXml.newParser().apply { setInput(r.body.byteStream(), null) }
                    val list = DavXml.parseMultiStatus(parser, uri, cfg.basePath)
                    return list.map { it.copy(uri = ensureAuthority(it.uri, uri)) }
                }
            }
        }
    }

    private fun ensureAuthority(child: VfsUri, parent: VfsUri): VfsUri =
        child.copy(scheme = parent.scheme, authority = parent.authority, query = parent.query)

    // ------------------------------------------------------------------ 写操作

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = withContext(env.dispatchers.vfs) {
        if (uri.isRoot) return@withContext
        val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = VfsUri.of(uri.scheme, uri.authority, "/")
        for ((i, seg) in segments.withIndex()) {
            current = current.child(seg)
            if (!parents && i != segments.lastIndex) continue
            val existing = runCatching { stat(current) }.getOrNull()
            if (existing != null) continue
            val url = DavHttp.url(cfg, current.path)
            execute(Request.Builder().url(url).method("MKCOL", null).build()).use { r ->
                if (!r.isSuccessful && r.code != 405) throw httpError(r, current)
            }
        }
    }

    override suspend fun touch(uri: VfsUri) = withContext(env.dispatchers.vfs) {
        val url = DavHttp.url(cfg, uri.path)
        execute(Request.Builder().url(url).put(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build()).use { r ->
            if (!r.isSuccessful) throw httpError(r, uri)
        }
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = withContext(env.dispatchers.vfs) {
        var done = 0L
        for (u in uris) {
            val url = DavHttp.url(cfg, u.path)
            execute(Request.Builder().url(url).delete().header("Depth", "infinity").build()).use { r ->
                if (!r.isSuccessful && r.code != 404) throw httpError(r, u)
            }
            done++
            onProgress?.onProgress(done, uris.size.toLong())
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = withContext(env.dispatchers.vfs) {
        val src = DavHttp.url(cfg, from.path)
        val dst = DavHttp.url(cfg, to.path)
        execute(
            Request.Builder().url(src).method("MOVE", null)
                .header("Destination", dst.toString())
                .header("Overwrite", "F")
                .build()
        ).use { r ->
            when {
                r.isSuccessful -> true
                r.code == 412 -> throw VfsException.Conflict(to)
                r.code == 409 -> false
                else -> throw httpError(r, from)
            }
        }
    }

    override suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean = withContext(env.dispatchers.vfs) {
        val src = DavHttp.url(cfg, from.path)
        val dst = DavHttp.url(cfg, to.path)
        execute(
            Request.Builder().url(src).method("COPY", null)
                .header("Destination", dst.toString())
                .header("Overwrite", "T")
                .build()
        ).use { r -> r.isSuccessful }
    }

    // ------------------------------------------------------------------ 流

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
        DavReader(http, DavHttp.url(cfg, uri.path), env, uri, offset)

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        if (offset > 0L) throw VfsException.Unsupported("WebDAV 当前版本不支持按偏移续传上传")
        return DavWriter(
            http = http,
            partUrl = DavHttp.url(cfg, partPathOf(uri.path)),
            finalUrl = DavHttp.url(cfg, uri.path),
            scope = scope,
            dispatcher = env.dispatchers.vfs,
            expectedSize = size,
        )
    }

    override fun close() {
        // 只释放连接，不销毁 dispatcher：会话被空闲回收后仍可复用（下次调用自动重连）
        _state.value = VfsState.Idle
        runCatching { http.connectionPool.evictAll() }
    }

    // ------------------------------------------------------------------ 工具

    private suspend fun execute(request: Request): Response = withContext(env.dispatchers.vfs) {
        try {
            http.newCall(request).execute()
        } catch (e: UnknownHostException) {
            throw VfsException.Network(VfsException.Network.Kind.DNS, "域名解析失败：${cfg.host}", e)
        } catch (e: SocketTimeoutException) {
            throw VfsException.Network(VfsException.Network.Kind.TIMEOUT, "连接超时：${cfg.host}:${cfg.port}", e)
        } catch (e: IOException) {
            throw VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "网络错误", e)
        }
    }

    private fun httpError(r: Response, uri: VfsUri): VfsException = when (r.code) {
        401, 403 -> VfsException.Auth("认证失败（${r.code}）", r.code)
        404 -> VfsException.NotFound(uri)
        409 -> VfsException.Conflict(uri)
        423 -> VfsException.Permission("资源被锁定（423）")
        507 -> VfsException.Quota("服务器空间不足（507）")
        else -> VfsException.ProtocolError("HTTP ${r.code} ${r.message}")
    }

    private fun wrap(e: Exception): VfsException = when (e) {
        is VfsException -> e
        else -> VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "连接失败", e)
    }

    companion object {
        /** 同目录临时名：`.name.panelfm.part`（PUT 完成后 MOVE 成正式名，失败/取消不留半成品） */
        internal fun partPathOf(filePath: String): String {
            val idx = filePath.lastIndexOf('/')
            val dir = if (idx >= 0) filePath.substring(0, idx + 1) else "/"
            return dir + "." + filePath.substring(idx + 1) + ".panelfm.part"
        }

        private val XML: MediaType = "application/xml; charset=utf-8".toMediaType()
        private val BODY_PROPFIND = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:displayname/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:resourcetype/>
            </d:prop></d:propfind>
        """.trimIndent()
    }

    /** 随机访问读：顺序读走长连接流，seek 时用 Range 重开。 */
    private class DavReader(
        private val http: OkHttpClient,
        private val url: HttpUrl,
        private val env: VfsEnv,
        private val uri: VfsUri,
        start: Long,
    ) : VfsReader {

        private var pos: Long = start
        private var response: Response? = null
        private var stream: InputStream? = null
        private var streamPos: Long = start

        override var size: Long? = null
            private set

        override val supportsSeek: Boolean get() = true
        override val position: Long get() = pos

        override suspend fun seek(position: Long) {
            if (position != pos) {
                pos = position
                closeStream()
            }
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            withContext(env.dispatchers.vfs) {
                ensureStream()
                val n = stream!!.read(buffer, offset, length)
                if (n > 0) {
                    pos += n
                    streamPos += n
                }
                n
            }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray =
            withContext(env.dispatchers.vfs) {
                val end = position + length - 1
                val req = Request.Builder().url(url)
                    .header("Range", "bytes=$position-$end")
                    .get()
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful && r.code != 206) throw VfsException.ProtocolError("Range 读取失败：HTTP ${r.code}")
                    val bytes = r.body.bytes()
                    if (size == null) size = parseTotal(r, position, bytes.size.toLong())
                    bytes
                }
            }

        private suspend fun ensureStream() = withContext(env.dispatchers.vfs) {
            if (stream != null && streamPos == pos) return@withContext
            closeStream()
            val req = Request.Builder().url(url)
                .apply { if (pos > 0) header("Range", "bytes=$pos-") }
                .get()
                .build()
            val r = try {
                http.newCall(req).execute()
            } catch (e: IOException) {
                throw VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "网络错误", e)
            }
            if (!r.isSuccessful && r.code != 206) {
                r.close()
                throw when (r.code) {
                    401, 403 -> VfsException.Auth("认证失败（${r.code}）", r.code)
                    404 -> VfsException.NotFound(uri)
                    else -> VfsException.ProtocolError("GET 失败：HTTP ${r.code}")
                }
            }
            response = r
            stream = r.body.byteStream()
            streamPos = pos
            if (size == null) size = parseTotal(r, pos, null)
        }

        private fun parseTotal(r: Response, start: Long, read: Long?): Long? {
            val cr = r.header("Content-Range")           // bytes 0-1023/4096
            val total = cr?.substringAfterLast('/')?.toLongOrNull()
            if (total != null && total > 0) return total
            val len = r.header("Content-Length")?.toLongOrNull()
            if (len != null && start == 0L) return len
            return null
        }

        private fun closeStream() {
            runCatching { stream?.close() }
            runCatching { response?.close() }
            stream = null
            response = null
        }

        override fun close() = closeStream()
    }

    /** 流式上传：write() 写入管道，OkHttp 在另一协程读管道发 HTTP（背压天然生效）。
     *  先 PUT 到同目录 `.name.panelfm.part`，commit 时 MOVE 成正式名（原子替换）。 */
    private class DavWriter(
        private val http: OkHttpClient,
        private val partUrl: HttpUrl,
        private val finalUrl: HttpUrl,
        scope: CoroutineScope,
        private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val expectedSize: Long?,
    ) : VfsWriter {

        private val pipeIn = PipedInputStream(PIPE_BUFFER)
        private val pipeOut = PipedOutputStream(pipeIn)
        private var written = 0L

        @Volatile
        private var finished = false

        /** 当前 PUT 的 OkHttp Call：取消 / 失败清理时用它立即中止 HTTP（管道关闭不可靠） */
        @Volatile
        private var call: Call? = null

        private val upload: Deferred<Response> = scope.async(Dispatchers.IO) {
            val body = object : RequestBody() {
                override fun contentType(): MediaType = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = expectedSize ?: -1L
                override fun writeTo(sink: BufferedSink) {
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = try {
                            pipeIn.read(buf)
                        } catch (e: IOException) {
                            break
                        }
                        if (n < 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
            val c = http.newCall(Request.Builder().url(partUrl).put(body).build())
            call = c
            try {
                c.execute()
            } finally {
                call = null
            }
        }

        override val writtenBytes: Long get() = written
        override val resumable: Resumability get() = Resumability.NONE

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            try {
                pipeOut.write(buffer, offset, length)
            } catch (e: IOException) {
                throw VfsException.Io("上传中断：${e.message}", e)
            }
            written += length
        }

        override suspend fun flush() {
            runCatching { pipeOut.flush() }
        }

        override suspend fun commit() {
            if (finished) return
            finished = true
            runCatching { pipeOut.close() }
            val resp = try {
                upload.await()
            } catch (e: Exception) {
                // PUT 中途失败（网络断 / 取消）：清掉半成品再抛
                runCatching { deletePart() }
                throw e
            }
            resp.use { r ->
                if (!r.isSuccessful) {
                    runCatching { deletePart() }
                    throw when (r.code) {
                        401, 403 -> VfsException.Auth("上传被拒绝（${r.code}）", r.code)
                        507 -> VfsException.Quota("服务器空间不足（507）")
                        else -> VfsException.ProtocolError("PUT 失败：HTTP ${r.code} ${r.message}")
                    }
                }
            }
            // 原子落位：MOVE part → 目标；个别服务器不支持 MOVE 时退化为 COPY + DELETE
            val moved = movePart(overwrite = true)
            if (!moved) {
                val copied = copyPart(overwrite = true)
                if (!copied) {
                    runCatching { deletePart() }
                    throw VfsException.ProtocolError("保存失败：服务器不支持 MOVE/COPY")
                }
                runCatching { deletePart() }
            }
            Logx.d("WebDavVfs", "uploaded ${finalUrl.encodedPath} ($written bytes)")
        }

        private fun movePart(overwrite: Boolean): Boolean = try {
            http.newCall(
                Request.Builder().url(partUrl).method("MOVE", null)
                    .header("Destination", finalUrl.toString())
                    .header("Overwrite", if (overwrite) "T" else "F")
                    .build()
            ).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        private fun copyPart(overwrite: Boolean): Boolean = try {
            http.newCall(
                Request.Builder().url(partUrl).method("COPY", null)
                    .header("Destination", finalUrl.toString())
                    .header("Overwrite", if (overwrite) "T" else "F")
                    .build()
            ).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        private fun deletePart() {
            runCatching {
                http.newCall(Request.Builder().url(partUrl).delete().build()).execute().close()
            }
        }

        override suspend fun abort() {
            finished = true
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            runCatching { call?.cancel() }          // 立即中止 HTTP，不等管道
            upload.cancel()
            runCatching { kotlinx.coroutines.withTimeoutOrNull(2000) { upload.join() } }
            deletePart()
        }

        override fun close() {
            // 传输中途失败（非取消）：取消请求并清掉半成品 .part；成功 / 已取消路径无需处理
            if (!finished) {
                finished = true
                runCatching { call?.cancel() }
                deletePart()
            }
        }

        companion object {
            private const val PIPE_BUFFER = 512 * 1024
        }
    }
}

/** 协议工厂（注册到 VfsRegistry）。 */
class WebDavVfsFactory : VfsFactory {
    override val scheme: String get() = "dav"

    override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
        WebDavVfs(
            cfg = DavConfig.from(config, secret, env.userAgent(), trustSelfSignedDefault = env.trustSelfSignedDefault()),
            env = env,
        )
}
