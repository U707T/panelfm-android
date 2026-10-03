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

    fun parseMultiStatus(
        parser: XmlPullParser,
        requested: VfsUri,
        basePath: String,
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
                        val path = href?.let { normalizeHref(it, basePath) }
                        if (path != null && path != requested.path) {
                            val name = path.trimEnd('/').substringAfterLast('/')
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
