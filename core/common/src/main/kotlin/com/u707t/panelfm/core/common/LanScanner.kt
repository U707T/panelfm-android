package com.u707t.panelfm.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** 局域网主机扫描（默认找 SSH/SFTP，也可扫 FTP/SMB/WebDAV 端口）。 */
object LanScanner {

    data class Host(val address: String, val port: Int, val banner: String?)

    /** 本机所在网段（取 /24 前缀，如 "192.168.28"）；没有可用网段时返回空表。 */
    fun localPrefixes(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .forEach { nif ->
                    nif.interfaceAddresses.forEach { ia ->
                        val addr = ia.address
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: return@forEach
                            val prefix = host.substringBeforeLast('.', "")
                            if (prefix.isNotEmpty() && prefix != "127.0.0" && prefix !in out) out += prefix
                        }
                    }
                }
        }
        return out
    }

    /** 网段内主机地址（默认 .1 ~ .254） */
    fun hosts(prefix: String, from: Int = 1, to: Int = 254): List<String> =
        (from..to).map { "$prefix.$it" }

    /**
     * 并发 TCP 扫描：命中即通过 [onFound] 回调（边扫边出结果）；每完成一个地址探测回调一次 [onProgress]。
     * [probeBanner] 为 true 时会读第一行（SSH 服务器会回 `SSH-2.0-...`，便于确认协议）。
     */
    suspend fun scan(
        prefixes: List<String>,
        port: Int = 22,
        timeoutMs: Int = 400,
        concurrency: Int = 64,
        probeBanner: Boolean = true,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onFound: suspend (Host) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 256))
        val targets = prefixes.flatMap { hosts(it) }
        val total = targets.size
        val probed = java.util.concurrent.atomic.AtomicInteger(0)
        targets.map { address ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val banner = probe(address, port, timeoutMs, probeBanner)
                    onProgress(probed.incrementAndGet(), total)
                    if (banner != null) onFound(Host(address, port, banner.takeIf { it.isNotBlank() }))
                }
            }
        }.awaitAll()
        Unit
    }

    /** 单机探测：连通返回 banner（可能是空串），失败返回 null */
    suspend fun probe(address: String, port: Int, timeoutMs: Int = 400, readBanner: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    if (!readBanner) return@withContext ""
                    socket.soTimeout = 800
                    runCatching {
                        BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: ""
                    }.getOrDefault("")
                }
            } catch (e: Exception) {
                null
            }
        }
}
