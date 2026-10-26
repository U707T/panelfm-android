package com.u707t.panelfm.core.common

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网**目标枚举**（网段 / 主机地址）。探测本身在 [PortScan]。
 *
 * 这里刻意不再放 socket 代码：旧 `LanScanner.probe` 用 `BufferedReader.readLine()` 读 banner
 * （无长度上限 + 800ms 超时），与端口面板是同一处隐患，已统一收敛到 [PortScan.probe]。
 */
object LanScanner {

    /** 本机所在网段（取 /24 前缀，如 "192.168.28"）；没有可用网段时返回空表。 */
    fun localPrefixes(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .forEach { nif ->
                    nif.interfaceAddresses.forEach { ia ->
                        val addr = ia.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
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
}
