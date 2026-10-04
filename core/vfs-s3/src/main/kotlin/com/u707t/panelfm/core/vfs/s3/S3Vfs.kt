package com.u707t.panelfm.core.vfs.s3

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.vfs.sortFileItems
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
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * S3 兼容对象存储（自研 SigV4 客户端）。
 *
 * 路径模型：`s3://bucket/key/dir/file`；`s3://_/` = 列出所有 Bucket（一键选择）。
 *  - 列表：ListObjectsV2 + delimiter 模拟目录，continuation-token 分页
 *  - 读取：GET + Range（断点续传下载 / 媒体播放）
 *  - 写入：≤ partSize 单次 PUT；更大自动 Multipart（8 MB 分片，边传边分）
 *  - 复制/移动：服务端 CopyObject（同桶秒级）
 *  - 自动 Content-Type（按扩展名映射）、可选自定义下载域名
 */
class S3Vfs(
    private val cfg: S3Config,
    private val env: VfsEnv,
) : VirtualFileSystem {

    override val id: String = "s3:${cfg.endpoint}:${cfg.accessKey.take(6)}"
    override val scheme: String = "s3"
    override val label: String = cfg.endpoint.removePrefix("https://").removePrefix("http://")

    override val capabilities = VfsCapabilities(
        rename = true,              // CopyObject + Delete（服务端）
        serverSideCopy = true,
        rangeRead = true,
        rangeWrite = false,         // 上传不做偏移续写（Multipart 断点跨进程续传在 M9 落库）
        resumable = Resumability.NONE,
        permissions = false,
        space = false,
        symlinks = false,
        recursiveDelete = true,
        touch = true,
        streamingList = true,
        writable = true,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val client = S3Client(cfg)
    private val mutex = Mutex()

    override suspend fun connect() {
        _state.value = VfsState.Connecting
        try {
            withContext(env.dispatchers.vfs) {
                // 有默认 Bucket 时只验证该 Bucket（受限密钥可能没有 ListBuckets 权限，
                // 旧实现无条件 listBuckets 会让这类连接直接不可用）；
                // 没有默认 Bucket 才列出全部 Bucket（供一键选择）。
                val b = cfg.bucket
                if (b != null) client.listObjects(b, "", delimiter = "/", maxKeys = 1)
                else client.listBuckets()
            }
            _state.value = VfsState.Ready
        } catch (e: Exception) {
            val msg = (e as? VfsException)?.userMessage ?: (e.message ?: "连接失败")
            _state.value = VfsState.Error(msg)
            throw if (e is VfsException) e else VfsException.Network(VfsException.Network.Kind.UNREACHABLE, msg, e)
        }
    }

    /**
     * 从 URI 解析 Bucket 名。
     * 约定：`s3://bucket/key`；连接根用端点（host:port）或 `_` 作为 authority —— 这两者都**不是** Bucket 名，
     * 此时回落到配置的默认 Bucket（没有则返回 null = 列出全部 Bucket）。
     * （历史 bug：把 `host:port` 当成了 Bucket 名 → 列表请求 404 / 403，S3 完全不可用。）
     */
    private fun bucketOf(uri: VfsUri): String? {
        val a = uri.authority
        return a.takeIf { it.isNotBlank() && it != "_" && it != cfg.uriAuthority } ?: cfg.bucket
    }

    private fun keyOf(uri: VfsUri): String = uri.path.trim('/')

    private fun isRoot(uri: VfsUri): Boolean = keyOf(uri).isEmpty()

    // ------------------------------------------------------------------ 列表

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> = mutex.withLock {
        val bucket = bucketOf(uri)
        withContext(env.dispatchers.vfs) {
            if (bucket == null) {
                // 根：列出所有 bucket（保留连接参数 ?c=，否则点进 Bucket 后找不到会话）
                val buckets = client.listBuckets()
                val items = buckets.map { name ->
                    FileMetadata.dir(VfsUri.of(scheme, name, "/", uri.query), name)
                }
                return@withContext sortFileItems(items, options.sort)
            }
            val prefix = keyOf(uri).let { if (it.isEmpty()) "" else "$it/" }
            val result = client.listObjects(bucket = bucket, prefix = prefix, delimiter = "/")
            val filter = options.filter
            val items = ArrayList<FileMetadata>()
            result.entries.forEach { entry ->
                if (entry.isPrefix) {
                    val name = entry.key.removePrefix(prefix).trimEnd('/')
                    if (name.isNotEmpty()) items.add(FileMetadata.dir(uri.child(name), name))
                } else {
                    if (entry.key == prefix) return@forEach  // 目录占位对象
                    val name = entry.key.removePrefix(prefix)
                    if (name.isEmpty() || name.contains('/')) return@forEach
                    if (!options.showHidden && name.startsWith(".")) return@forEach
                    if (!filter.isNullOrBlank() && !name.contains(filter, ignoreCase = true)) return@forEach
                    items.add(
                        FileMetadata(
                            uri = uri.child(name),
                            name = name,
                            isDirectory = false,
                            size = entry.size,
                            lastModified = entry.lastModified,
                            mimeType = MimeTypes.of(name.substringAfterLast('.', "")),
                            etag = entry.etag,
                        )
                    )
                }
            }
            sortFileItems(items, options.sort)
        }
    }

    override suspend fun stat(uri: VfsUri): FileMetadata = mutex.withLock {
        val bucket = bucketOf(uri) ?: return@withLock FileMetadata.dir(uri, uri.authority.ifEmpty { "/" })
        val key = keyOf(uri)
        withContext(env.dispatchers.vfs) {
            if (key.isEmpty()) return@withContext FileMetadata.dir(uri, bucket)
            // 目录：以 "key/" 作为占位对象，或存在子对象
            val asDir = client.head(bucket, "$key/")
            if (asDir != null) return@withContext FileMetadata.dir(uri, uri.name, asDir.lastModified)
            val asFile = client.head(bucket, key)
            if (asFile != null) {
                return@withContext FileMetadata(
                    uri = uri,
                    name = uri.name,
                    isDirectory = false,
                    size = asFile.size,
                    lastModified = asFile.lastModified,
                    mimeType = MimeTypes.of(uri.name.substringAfterLast('.', "")),
                    etag = asFile.etag,
                )
            }
            // 有子对象 → 视作目录
            val children = client.listObjects(bucket, "$key/", delimiter = "/", maxKeys = 1)
            if (children.entries.isNotEmpty()) return@withContext FileMetadata.dir(uri, uri.name)
            throw VfsException.NotFound(uri)
        }
    }

    // ------------------------------------------------------------------ 写操作

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = mutex.withLock {
        val bucket = bucketOf(uri) ?: throw VfsException.Unsupported("请先选择 Bucket")
        val key = keyOf(uri)
        if (key.isEmpty()) return@withLock
        withContext(env.dispatchers.vfs) {
            client.putBytes(bucket, "$key/", ByteArray(0), "application/x-directory")
        }
    }

    override suspend fun touch(uri: VfsUri) {
        val bucket = bucketOf(uri) ?: throw VfsException.Unsupported("请先选择 Bucket")
        val key = keyOf(uri)
        withContext(env.dispatchers.vfs) { client.putBytes(bucket, key, ByteArray(0), contentTypeOf(uri.name)) }
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = mutex.withLock {
        var done = 0L
        for (u in uris) {
            val bucket = bucketOf(u) ?: continue
            val key = keyOf(u)
            withContext(env.dispatchers.vfs) { deleteRecursive(bucket, key) }
            done++
            onProgress?.onProgress(done, uris.size.toLong())
        }
    }

    private suspend fun deleteRecursive(bucket: String, key: String) {
        val meta = client.head(bucket, key)
        val isDir = meta == null && key.isNotEmpty()
        if (isDir) {
            var token: String? = null
            do {
                val page = client.listObjects(bucket, "$key/", delimiter = null, continuationToken = token)
                page.entries.forEach { entry -> client.deleteObject(bucket, entry.key) }
                token = page.nextToken
            } while (page.truncated && token != null)
            client.deleteObject(bucket, "$key/")
        } else {
            client.deleteObject(bucket, key)
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = mutex.withLock {
        val bucket = bucketOf(from) ?: return@withLock false
        val fromKey = keyOf(from)
        val toKey = keyOf(to)
        withContext(env.dispatchers.vfs) {
            copyRecursive(bucket, fromKey, toKey)
            deleteRecursive(bucket, fromKey)
        }
        true
    }

    override suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean = mutex.withLock {
        val bucket = bucketOf(from) ?: return@withLock false
        withContext(env.dispatchers.vfs) { copyRecursive(bucket, keyOf(from), keyOf(to)) }
        true
    }

    private suspend fun copyRecursive(bucket: String, fromKey: String, toKey: String) {
        val isFile = client.head(bucket, fromKey) != null
        if (isFile) {
            client.copyObject(bucket, fromKey, toKey)
            return
        }
        // 目录：复制所有子对象（保留相对路径）
        var token: String? = null
        do {
            val page = client.listObjects(bucket, "$fromKey/", delimiter = null, continuationToken = token)
            page.entries.forEach { entry ->
                val relative = entry.key.removePrefix("$fromKey/")
                client.copyObject(bucket, entry.key, "$toKey/$relative")
            }
            token = page.nextToken
        } while (page.truncated && token != null)
    }

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader {
        val bucket = bucketOf(uri) ?: throw VfsException.Unsupported("请先选择 Bucket")
        return S3Reader(bucket, keyOf(uri), offset)
    }

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        if (offset > 0L) throw VfsException.Unsupported("S3 上传暂不支持偏移续写")
        val bucket = bucketOf(uri) ?: throw VfsException.Unsupported("请先选择 Bucket")
        return S3Writer(bucket, keyOf(uri), contentTypeOf(uri.name))
    }

    override suspend fun space(uri: VfsUri): SpaceInfo? = null

    override fun close() {
        client.close()
        _state.value = VfsState.Idle
    }

    private fun contentTypeOf(name: String): String =
        MimeTypes.of(name.substringAfterLast('.', "")) ?: "application/octet-stream"

    // ------------------------------------------------------------------ 流

    /** Range 读取：顺序流 + 任意位置读（流媒体验证过的工作方式） */
    private inner class S3Reader(private val bucket: String, private val key: String, start: Long) : VfsReader {

        private var pos: Long = start
        private var response: Response? = null
        private var stream: InputStream? = null

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

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(env.dispatchers.vfs) {
            ensureStream()
            val n = stream!!.read(buffer, offset, length)
            if (n > 0) pos += n
            n
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray =
            client.getBytes(bucket, key, offset = position, length = length.toLong())

        private suspend fun ensureStream() {
            if (stream != null) return
            val r = client.openGet(bucket, key, pos)
            response = r
            stream = r.body.byteStream()
            if (size == null) {
                size = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.header("Content-Length")?.toLongOrNull()
            }
        }

        private fun closeStream() {
            runCatching { stream?.close() }
            runCatching { response?.close() }
            stream = null
            response = null
        }

        override fun close() = closeStream()
    }

    /** 分片上传：缓冲满 8 MB 立刻上传一个 part（边传边分），commit 时合并 */
    private inner class S3Writer(
        private val bucket: String,
        private val key: String,
        private val contentType: String,
    ) : VfsWriter {

        private val buffer = ByteArrayOutputStream(PART_SIZE)
        private val parts = mutableListOf<Pair<Int, String>>()
        private var uploadId: String? = null
        private var total = 0L
        private var finished = false

        override val writtenBytes: Long get() = total
        override val resumable: Resumability get() = Resumability.NONE

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            var remaining = length
            var o = offset
            while (remaining > 0) {
                val take = minOf(remaining, PART_SIZE - this.buffer.size())
                this.buffer.write(buffer, o, take)
                total += take
                remaining -= take
                o += take
                if (this.buffer.size() >= PART_SIZE) flushPart()
            }
        }

        override suspend fun flush() = Unit

        private suspend fun flushPart() {
            if (buffer.size() == 0) return
            val id = uploadId ?: withContext(env.dispatchers.vfs) {
                client.createMultipart(bucket, key, contentType).also { uploadId = it }
            }
            val partNumber = parts.size + 1
            val bytes = buffer.toByteArray()
            buffer.reset()
            val etag = withContext(env.dispatchers.vfs) {
                client.uploadPart(bucket, key, id, partNumber, bytes)
            }
            parts += partNumber to etag
        }

        override suspend fun commit() {
            if (finished) return
            finished = true
            withContext(env.dispatchers.vfs) {
                val id = uploadId
                if (id == null) {
                    // 小文件：单次 PUT
                    client.putBytes(bucket, key, buffer.toByteArray(), contentType)
                } else {
                    flushPart()
                    client.completeMultipart(bucket, key, id, parts)
                }
            }
            Logx.d("S3Vfs", "commit s3://$bucket/$key ($total bytes, ${parts.size} parts)")
        }

        override suspend fun abort() {
            finished = true
            runCatching {
                val id = uploadId ?: return@runCatching
                withContext(env.dispatchers.vfs) { client.abortMultipart(bucket, key, id) }
            }
        }

        override fun close() = Unit
    }

    class Factory : VfsFactory {
        override val scheme: String get() = "s3"

        override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem =
            S3Vfs(S3Config.from(config, secret), env)
    }

    companion object {
        /** 8 MB 分片（S3 最小 5 MB） */
        private const val PART_SIZE = 8 * 1024 * 1024

        /** 自定义下载域名的直链（播放/分享用） */
        fun directUrl(cfg: S3Config, bucket: String, key: String): String = S3Client.downloadUrl(cfg, bucket, key)
    }
}
