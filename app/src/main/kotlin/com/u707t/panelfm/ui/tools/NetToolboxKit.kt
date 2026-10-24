package com.u707t.panelfm.ui.tools

import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.TextEncodings

// ================================================================================================
// 网络工具箱 —— 纯逻辑层（不依赖 Compose / Android，可 JVM 单测）
//
// 本文件只放「能算出来的东西」：参数拼装、报文/输出解析、文本渲染。
// 界面状态与协程在 NetToolboxScreen / NetPanes*.kt。
//
// 分工：
//   NetToolboxKit —— Ping 参数与输出统计、HTTP 头与响应体、端口表、HEX dump
//   DnsKit        —— DNS 报文编解码与 UDP 查询
// ================================================================================================

/** Ping 运行中累积的统计（逐行 feed，随时可读）。 */
data class PingStats(
    val transmitted: Int = 0,
    val received: Int = 0,
    val times: List<Double> = emptyList(),
    val statsLineSeen: Boolean = false,
) {
    val lossPercent: Double
        get() = if (transmitted <= 0) 0.0 else (transmitted - received) * 100.0 / transmitted

    val min: Double? get() = times.minOrNull()
    val max: Double? get() = times.maxOrNull()
    val avg: Double? get() = if (times.isEmpty()) null else times.sum() / times.size

    /** 抖动（相邻 RTT 差值的平均绝对值）；样本不足 2 个时 null。 */
    val jitter: Double?
        get() = if (times.size < 2) null else
            times.zipWithNext().sumOf { (a, b) -> kotlin.math.abs(b - a) } / (times.size - 1)

    /** 一行结论（界面上方常驻，不用去翻输出）。 */
    fun summary(): String = when {
        transmitted == 0 && received == 0 -> "尚未收到任何应答"
        else -> buildString {
            append("发 $transmitted / 收 $received")
            append(" · 丢包 ").append(fmt1(lossPercent)).append('%')
            avg?.let { append(" · 平均 ").append(fmt1(it)).append(" ms") }
            min?.let { append(" · 最快 ").append(fmt1(it)).append(" ms") }
            max?.let { append(" · 最慢 ").append(fmt1(it)).append(" ms") }
            jitter?.let { append(" · 抖动 ").append(fmt1(it)).append(" ms") }
        }
    }

    companion object {
        private fun fmt1(v: Double): String {
            val r = kotlin.math.round(v * 10) / 10
            return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        }

        /** 逐行喂入 ping 的 stdout，返回新的统计。纯函数，便于单测。 */
        fun feed(current: PingStats, line: String): PingStats {
            var next = current
            // 1) 单条应答：`64 bytes from 1.2.3.4: icmp_seq=1 ttl=52 time=12.3 ms`
            val timeMatch = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""").find(line)
            if (timeMatch != null && line.contains("bytes from")) {
                val t = timeMatch.groupValues[1].toDoubleOrNull()
                if (t != null) {
                    next = next.copy(
                        received = next.received + 1,
                        transmitted = if (next.transmitted < next.received) next.received else next.transmitted,
                        times = next.times + t,
                    )
                }
            }
            // 2) 统计行：`4 packets transmitted, 4 received, 0% packet loss`
            if (line.contains("packets transmitted") || line.contains("transmitted,")) {
                val m = Regex("""(\d+)\s+packets transmitted""").find(line)
                val r = Regex("""(\d+)\s+(?:packets\s+)?received""").find(line)
                if (m != null) {
                    val tx = m.groupValues[1].toIntOrNull() ?: next.transmitted
                    val rx = r?.groupValues?.get(1)?.toIntOrNull() ?: next.received
                    next = next.copy(transmitted = tx, received = rx, statsLineSeen = true)
                }
            }
            return next
        }
    }
}

object NetToolboxKit {

    /** HTTP 方法全集。 */
    val HttpMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /** Ping 常用目标预设（默认选中第一个）。 */
    val PingPresets = listOf(
        "223.5.5.5",
        "119.29.29.29",
        "114.114.114.114",
        "8.8.8.8",
        "www.baidu.com",
    )

    /** HTTP 常用网址预设（默认选中第一个）。 */
    val HttpPresets = listOf(
        "https://www.baidu.com",
        "https://api.github.com",
        "https://httpbin.org/get",
        "https://cn.bing.com",
    )

    /** 公共 DNS 预设（DNS 查询页的服务器选择）。 */
    val DnsServers = listOf(
        "223.5.5.5" to "阿里 DNS",
        "119.29.29.29" to "腾讯 DNSPod",
        "114.114.114.114" to "114 DNS",
        "8.8.8.8" to "Google",
        "1.1.1.1" to "Cloudflare",
    )

    /**
     * 常用端口表（端口探测页）：`端口 to 说明`。
     * 只收「远程连接 / 调试」真会用到的，不做 1-65535 全扫（手机发热且被网络设备限速）。
     */
    val CommonPorts: List<Pair<Int, String>> = listOf(
        21 to "FTP",
        22 to "SSH / SFTP",
        23 to "Telnet",
        25 to "SMTP",
        53 to "DNS",
        80 to "HTTP",
        110 to "POP3",
        143 to "IMAP",
        443 to "HTTPS",
        445 to "SMB",
        873 to "rsync",
        1080 to "SOCKS",
        1433 to "SQL Server",
        1521 to "Oracle",
        2049 to "NFS",
        2375 to "Docker",
        3306 to "MySQL",
        3389 to "RDP",
        4444 to "常见反弹端口",
        5000 to "Flask / UPnP",
        5432 to "PostgreSQL",
        5900 to "VNC",
        6379 to "Redis",
        7001 to "WebLogic",
        8000 to "常见 HTTP 备用",
        8080 to "HTTP 代理 / Tomcat",
        8443 to "HTTPS 备用",
        8888 to "常见面板",
        9000 to "PHP-FPM / Portainer",
        9090 to "常见面板",
        9200 to "Elasticsearch",
        11211 to "Memcached",
        27017 to "MongoDB",
    )

    // ------------------------------------------------------------------ URL / 请求头

    /** 无协议自动补 http://。 */
    fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "http://$t"
    }

    /**
     * 请求头文本 → 头列表。每行 `key: value`；空行跳过；无冒号的行忽略。
     * 值里的冒号保留（只切第一个冒号）。
     */
    fun parseHeaderLines(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) {
                    null
                } else {
                    val key = line.substring(0, i).trim()
                    if (key.isEmpty()) null else key to line.substring(i + 1).trim()
                }
            }
            .toList()

    /**
     * OkHttp 会**直接抛异常**（而不是忽略）的受限头。用户在「请求头」里手写这些，
     * 旧实现整次请求失败并只回一句「请求失败：…」，看不出是哪个头的问题。
     */
    private val RestrictedHeaders = setOf(
        "host", "content-length", "connection", "transfer-encoding",
        "upgrade", "expect", "keep-alive", "proxy-connection", "te", "trailer",
    )

    /** 头名是否会被 OkHttp 拒绝。 */
    fun isRestrictedHeader(name: String): Boolean = name.trim().lowercase() in RestrictedHeaders

    /**
     * 拆分可用头与被拒头：`(可发送, 被忽略的头名)`。
     * Content-Type 单独交给 RequestBody，也会从这里剔除。
     */
    fun splitHeaders(lines: List<Pair<String, String>>): Pair<List<Pair<String, String>>, List<String>> {
        val accepted = ArrayList<Pair<String, String>>(lines.size)
        val rejected = ArrayList<String>()
        for ((k, v) in lines) {
            when {
                isRestrictedHeader(k) -> rejected += k
                k.equals("Content-Type", ignoreCase = true) -> Unit // 由 RequestBody 携带
                else -> accepted += k to v
            }
        }
        return accepted to rejected
    }

    /** 从请求头文本里取 Content-Type（大小写不敏感）。 */
    fun contentTypeOf(lines: List<Pair<String, String>>): String? =
        lines.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second

    /**
     * 该方法是否「按规范不该带请求体」。
     * 旧实现只挡 GET/HEAD，DELETE 带体其实是合法的；OPTIONS 带体才是少见但允许。
     */
    fun methodAllowsBody(method: String): Boolean = method != "GET" && method != "HEAD"

    // ------------------------------------------------------------------ Ping

    /**
     * ping 命令行（toybox / iputils 通用子集）：`ping [-4|-6] -c 次数 -s 包大小 -w 总超时 目标`。
     * 参数越界时夹取（次数 1–100 / 大小 8–65500 / 超时 1–300 秒）。
     *
     * @param family 4 / 6 时显式指定地址族；null = 交给系统选
     */
    fun buildPingArgs(
        host: String,
        count: Int,
        size: Int,
        timeoutSec: Int,
        family: Int? = null,
    ): List<String> = buildList {
        add("ping")
        when (family) {
            4 -> add("-4")
            6 -> add("-6")
        }
        add("-c"); add(count.coerceIn(1, 100).toString())
        add("-s"); add(size.coerceIn(8, 65500).toString())
        add("-w"); add(timeoutSec.coerceIn(1, 300).toString())
        add(host.trim())
    }

    /**
     * 「总超时」的自动值：旧实现写死 4 秒，用户把次数改成 20 后仍然 4 秒就被 `-w` 掐断，
     * 看起来像「只 ping 了几次」。按「每包 1 秒间隔 + 2 秒余量」推导。
     */
    fun autoDeadlineSec(count: Int): Int = (count.coerceIn(1, 100) + 2).coerceIn(3, 120)

    /** Ping 目标合法性：空、或以 `-` 开头（会被当开关）都不行。 */
    fun isPingTargetValid(raw: String): Boolean {
        val t = raw.trim()
        return t.isNotEmpty() && !t.startsWith("-")
    }

    // ------------------------------------------------------------------ 响应体

    /** 智能解码结果：[charset] 是检测到的编码，[binary] 表示看起来是二进制（该走 HEX 视图）。 */
    data class BodyText(val text: String, val charset: String, val binary: Boolean)

    /**
     * 智能解码：旧实现一律按 UTF-8 硬解，中文 GBK 页面整屏乱码，PNG/zip 直接刷满替换符。
     * 这里先按二进制特征判定，再交给统一的编码识别（UTF-8 / GBK / Big5 / UTF-16…）。
     */
    fun decodeBodySmart(bytes: ByteArray, limit: Int = 256 * 1024): BodyText {
        if (bytes.isEmpty()) return BodyText("", "—", false)
        val head = if (bytes.size <= limit) bytes else bytes.copyOf(limit)
        if (looksBinary(head)) {
            return BodyText(hexDump(head), "二进制", true)
        }
        val decoded = runCatching { TextEncodings.decode(head) }
            .getOrElse { TextEncodings.Decoded("UTF-8", String(head, Charsets.UTF_8)) }
        val tail = if (bytes.size > limit) {
            "\n…（响应体过大，已截断显示前 ${Fmt.size(limit.toLong())}，实际 ${Fmt.size(bytes.size.toLong())}）"
        } else {
            ""
        }
        return BodyText(decoded.text + tail, decoded.charset, false)
    }

    /**
     * 二进制判定：出现 NUL，或控制字符占比超过 5%。
     * 纯文本（含中文）在 UTF-8 / GBK 下都不会有 NUL。
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var ctrl = 0
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            // 允许 \t \n \r \f \b 与 ESC(0x1B，终端色码)
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x08 && b != 0x1B) ctrl++
        }
        return ctrl * 100 / n > 5
    }

    /**
     * 标准 HEX dump：每行 16 字节 = `偏移  4+4 组十六进制  |ASCII|`。
     * 走查表 + StringBuilder（逐字节 `String.format` 在 16KB 上是上万次格式化，会卡一帧）。
     */
    fun hexDump(bytes: ByteArray, limit: Int = 16 * 1024): String {
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder((n / 16 + 2) * 80)
        var i = 0
        while (i < n) {
            val end = minOf(i + 16, n)
            for (shift in 28 downTo 0 step 4) {
                sb.append(HEX_UPPER[(i ushr shift) and 0xF])
            }
            sb.append("  ")
            for (j in 0 until 16) {
                val k = i + j
                if (k < end) {
                    val v = bytes[k].toInt() and 0xFF
                    sb.append(HEX_UPPER[v ushr 4]).append(HEX_UPPER[v and 0xF]).append(' ')
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(' ')
            }
            sb.append('|')
            for (k in i until end) {
                val b = bytes[k].toInt() and 0xFF
                sb.append(if (b in 32..126) b.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (bytes.size > limit) {
            sb.append("…（已截断显示前 ${Fmt.size(limit.toLong())}，实际 ${Fmt.size(bytes.size.toLong())}）")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ JSON 美化

    /**
     * 极简 JSON 缩进（不解析语义，只按括号与引号状态缩进）。
     * 请求调试时把一行压缩 JSON 展开看结构，比来回贴到别的工具快。
     * 非法 JSON（括号不配对 / 字符串未闭合）返回 null，界面提示「不是合法 JSON」。
     */
    fun prettyJson(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.first() != '{' && s.first() != '[') return null
        val out = StringBuilder(s.length * 2)
        var depth = 0
        var inString = false
        var escaped = false
        fun newline() {
            out.append('\n')
            repeat(depth) { out.append("  ") }
        }
        for (c in s) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> { inString = true; out.append(c) }
                '{', '[' -> {
                    out.append(c)
                    depth++
                    newline()
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) return null
                    newline()
                    out.append(c)
                }
                ',' -> { out.append(c); newline() }
                ':' -> out.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> out.append(c)
            }
        }
        if (inString || depth != 0) return null
        return out.toString()
    }

    // ------------------------------------------------------------------ 端口

    /** 解析用户输入的端口列表：支持 `80,443` / `1-1024` / 混写；非法片段忽略。 */
    fun parsePorts(raw: String): List<Int> {
        val out = sortedSetOf<Int>()
        raw.split(',', '，', ' ', '\n').forEach { part ->
            val t = part.trim()
            if (t.isEmpty()) return@forEach
            val dash = t.indexOf('-')
            if (dash > 0) {
                val a = t.substring(0, dash).trim().toIntOrNull()
                val b = t.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null && a in 1..65535 && b in 1..65535) {
                    val from = minOf(a, b)
                    val to = maxOf(a, b)
                    // 单次最多 2048 个端口：再宽就不是「探测」而是全扫了
                    for (p in from..minOf(to, from + 2047)) out += p
                }
            } else {
                t.toIntOrNull()?.takeIf { it in 1..65535 }?.let { out += it }
            }
        }
        return out.toList()
    }

    /** 端口说明（找不到返回 null）。 */
    fun portLabel(port: Int): String? = CommonPorts.firstOrNull { it.first == port }?.second

    /** 结果表格的一行。 */
    data class PortResult(val port: Int, val open: Boolean, val banner: String?, val elapsedMs: Long)

    /** 汇总：`开放 3 / 共 34 · 用时 1.2 s` */
    fun portSummary(results: List<PortResult>, elapsedMs: Long): String {
        val open = results.count { it.open }
        return "开放 $open / 共 ${results.size} · 用时 ${Fmt.duration(elapsedMs)}"
    }
}

/** 十六进制查表：避免逐字节 `String.format`。 */
private const val HEX_UPPER = "0123456789ABCDEF"
