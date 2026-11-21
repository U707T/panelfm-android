package com.u707t.panelfm.core.vfs

/**
 * 全应用统一的资源定位：`scheme://authority/path?query`
 *
 *  - local://emulated/0/Download/a.txt   （authority 表卷标识）
 *  - local://root/system/build.prop
 *  - dav://192.168.28.156:5244/dav/x.zip
 *  - ftp://host:21/pub/a.zip  · sftp://nas:22/home/u/a.txt  · smb://nas/share/x.mkv
 *  - s3://photos/2026/a.jpg              （authority = bucket）
 *  - archive://zip/<encoded host uri>!/inner/path   （压缩包内部）
 *
 * 约定：path 一律使用 '/' 分隔且以 '/' 开头；内部保存解码后的明文路径，协议层负责转义。
 */
data class VfsUri(
    val scheme: String,
    val authority: String,
    val path: String,
    val query: String? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/', "")

    val isRoot: Boolean get() = path.isEmpty() || path == "/"

    val parent: VfsUri?
        get() = if (isRoot) null else copy(path = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" })

    fun child(childName: String): VfsUri =
        copy(path = path.trimEnd('/') + "/" + childName)

    /** 拼接相对路径（以当前为根） */
    fun resolve(relative: String): VfsUri =
        copy(path = (if (isRoot) "" else path.trimEnd('/')) + "/" + relative.trimStart('/'))

    /**
     * 是否在同一挂载点。
     *
     * 同一主机/端口可能同时存在多个连接（不同用户、域或根路径），
     * `?c=<connectionId>` 是连接隔离边界；不能只比较 scheme + authority，
     * 否则「目录自包含检查」和「自身覆盖保护」会把两个账号误当成同一挂载点。
     */
    fun sameMount(other: VfsUri): Boolean {
        if (scheme != other.scheme || authority != other.authority) return false
        val a = VfsUris.connectionId(this)
        val b = VfsUris.connectionId(other)
        return if (a != null || b != null) a == b else true
    }

    fun withPath(newPath: String): VfsUri = copy(path = if (newPath.startsWith("/")) newPath else "/$newPath")

    override fun toString(): String = buildString {
        val encoded = path.any { it == '%' || it == '?' || it == '#' }
        append(scheme).append("://").append(authority).append(if (encoded) encodePath(path) else path)
        query?.let { append('?').append(it) }
        // 只对新版转义串解码：旧记录中 a%20b 是合法的字面文件名，不能自动改成空格。
        if (encoded) append(if (query == null) '?' else '&').append(pathEncodingMarker(encodePath(path)))
    }

    /** 用于 UI 展示的短路径 */
    val displayPath: String get() = if (isRoot) "/" else path

    companion object {
        fun parse(raw: String): VfsUri {
            val s = raw.trim()
            val sep = s.indexOf("://")
            require(sep > 0) { "非法路径：$raw" }
            val scheme = s.substring(0, sep)
            val rest = s.substring(sep + 3)
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            var path = if (slash < 0) "/" else rest.substring(slash)
            var query: String? = null
            val archiveBoundary = if (scheme == "archive") path.indexOf("!/") else -1
            if (archiveBoundary >= 0) {
                val q = path.indexOf('?', 0)
                if (q >= 0 && q < archiveBoundary) {
                    // Legacy form: archive host contains ?c= before the !/ separator.
                    // Keep it inside path so decodeHost() can recover the host URI.
                } else {
                    val innerStart = archiveBoundary + 2
                    val q = path.indexOf('?', innerStart)
                    val tail = if (q >= 0) path.substring(q + 1) else ""
                    // New format marker identifies an encoded path; an inner ? without it is legacy text.
                    if (q >= 0) {
                        val marker = pathEncodingMarker(path.substring(0, q))
                        if (tail == marker || tail.endsWith("&$marker")) {
                            val outerQuery = tail.removeSuffix(if (tail == marker) marker else "&$marker")
                            query = outerQuery.ifEmpty { null }
                            path = decodePath(path.substring(0, q))
                        }
                    }
                }
            } else {
                val q = path.indexOf('?')
                if (q >= 0) {
                    val tail = path.substring(q + 1)
                    path = path.substring(0, q)
                    val marker = pathEncodingMarker(path)
                    if (tail == marker || tail.endsWith("&$marker")) {
                        query = if (tail == marker) null else tail.removeSuffix("&$marker")
                        path = decodePath(path)
                    } else query = tail
                }
            }
            if (!path.startsWith("/")) path = "/$path"
            return VfsUri(scheme, authority, path, query)
        }

        fun of(scheme: String, authority: String, path: String, query: String? = null): VfsUri =
            VfsUri(scheme, authority, if (path.startsWith("/")) path else "/$path", query)

        /** 压缩包内部路径 */
        fun archive(host: VfsUri, inner: String): VfsUri =
            VfsUri("archive", "zip", "/" + encodeHost(host.toString()) + "!/" + inner.trimStart('/'))

        private const val PATH_ENCODING_KEY = "__pf_path_encoding=2-"

        private fun pathEncodingMarker(encodedPath: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encodedPath.toByteArray(Charsets.UTF_8))
            return PATH_ENCODING_KEY + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun encodePath(value: String): String = value.replace("%", "%25")
            .replace("?", "%3F").replace("#", "%23")

        private fun decodePath(value: String): String = Regex("%(25|3[Ff]|23)").replace(value) {
            when (it.value.uppercase()) { "%25" -> "%"; "%3F" -> "?"; else -> "#" }
        }

        fun encodeHost(host: String): String = host.replace("%", "%25")
            .replace("/", "%2F").replace("!", "%21").replace(":", "%3A")
            .replace("?", "%3F").replace("#", "%23").replace("&", "%26").replace("=", "%3D")

        fun decodeHost(encoded: String): String = Regex("%(25|2[Ff]|21|3[Aa]|3[Ff]|23|26|3[Dd])").replace(encoded) {
            when (it.value.uppercase()) {
                "%25" -> "%"; "%2F" -> "/"; "%21" -> "!"; "%3A" -> ":"
                "%3F" -> "?"; "%23" -> "#"; "%26" -> "&"; else -> "="
            }
        }
    }
}
