package com.u707t.panelfm.core.vfs.s3

import android.util.Xml
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 自研 S3 客户端（OkHttp + SigV4）。
 *  - path-style / virtual-host style 都支持；R2 / COS / OSS / MinIO / 七牛 兼容
 *  - 列表分页（continuation-token）、Range 读、Multipart 上传（边传边分片）
 *  - 自定义下载域名：下载与播放可直接走它（不签名）
 */
class S3Client(private val cfg: S3Config) {

    data class Entry(
        val key: String,
        val size: Long,
        val lastModified: Long,
        val etag: String?,
        val isPrefix: Boolean,
    )

    data class ListResult(val entries: List<Entry>, val nextToken: String?, val truncated: Boolean)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .apply {
            // 自签 HTTPS（自建 MinIO / 私有 S3 网关）必须显式放开校验。
            // 旧实现这里完全没有 TLS 配置，导致「信任自签证书」开关对 S3 静默无效、
            // 连接必然握手失败。仅当用户对该连接开启该选项时才生效。
            if (cfg.secure && cfg.trustSelfSigned) {
                sslSocketFactory(
                    com.u707t.panelfm.core.vfs.TlsTrust.socketFactory,
                    com.u707t.panelfm.core.vfs.TlsTrust.trustManager,
                )
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    // ------------------------------------------------------------------ URL 构造

    private fun baseUrl(bucket: String?): HttpUrl {
        val base = cfg.endpoint.toHttpUrlOrNull() ?: throw VfsException.ProtocolError("非法的端点：${cfg.endpoint}")
        val useVirtualHost = !cfg.pathStyle && bucket != null
        return if (useVirtualHost) {
            base.newBuilder().host("$bucket.${base.host}").build()
        } else {
            base
        }
    }

    /** 请求 URL 构造。`internal` 是为了让 `S3SigningPathTest` 能断言「发送路径 == 签名路径」。 */
    internal fun url(bucket: String?, key: String?, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl(bucket).newBuilder()
        if (cfg.pathStyle && bucket != null) builder.addPathSegment(bucket)
        // key 必须**逐段预编码**后交给 addEncodedPathSegment：
        //  - `addPathSegment` 会把 `+` 当普通字符**原样留在路径里**（OkHttp 认为它安全），
        //    而 SigV4 的 canonical URI 要求 `+` → `%2B`。两边不一致 → SignatureDoesNotMatch。
        //  - 用我们的 `uriEncode` 先编码（只保留 unreserved：A-Za-z0-9-._~），
        //    路径就与签名用的 canonical URI **逐字节一致**（AWS SDK 也是这么发的）。
        key?.takeIf { it.isNotEmpty() }?.split('/')?.forEach { builder.addEncodedPathSegment(SigV4.uriEncode(it)) }
        query.forEach { (k, v) -> if (v == null) builder.addQueryParameter(k, "") else builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun hostHeader(bucket: String?): String {
        val base = cfg.endpoint.toHttpUrlOrNull() ?: throw VfsException.ProtocolError("非法的端点")
        val host = if (!cfg.pathStyle && bucket != null) "$bucket.${base.host}" else base.host
        return if (base.port != HttpUrl.defaultPort(base.scheme)) "$host:${base.port}" else host
    }

    // ------------------------------------------------------------------ 请求发送

    private suspend fun execute(
        method: String,
        bucket: String?,
        key: String?,
        query: Map<String, String?> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        payloadHash: String = SigV4.EMPTY_SHA256,
    ): Response = withContext(Dispatchers.IO) {
        val target = url(bucket, key, query)
        // 签名用「未编码」路径：SigV4 内部会自己逐段编码，避免二次编码。
        val signed = SigV4.sign(
            method = method,
            host = hostHeader(bucket),
            path = signingPathOf(target.encodedPath),
            query = query,
            headers = extraHeaders,
            payloadHash = payloadHash,
            accessKey = cfg.accessKey,
            secretKey = cfg.secretKey,
            region = cfg.region,
        )
        val builder = Request.Builder().url(target)
            .header("Authorization", signed.authorization)
            .header("x-amz-date", signed.amzDate)
            .header("x-amz-content-sha256", payloadHash)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            else -> builder.method(method, body)
        }
        try {
            http.newCall(builder.build()).execute()
        } catch (e: UnknownHostException) {
            throw VfsException.Network(VfsException.Network.Kind.DNS, "域名解析失败：${target.host}", e)
        } catch (e: SocketTimeoutException) {
            throw VfsException.Network(VfsException.Network.Kind.TIMEOUT, "连接超时：${target.host}", e)
        } catch (e: IOException) {
            throw VfsException.Network(VfsException.Network.Kind.UNREACHABLE, e.message ?: "网络错误", e)
        }
    }

    private fun check(r: Response, key: String?): Response {
        if (r.isSuccessful) return r
        val code = r.code
        val msg = runCatching { r.body.string() }.getOrDefault("").take(300)
        r.close()
        throw when (code) {
            403 -> VfsException.Auth("S3 拒绝访问（403）：检查 AK/SK、桶权限与区域", 403)
            404 -> VfsException.NotFound(VfsUri_s3(key))
            301, 307 -> VfsException.ProtocolError("区域/端点不匹配（$code）：请在连接设置里填写正确的 Region")
            409 -> VfsException.Conflict(VfsUri_s3(key))
            else -> VfsException.ProtocolError("S3 HTTP $code：$msg")
        }
    }

    private fun VfsUri_s3(key: String?) = com.u707t.panelfm.core.vfs.VfsUri.of("s3", "unknown", "/" + (key ?: ""))

    // ------------------------------------------------------------------ 操作

    suspend fun listBuckets(): List<String> {
        val resp = execute("GET", null, null)
        return resp.use { r ->
            check(r, null)
            val names = ArrayList<String>()
            val parser = newParser(r.body.byteStream())
            var event = parser.eventType
            var currentTag: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name.lowercase()
                    XmlPullParser.TEXT -> if (currentTag == "name") names.add(parser.text.trim())
                    XmlPullParser.END_TAG -> if (parser.name.lowercase() == "bucket") currentTag = null
                }
                event = parser.next()
            }
            names.filter { it.isNotEmpty() }
        }
    }

    suspend fun listObjects(
        bucket: String,
        prefix: String,
        delimiter: String? = "/",
        continuationToken: String? = null,
        maxKeys: Int = 1000,
    ): ListResult {
        val query = mutableMapOf<String, String?>(
            "list-type" to "2",
            "max-keys" to maxKeys.toString(),
        )
        if (prefix.isNotEmpty()) query["prefix"] = prefix
        if (delimiter != null) query["delimiter"] = delimiter
        continuationToken?.let { query["continuation-token"] = it }

        val resp = execute("GET", bucket, null, query)
        return resp.use { r ->
            check(r, prefix)
            val entries = ArrayList<Entry>()
            var nextToken: String? = null
            var truncated = false
            val parser = newParser(r.body.byteStream())
            var event = parser.eventType
            var tag = ""
            var key = ""
            var size = 0L
            var modified = -1L
            var etag: String? = null
            var inContents = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name.lowercase()
                        if (tag == "contents") {
                            inContents = true; key = ""; size = 0L; modified = -1L; etag = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text
                        if (inContents) {
                            when (tag) {
                                "key" -> key += text
                                "size" -> size = text.trim().toLongOrNull() ?: 0L
                                "lastmodified" -> modified = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrDefault(-1L)
                                "etag" -> etag = text.trim().trim('"')
                            }
                        } else {
                            when (tag) {
                                "prefix" -> if (text.isNotBlank() && (key.isEmpty())) key += text
                                "nextcontinuationtoken" -> nextToken = text.trim()
                                "istruncated" -> truncated = text.trim().equals("true", ignoreCase = true)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                        "contents" -> {
                            inContents = false
                            if (key.isNotEmpty()) entries.add(Entry(key, size, modified, etag, isPrefix = false))
                        }
                        "commonprefixes" -> {
                            if (key.isNotEmpty()) entries.add(Entry(key, -1, -1, null, isPrefix = true))
                            key = ""
                        }
                    }
                }
                event = parser.next()
            }
            ListResult(entries, nextToken, truncated)
        }
    }

    suspend fun head(bucket: String, key: String): Entry? {
        val resp = execute("HEAD", bucket, key)
        resp.use { r ->
            if (r.code == 404) return null
            check(r, key)
            return Entry(
                key = key,
                size = r.header("Content-Length")?.toLongOrNull() ?: -1L,
                lastModified = r.header("Last-Modified")?.let { gm ->
                    runCatching { java.time.ZonedDateTime.parse(gm, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(-1L)
                } ?: -1L,
                etag = r.header("ETag")?.trim('"'),
                isPrefix = false,
            )
        }
    }

    suspend fun openGet(bucket: String, key: String, offset: Long = 0L): Response {
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
        val r = execute("GET", bucket, key, extraHeaders = headers)
        return check(r, key)
    }

    suspend fun getBytes(bucket: String, key: String, offset: Long = 0L, length: Long = -1L): ByteArray {
        val headers = when {
            offset > 0 && length > 0 -> mapOf("Range" to "bytes=$offset-${offset + length - 1}")
            offset > 0 -> mapOf("Range" to "bytes=$offset-")
            length > 0 -> mapOf("Range" to "bytes=0-${length - 1}")
            else -> emptyMap()
        }
        val r = execute("GET", bucket, key, extraHeaders = headers)
        return r.use { resp ->
            check(resp, key)
            resp.body.bytes()
        }
    }

    suspend fun putBytes(bucket: String, key: String, bytes: ByteArray, contentType: String) {
        val body = bytes.toRequestBody(contentType.toMediaType())
        val r = execute(
            "PUT", bucket, key,
            extraHeaders = mapOf("Content-Type" to contentType),
            body = body,
            payloadHash = SigV4.sha256Hex(bytes),
        )
        r.use { check(it, key) }
    }

    suspend fun deleteObject(bucket: String, key: String) {
        val r = execute("DELETE", bucket, key)
        r.use { resp ->
            if (resp.code != 404) check(resp, key)
        }
    }

    suspend fun copyObject(bucket: String, fromKey: String, toKey: String) {
        val r = execute(
            "PUT", bucket, toKey,
            extraHeaders = mapOf("x-amz-copy-source" to "/$bucket/${SigV4.uriEncode(fromKey, encodeSlash = false)}"),
            body = ByteArray(0).toRequestBody(null),
        )
        r.use { resp ->
            check(resp, toKey)
            val text = runCatching { resp.body.string() }.getOrDefault("")
            if (text.contains("<Error>")) throw VfsException.ProtocolError("CopyObject 失败：${text.take(200)}")
        }
    }

    // ---- Multipart

    suspend fun createMultipart(bucket: String, key: String, contentType: String): String {
        val r = execute(
            "POST", bucket, key,
            query = mapOf("uploads" to null),
            extraHeaders = mapOf("Content-Type" to contentType),
            body = ByteArray(0).toRequestBody(null),
        )
        return r.use { resp ->
            check(resp, key)
            val text = resp.body.string()
            Regex("<UploadId>([^<]+)</UploadId>").find(text)?.groupValues?.get(1)
                ?: throw VfsException.ProtocolError("无法解析 UploadId：${text.take(200)}")
        }
    }

    suspend fun uploadPart(bucket: String, key: String, uploadId: String, partNumber: Int, bytes: ByteArray): String {
        val r = execute(
            "PUT", bucket, key,
            query = mapOf("partNumber" to partNumber.toString(), "uploadId" to uploadId),
            body = bytes.toRequestBody("application/octet-stream".toMediaType()),
            payloadHash = SigV4.sha256Hex(bytes),
        )
        return r.use { resp ->
            check(resp, key)
            resp.header("ETag")?.trim('"') ?: throw VfsException.ProtocolError("分片上传缺少 ETag（part $partNumber）")
        }
    }

    suspend fun completeMultipart(bucket: String, key: String, uploadId: String, parts: List<Pair<Int, String>>) {
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            parts.forEach { (n, etag) ->
                append("<Part><PartNumber>").append(n).append("</PartNumber><ETag>\"").append(etag).append("\"</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }
        val body = xml.toRequestBody("application/xml".toMediaType())
        val r = execute("POST", bucket, key, query = mapOf("uploadId" to uploadId), body = body)
        r.use { resp ->
            check(resp, key)
            val text = runCatching { resp.body.string() }.getOrDefault("")
            if (text.contains("<Error>")) throw VfsException.ProtocolError("合并分片失败：${text.take(200)}")
        }
    }

    suspend fun abortMultipart(bucket: String, key: String, uploadId: String) {
        runCatching {
            val r = execute("DELETE", bucket, key, query = mapOf("uploadId" to uploadId))
            r.use { check(it, key) }
        }
    }

    fun close() {
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    private fun newParser(input: java.io.InputStream) = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    companion object {
        fun mediaTypeOf(contentType: String): MediaType? = runCatching { contentType.toMediaType() }.getOrNull()

        /**
         * 由「真实发送的编码路径」反推签名用的**未编码**路径（[SigV4] 会重新逐段编码）。
         *
         * 每段必须先把手写的 `+` 换成 `%2B` 再解码：`URLDecoder` 是表单（`x-www-form-urlencoded`）
         * 语义，默认把 `'+'` 当成空格 —— 含 `+` 的对象 key（`a+b.txt`）会被解成 `a b.txt`，
         * 于是 canonical URI 与真实请求不同，服务器回 `SignatureDoesNotMatch`。
         * 同样的写法在 WebDAV 侧已有先例（`DavXml.normalizeHref`）。
         */
        internal fun signingPathOf(encodedPath: String): String =
            "/" + encodedPath.trimStart('/').split('/')
                .joinToString("/") { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }

        /** 自定义下载域名优先：播放/下载走它（不签名） */
        fun downloadUrl(cfg: S3Config, bucket: String, key: String): String {
            val domain = cfg.downloadDomain ?: return ""
            val base = if (domain.startsWith("http")) domain else "https://$domain"
            return base.trimEnd('/') + "/" + bucket + "/" + key.split('/').joinToString("/") { SigV4.uriEncode(it) }
        }
    }
}
