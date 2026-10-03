package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 远程管理（内置只读 HTTP 服务）：电脑浏览器访问手机目录，直接浏览与下载。
 * 走统一 VFS，所以本地 / SFTP / WebDAV / S3 目录都能通过它暴露到局域网（默认只读，更安全）。
 */
class RemoteHttpServer(private val locator: VfsLocator) {

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    val running: Boolean get() = serverSocket?.isClosed == false

    /** 启动服务，返回可访问的 URL；失败返回 null */
    fun start(root: VfsUri): String? {
        if (running) return currentUrl
        return runCatching {
            val socket = ServerSocket(0)
            serverSocket = socket
            thread = Thread({ serve(socket, root) }, "panelfm-remote").apply {
                isDaemon = true
                start()
            }
            val ip = lanAddress() ?: "127.0.0.1"
            currentUrl = "http://$ip:${socket.localPort}/"
            Logx.i("RemoteHttp", "serving $root at $currentUrl")
            currentUrl
        }.getOrElse {
            Logx.e("RemoteHttp", "start failed: ${it.message}", it)
            null
        }
    }

    private var currentUrl: String = ""

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        thread = null
        currentUrl = ""
    }

    private fun serve(socket: ServerSocket, root: VfsUri) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { handle(client, root) }
                .onFailure { Logx.w("RemoteHttp", "handle failed: ${it.message}") }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket, root: VfsUri) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val rawPath = parts[1].substringBefore('?')
        // 读掉请求头
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val out = BufferedOutputStream(client.getOutputStream())

        val target = root.withPath(if (path == "/") "" else path)
        val vfs = locator.find(target)
        if (vfs == null) {
            respond(out, 404, "text/plain", "会话不可用（该目录的存储已断开）".toByteArray())
            return
        }

        val meta = runBlocking { runCatching { vfs.stat(target) }.getOrNull() }
        if (meta == null) {
            respond(out, 404, "text/plain", "404 未找到：$path".toByteArray())
            return
        }

        if (meta.isDirectory) {
            val items = runBlocking { runCatching { vfs.list(target) }.getOrDefault(emptyList()) }
            val html = buildString {
                append("<!doctype html><html><head><meta charset=\"utf-8\"><title>PanelFM</title>")
                append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                append("<style>body{font-family:system-ui;margin:24px}a{display:block;padding:6px 0;text-decoration:none;color:#1a73e8}span{color:#888;margin-left:8px}</style>")
                append("</head><body><h3>PanelFM · ${root.displayPath}${path}</h3>")
                if (path != "/") append("<a href=\"${parentOf(path)}\">..</a>")
                items.forEach { item ->
                    val href = (if (path.endsWith("/")) path else "$path/") + item.name + if (item.isDirectory) "/" else ""
                    append("<a href=\"$href\">${if (item.isDirectory) "📁" else "📄"} ${item.name}")
                    if (!item.isDirectory) append("<span>${Fmt.size(item.size)}</span>")
                    append("</a>")
                }
                append("<p style=\"color:#888;font-size:12px\">由 PanelFM 提供（只读）</p></body></html>")
            }
            respond(out, 200, "text/html; charset=utf-8", html.toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: ${meta.mimeType ?: "application/octet-stream"}\r\n".toByteArray())
            if (meta.size > 0) out.write("Content-Length: ${meta.size}\r\n".toByteArray())
            out.write("Content-Disposition: attachment; filename=\"${meta.name}\"\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.flush()
            val stream = runBlocking { vfs.openRead(target) }
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = runBlocking { stream.read(buffer, 0, buffer.size) }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } finally {
                runBlocking { runCatching { stream.close() } }
            }
            out.flush()
        }
    }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx + 1)
    }

    private fun respond(out: BufferedOutputStream, code: Int, contentType: String, body: ByteArray) {
        out.write("HTTP/1.1 $code\r\n".toByteArray())
        out.write("Content-Type: $contentType\r\n".toByteArray())
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
