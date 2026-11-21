package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import com.u707t.panelfm.core.vfs.local.LocalVfs
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only shares use a revocable, per-start capability URL and one selected interface. */
class RemoteHttpServer(
    private val locator: VfsLocator,
    private val protectedRoots: List<File> = emptyList(),
    private val bindAddress: () -> InetAddress = { lanAddress() },
) {
    private class Client(val socket: Socket) {
        val job = Job()
        val acceptedAt = System.nanoTime()
        @Volatile var progressAt = acceptedAt
        @Volatile var headersRead = false
        @Volatile var reader: VfsReader? = null
        fun revoke() {
            job.cancel()
            runCatching { socket.close() }
            runCatching { reader?.close() }
        }
    }
    private class Share(val socket: ServerSocket, val root: VfsUri, val token: String) {
        @Volatile var active = true
        val clients = ConcurrentHashMap<Socket, Client>()
        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "panelfm-remote-timeouts").apply { isDaemon = true }
        }
    }
    @Volatile private var share: Share? = null
    @Volatile private var currentUrl = ""
    val running: Boolean get() = share?.active == true
    val url: String get() = currentUrl
    val servedRoot: VfsUri? get() = share?.root

    @Synchronized
    fun start(root: VfsUri): String? {
        if (running) return currentUrl
        var socket: ServerSocket? = null
        return runCatching {
            val vfs = locator.find(root) ?: error("会话不可用")
            require(root.scheme != "local" || root.authority !in setOf("app", "root")) {
                "应用私有目录和系统根目录不能用于远程分享"
            }
            require(vfs is LocalVfs || !vfs.capabilities.symlinks) {
                "此存储无法验证符号链接边界，请先复制到本地再分享"
            }
            if (vfs is LocalVfs) checkedLocalSharePath(vfs, root, root, protectedRoots)
            val address = bindAddress()
            socket = ServerSocket().apply { bind(InetSocketAddress(address, 0)); soTimeout = 1000 }
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val session = Share(socket!!, root, token)
            share = session
            currentUrl = "http://${address.hostAddress}:${socket!!.localPort}/$token/"
            session.timer.scheduleAtFixedRate({
                val now = System.nanoTime()
                session.clients.values.forEach { client ->
                    val headersExpired = !client.headersRead && now - client.acceptedAt > TimeUnit.SECONDS.toNanos(10)
                    val stalled = now - client.progressAt > TimeUnit.SECONDS.toNanos(30)
                    if (headersExpired || stalled) client.revoke()
                }
            }, 1, 1, TimeUnit.SECONDS)
            Thread({ serve(session) }, "panelfm-remote").apply { isDaemon = true; start() }
            // Capability URLs must never be written to logs.
            Logx.i("RemoteHttp", "read-only share started")
            currentUrl
        }.getOrElse {
            runCatching { socket?.close() }
            share?.let { session -> session.active = false; session.timer.shutdownNow() }
            share = null
            currentUrl = ""
            Logx.w("RemoteHttp", "start failed: ${it.message}")
            null
        }
    }

    @Synchronized
    fun stop() {
        val old = share ?: return
        old.active = false
        share = null
        currentUrl = ""
        runCatching { old.socket.close() }
        old.clients.values.forEach { it.revoke() }
        old.timer.shutdownNow()
    }

    private fun serve(session: Share) {
        while (session.active) {
            val socket = runCatching { session.socket.accept() }.getOrNull() ?: continue
            val client = Client(socket)
            synchronized(this) {
                if (share !== session || !session.active || session.clients.size >= MAX_CLIENTS) {
                    socket.close()
                    return@synchronized
                }
                session.clients[socket] = client
            }
            if (socket.isClosed) continue
            Thread({
                try {
                    socket.soTimeout = 10_000
                    handle(session, client)
                } catch (_: Exception) {
                    // Malformed, cancelled and timed-out clients have no user-visible side effects.
                } finally {
                    client.revoke()
                    session.clients.remove(socket)
                }
            }, "panelfm-remote-conn").apply { isDaemon = true; start() }
        }
    }

    private fun requireActive(session: Share, client: Client) {
        if (!session.active || share !== session || client.socket.isClosed) throw IOException("Share stopped")
    }

    private fun handle(session: Share, client: Client) {
        val input = BufferedInputStream(client.socket.getInputStream())
        val out = BufferedOutputStream(client.socket.getOutputStream())
        val request = readRemoteLine(input, 4096) ?: return
        val parts = request.split(' ')
        if (parts.size != 3 || parts[0] != "GET" || !parts[2].startsWith("HTTP/1.")) {
            respond(out, 405, "text/plain", ByteArray(0)); return
        }
        var total = request.length
        var complete = false
        repeat(100) {
            if (!complete) {
                val line = readRemoteLine(input, 4096) ?: throw IOException("Incomplete headers")
                total += line.length + 2
                if (total > 16 * 1024) throw IOException("Headers too large")
                if (line.isEmpty()) complete = true
            }
        }
        if (!complete) throw IOException("Too many headers")
        client.headersRead = true
        client.progressAt = System.nanoTime()
        requireActive(session, client)
        val path = authorizedRemotePath(parts[1], session.token)
        if (path == null) { respond(out, 403, "text/plain", ByteArray(0)); return }
        val target = if (path == "/") session.root else session.root.resolve(path.trimStart('/'))
        val vfs = locator.find(target) ?: return
        val meta = runBlocking(client.job) {
            checkBoundary(session, vfs, target)
            vfs.stat(target)
        }
        requireActive(session, client)
        if (meta.isSymlink) { respond(out, 403, "text/plain", ByteArray(0)); return }
        if (meta.isDirectory) {
            val items = runBlocking(client.job) { vfs.list(target) }
            requireActive(session, client)
            val html = buildString {
                append("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>PanelFM</title>")
                append("<h3>PanelFM · ${escapeHtml(path)}</h3>")
                if (path != "/") {
                    val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
                    append("<p><a href=\"/${session.token}${urlEncodePath(parent)}\">..</a></p>")
                }
                items.filterNot { it.isSymlink }.forEach { item ->
                    val relative = path.trimEnd('/') + "/" + item.name + if (item.isDirectory) "/" else ""
                    append("<p><a href=\"/${session.token}${escapeHtml(urlEncodePath(relative))}\">${escapeHtml(item.name)}</a>")
                    if (!item.isDirectory) append(" · ${Fmt.size(item.size)}")
                    append("</p>")
                }
            }
            respond(out, 200, "text/html; charset=utf-8", html.toByteArray())
        } else {
            val reader = runBlocking(client.job) {
                checkBoundary(session, vfs, target)
                vfs.openRead(target)
            }
            client.reader = reader
            try {
                requireActive(session, client)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n".toByteArray())
                if (meta.size >= 0) out.write("Content-Length: ${meta.size}\r\n".toByteArray())
                out.write("Content-Disposition: attachment\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n".toByteArray())
                val buffer = ByteArray(64 * 1024)
                var emptyReads = 0
                while (true) {
                    requireActive(session, client)
                    val n = runBlocking(client.job) { reader.read(buffer, 0, buffer.size) }
                    if (n < 0) break
                    if (n == 0) {
                        if (++emptyReads >= 3) throw IOException("Reader made no progress")
                        continue
                    }
                    emptyReads = 0
                    requireActive(session, client)
                    out.write(buffer, 0, n)
                    out.flush()
                    client.progressAt = System.nanoTime()
                }
            } finally {
                client.reader = null
                runCatching { reader.close() }
            }
        }
    }

    private suspend fun checkBoundary(session: Share, vfs: VirtualFileSystem, target: VfsUri) {
        if (vfs is LocalVfs) {
            checkedLocalSharePath(vfs, session.root, target, protectedRoots)
        } else {
            require(!vfs.capabilities.symlinks) { "Unverifiable remote symlink boundary" }
            var current = session.root
            require(!vfs.stat(current).isSymlink)
            val relative = target.path.removePrefix(session.root.path.trimEnd('/')).trim('/')
            for (part in relative.split('/').filter { it.isNotEmpty() }) {
                current = current.child(part)
                require(!vfs.stat(current).isSymlink)
            }
        }
    }

    private fun respond(out: BufferedOutputStream, code: Int, mime: String, bytes: ByteArray) {
        out.write(("HTTP/1.1 $code\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private companion object {
        const val MAX_CLIENTS = 8
        fun lanAddress(): InetAddress = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        }.getOrNull() ?: InetAddress.getLoopbackAddress()
    }
}

internal fun checkedLocalSharePath(vfs: LocalVfs, root: VfsUri, target: VfsUri, protectedRoots: List<File>) {
    val base = File(vfs.absolutePath(root)).toPath().toRealPath()
    val candidate = File(vfs.absolutePath(target)).toPath()
    val real = candidate.toRealPath()
    require(real.startsWith(base)) { "Path outside share" }
    require(protectedRoots.none { real.startsWith(it.canonicalFile.toPath()) || base.startsWith(it.canonicalFile.toPath()) }) {
        "Private application data cannot be shared"
    }
    var cursor = candidate
    while (cursor != null && cursor != base) {
        require(!Files.isSymbolicLink(cursor)) { "Symbolic links cannot be shared" }
        cursor = cursor.parent
    }
}

internal fun readRemoteLine(input: java.io.InputStream, maxBytes: Int): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) return if (bytes.size() == 0) null else throw IOException("Incomplete HTTP line")
        if (b == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
        if (bytes.size() >= maxBytes) throw IOException("HTTP line too long")
        bytes.write(b)
    }
}

internal fun authorizedRemotePath(requestTarget: String, token: String): String? {
    val raw = requestTarget.substringBefore('?')
    if (!raw.startsWith('/')) return null
    val supplied = raw.removePrefix("/").substringBefore('/')
    if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) return null
    if (raw != "/$token" && !raw.startsWith("/$token/")) return null
    return normalizeRemotePath(decodeRemotePath(raw.removePrefix("/$token").ifEmpty { "/" }))
}

internal fun decodeRemotePath(raw: String): String =
    runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)

internal fun normalizeRemotePath(raw: String): String? {
    if (raw.any { it == '\u0000' || it == '\\' || it.code < 32 }) return null
    val segments = ArrayDeque<String>()
    for (seg in raw.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeLast()
            else -> segments.addLast(seg)
        }
    }
    return "/" + segments.joinToString("/")
}

private fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun urlEncodePath(path: String): String = path.split('/').joinToString("/") {
    java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
}
