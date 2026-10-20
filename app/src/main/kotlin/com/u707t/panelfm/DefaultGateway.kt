package com.u707t.panelfm

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 读取当前使用的**默认网关（IPv4）**——「主机 / IP」输入框右侧「网关」快捷按钮用。
 *
 * 为什么不用 `WifiManager.getDhcpInfo().gateway`：
 *  - 它只覆盖 Wi-Fi（有线 / 热点 / 网络共享拿不到），还要 ACCESS_WIFI_STATE 权限；
 *  - `getDhcpInfo()` 从 Android 10 起在多数机型上直接返回 0，早已不可靠。
 *
 * 所以走 [ConnectivityManager] 的 [LinkProperties] 路由表（Android 6+ 的官方途径）：
 *  1. **Wi-Fi / 有线网优先**：连了 VPN 时 active network 是 VPN，默认路由会被 VPN 遮住，
 *     直接取 active 会填进 VPN 网关（甚至 0.0.0.0），对局域网连接毫无用处；
 *  2. 再退回其它网络（移动数据等）；
 *  3. 最后尝试 `/proc/net/route`（老 ROM / 少数厂商兜底；Android 10+ 一般已读不到）。
 *
 * 没有默认路由（飞行模式、纯 IPv6 网络等）时返回 null，由调用方给提示。
 */
object DefaultGateway {

    /** 当前默认网关 IPv4 的点分十进制地址；读不到返回 null。 */
    fun ipv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.let { fromRoutes(it) } ?: fromProcNetRoute()
    }

    /** 遍历各网络的默认路由；Wi-Fi / 有线网排在前面（稳定排序，同等优先级仍保持 active 优先）。 */
    private fun fromRoutes(cm: ConnectivityManager): String? {
        val active = cm.activeNetwork
        @Suppress("DEPRECATION") // allNetworks 在 31 起标记废弃，但没有等价的「按优先级列全部网络」API
        val all = cm.allNetworks
        val ordered = buildList {
            active?.let(::add)
            all.forEach { if (it != active) add(it) }
        }
        val lanFirst = ordered.sortedByDescending { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) 1 else 0
        }
        lanFirst.forEach { network ->
            gatewayOf(cm.getLinkProperties(network))?.let { return it }
        }
        return null
    }

    /** 取 LinkProperties 里第一条 IPv4 默认路由的网关；0.0.0.0（VPN 无网关）跳过。 */
    private fun gatewayOf(props: LinkProperties?): String? {
        val routes = props?.routes ?: return null
        for (route in routes) {
            if (!route.isDefaultRoute) continue
            val gateway = route.gateway
            if (gateway is Inet4Address && !gateway.isAnyLocalAddress) {
                return gateway.hostAddress
            }
        }
        return null
    }

    /** 兜底：解析 `/proc/net/route`（Destination 为 00000000 的那一行即默认路由）。 */
    private fun fromProcNetRoute(): String? {
        val routeFile = File("/proc/net/route")
        if (!routeFile.canRead()) return null
        return runCatching {
            routeFile.useLines { lines ->
                lines.drop(1) // 首行是表头 Iface Destination Gateway ...
                    .map { it.trim().split(WHITESPACE) }
                    .firstNotNullOfOrNull { cols ->
                        if (cols.size < 3 || cols[1] != "00000000") null
                        else gatewayFromProcHex(cols[2])
                    }
            }
        }.getOrNull()
    }

    /**
     * `/proc/net/route` 的 Gateway 列（8 位十六进制、**小端序**）→ 点分十进制。
     * 例：`0101A8C0` → `192.168.1.1`；全 0（无网关）返回 null。
     *
     * 抽成 internal 便于单测：字节序搞反不会报错，只会把 1.1.168.192 静默填进输入框。
     */
    internal fun gatewayFromProcHex(hex: String): String? {
        if (hex.length != 8) return null
        val raw = hex.toLongOrNull(16)?.toInt() ?: return null
        if (raw == 0) return null
        val bytes = ByteArray(4) { i -> (raw ushr (i * 8)).toByte() }
        return InetAddress.getByAddress(bytes).hostAddress
    }

    private val WHITESPACE = Regex("\\s+")
}
