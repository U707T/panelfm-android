package com.u707t.panelfm.core.vfs.webdav

import android.util.Xml
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** WebDAV 207 Multi-Status 解析（兼容 Alist / Nextcloud / nginx-dav 的命名空间差异）。 */
object DavXml {

    /**
     * @param skipSelf 列目录时跳过「自身」节点（true）；stat 时保留自身（false）
     */
    fun parseMultiStatus(
        parser: XmlPullParser,
        requested: VfsUri,
        basePath: String,
        skipSelf: Boolean = true,
    ): List<FileMetadata> {
        val out = mutableListOf<FileMetadata>()

        var href: String? = null
        var isCollection = false
        var length = -1L
        var modified = -1L
        var etag: String? = null
        var displayName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        href = null; isCollection = false; length = -1L; modified = -1L; etag = null; displayName = null
                    }
                    "status" -> parser.nextText()
                    "href" -> href = parser.nextText()
                    "collection" -> isCollection = true
                    "getcontentlength" -> length = parser.nextText().trim().toLongOrNull() ?: -1L
                    "getlastmodified" -> modified = parseHttpDate(parser.nextText().trim())
                    "getetag" -> etag = parser.nextText().trim().trim('"')
                    "displayname" -> displayName = parser.nextText().ifBlank { null }
                }

                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        val rawPath = href?.let { normalizeHref(it, basePath) }
                        // 目录 href 常带尾斜杠（AList 等）：统一去掉，避免 "/sub/" 与 "/sub" 两套形式导致
                        // 「上一级只跳半格」及把自身目录当成子项列出
                        val path = rawPath?.let { normalizeDirPath(it) }
                        if (path != null && shouldInclude(path, requested.path, skipSelf)) {
                            val name = path.substringAfterLast('/')
                            if (name.isNotEmpty()) {
                                out += FileMetadata(
                                    uri = VfsUri.of(requested.scheme, requested.authority, path),
                                    name = name,
                                    isDirectory = isCollection,
                                    size = if (isCollection) -1L else length,
                                    lastModified = modified,
                                    mimeType = if (isCollection) null else MimeTypes.of(name.substringAfterLast('.', "")),
                                    etag = etag,
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    fun newParser() = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    }

    /** 目录路径规范化：href 尾斜杠统一去掉（根 "/" 保持不变） */
    internal fun normalizeDirPath(path: String): String = if (path.length > 1) path.trimEnd('/') else path

    /** 该节点是否应加入列表：列目录时跳过「自身」；stat 时保留自身 */
    internal fun shouldInclude(path: String, requestedPath: String, skipSelf: Boolean): Boolean =
        !(skipSelf && normalizeDirPath(path) == normalizeDirPath(requestedPath))

    private fun normalizeHref(raw: String, basePath: String): String? {
        val decoded = runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrElse { raw }
        val withoutScheme = when {
            decoded.startsWith("http://") || decoded.startsWith("https://") -> {
                val idx = decoded.indexOf('/', decoded.indexOf("://") + 3)
                if (idx < 0) "/" else decoded.substring(idx)
            }
            else -> decoded
        }
        val base = basePath.trimEnd('/')
        val relative = when {
            base.isEmpty() || base == "/" -> withoutScheme
            withoutScheme.startsWith(base) -> withoutScheme.removePrefix(base)
            else -> withoutScheme
        }
        return relative.ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
    }

    private fun parseHttpDate(text: String): Long = runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching {
            // 部分服务器返回 ISO8601
            ZonedDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        }.getOrDefault(-1L)
    }
}
