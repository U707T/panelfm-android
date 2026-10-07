package com.u707t.panelfm.ui.connections

/**
 * WebDAV URL ↔ 连接字段互转（复刻 MT 的「URL」单行输入）：
 *  - `http://192.168.1.9:5244/dav` → host=192.168.1.9 / port=5244 / path=/dav / secure=false
 *  - 省略协议按 http；省略端口按协议默认（80 / 443）；IPv6 支持 `[::1]:5244` 写法
 *  - 路径按 URL 解码后存储（请求时由 DavHttp 逐段重新编码）
 */
data class WebDavUrlParts(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val path: String,
)

object WebDavUrl {

    fun parse(raw: String): WebDavUrlParts? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val secure: Boolean
        val rest: String
        when {
            text.startsWith("https://", ignoreCase = true) -> {
                secure = true
                rest = text.substring(8)
            }
            text.startsWith("http://", ignoreCase = true) -> {
                secure = false
                rest = text.substring(7)
            }
            else -> {
                secure = false
                rest = text
            }
        }
        if (rest.isBlank()) return null

        val slash = rest.indexOf('/')
        // host 区也要剥 query / fragment：无路径 URL（如 http://host?x=1）的 ? 之前会并进 host
        val hostPort = (if (slash >= 0) rest.substring(0, slash) else rest)
            .substringBefore('?').substringBefore('#')
        var rawPath = if (slash >= 0) rest.substring(slash) else "/"
        if (hostPort.isBlank() || hostPort.contains(' ')) return null

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            val after = hostPort.substring(close + 1)
            port = when {
                after.isEmpty() -> if (secure) 443 else 80
                after.startsWith(":") -> after.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0 && hostPort.indexOf(':') == colon) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = hostPort
                port = if (secure) 443 else 80
            }
        }
        if (host.isBlank() || port !in 1..65535) return null

        rawPath = rawPath.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(rawPath)
        var path = decoded.ifBlank { "/" }
        if (!path.startsWith("/")) path = "/$path"
        path = path.trimEnd('/').ifEmpty { "/" }
        return WebDavUrlParts(secure, host, port, path)
    }

    /** 由字段生成 URL（用于编辑已有连接时回填输入框） */
    fun build(host: String, port: Int, secure: Boolean, basePath: String): String {
        val scheme = if (secure) "https" else "http"
        // IPv6 字面量必须带方括号再拼端口；parse 存的是剥括号的裸地址，这里必须还原——
        // 否则「编辑-保存」会把 host 落成 "fe80::1:5244" 这类坏值（parse 不再认识）
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$h:$port${basePath.ifBlank { "/" }}"
    }
}
