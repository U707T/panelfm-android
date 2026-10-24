package com.u707t.panelfm.ui.tools

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 极简 DNS 客户端（UDP，RFC 1035）：自己拼查询报文、自己解响应。
 *
 * 为什么不用 `InetAddress.getAllByName`：
 *  - 它只能拿 A / AAAA，拿不到 TTL / CNAME / MX / TXT / NS / SOA；
 *  - 无法指定查询服务器（排查「本地 DNS 污染 / 内部 DNS 记录」时是刚需）；
 *  - 分不清「域名不存在（NXDOMAIN）」与「超时」—— 这两者的处置方式完全不同。
 *
 * 除 [query] 的 socket 部分外都是纯函数，[buildQuery] / [parseReply] 可单测。
 */
object DnsKit {

    private const val TYPE_A = 1
    private const val TYPE_NS = 2
    private const val TYPE_CNAME = 5
    private const val TYPE_SOA = 6
    private const val TYPE_PTR = 12
    private const val TYPE_MX = 15
    private const val TYPE_TXT = 16
    private const val TYPE_AAAA = 28
    private const val TYPE_SRV = 33
    private const val TYPE_CAA = 257

    private const val HEX = "0123456789ABCDEF"

    /** 支持的记录类型（顺序即界面 chip 顺序）。 */
    enum class RType(val code: Int, val label: String, val hint: String) {
        A(TYPE_A, "A", "IPv4 地址"),
        AAAA(TYPE_AAAA, "AAAA", "IPv6 地址"),
        CNAME(TYPE_CNAME, "CNAME", "别名指向"),
        MX(TYPE_MX, "MX", "邮件服务器"),
        TXT(TYPE_TXT, "TXT", "文本 / SPF / 验证"),
        NS(TYPE_NS, "NS", "权威服务器"),
        SOA(TYPE_SOA, "SOA", "授权起始"),
        PTR(TYPE_PTR, "PTR", "反向解析（填 IP）"),
        SRV(TYPE_SRV, "SRV", "服务定位"),
        CAA(TYPE_CAA, "CAA", "证书签发授权"),
    }

    /** 一条资源记录。 */
    data class Record(val name: String, val type: String, val ttl: Long, val value: String)

    /** 一次应答。 */
    data class Reply(
        val id: Int,
        val rcode: Int,
        val truncated: Boolean,
        val answers: List<Record>,
        val authorities: List<Record>,
        val additionals: List<Record>,
    ) {
        val ok: Boolean get() = rcode == 0

        /** 人类可读的响应码说明。 */
        fun rcodeText(): String = when (rcode) {
            0 -> "NOERROR"
            1 -> "FORMERR（服务器认为报文格式错误）"
            2 -> "SERVFAIL（服务器处理失败）"
            3 -> "NXDOMAIN（该域名/记录不存在）"
            4 -> "NOTIMP（服务器不支持此查询）"
            5 -> "REFUSED（服务器拒绝回答）"
            else -> "RCODE $rcode"
        }
    }

    /** 查询失败（网络层）。 */
    class DnsException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------------ 编码

    /**
     * 拼查询报文。域名按 `label 长度 + 字节` 序列编码，末尾 0；
     * 单标签超过 63 字节或总长超过 253 字节直接抛 [IllegalArgumentException]（RFC 限制）。
     */
    fun buildQuery(host: String, type: RType, id: Int, recursionDesired: Boolean = true): ByteArray {
        val name = normalizeQueryName(host)
        require(name.isNotEmpty()) { "查询域名为空" }
        val out = ByteArrayOutputStream(64 + name.length)
        fun u16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        u16(id and 0xFFFF)
        // QR=0 / OPCODE=0 / RD / RA=0 / Z=0 / RCODE=0
        u16(if (recursionDesired) 0x0100 else 0x0000)
        u16(1) // QDCOUNT
        u16(0) // ANCOUNT
        u16(0) // NSCOUNT
        u16(0) // ARCOUNT
        var total = 0
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.ISO_8859_1)
            require(bytes.size in 1..63) { "域名标签长度非法：$label" }
            total += bytes.size + 1
            out.write(bytes.size)
            out.write(bytes)
        }
        require(total <= 253) { "域名过长（>253 字节）" }
        out.write(0)
        u16(type.code)
        u16(1) // CLASS = IN
        return out.toByteArray()
    }

    /** 去掉尾部点、去掉 `http://` 之类前缀；PTR 查询把 IPv4 自动转成 `x.x.x.x.in-addr.arpa`。 */
    fun normalizeQueryName(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        if (t.contains("://")) t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.trim().trimEnd('.')
        // IPv4 反向解析：自动补 in-addr.arpa（用户直接填 IP 时最常见的误用）
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(t)) {
            return t.split('.').reversed().joinToString(".") + ".in-addr.arpa"
        }
        return t
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解析应答报文。
     *
     * 全程做越界保护：截断 / 畸形报文返回「已解析到的部分」而不是抛异常 ——
     * 排查现场时半份结果比一个堆栈有用。ID / RCODE / TC 一定可读（前 12 字节是固定头）。
     */
    fun parseReply(buf: ByteArray): Reply {
        require(buf.size >= 12) { "报文短于 12 字节，不是 DNS 响应" }
        val id = u16(buf, 0)
        val flags = u16(buf, 2)
        val qd = u16(buf, 4)
        val an = u16(buf, 6)
        val ns = u16(buf, 8)
        val ar = u16(buf, 10)
        val rcode = flags and 0x000F
        val truncated = (flags and 0x0200) != 0

        var pos = 12
        repeat(qd) {
            if (pos + 2 > buf.size) return Reply(id, rcode, truncated, emptyList(), emptyList(), emptyList())
            val (_, afterName) = readName(buf, pos)
            pos = afterName + 4 // QTYPE + QCLASS
        }
        val (answers, p1) = readRecords(buf, pos, an)
        val (authorities, p2) = readRecords(buf, p1, ns)
        val (additionals, _) = readRecords(buf, p2, ar)
        return Reply(id, rcode, truncated, answers, authorities, additionals)
    }

    /** 读一个（可能带压缩指针的）域名，返回 `域名 to 下一读取位置`。 */
    fun readName(buf: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = start
        var next = start
        var jumped = false
        // 压缩指针可以成环（恶意/畸形报文）：限制跳转次数，避免死循环把界面卡死
        var hops = 0
        while (hops++ < 128) {
            if (pos < 0 || pos >= buf.size) break
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                if (!jumped) next = pos
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= buf.size) break
                val ptr = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                if (!jumped) {
                    next = pos + 2
                    jumped = true
                }
                pos = ptr
                continue
            }
            if (pos + 1 + len > buf.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(buf, pos + 1, len, Charsets.ISO_8859_1))
            pos += len + 1
            if (!jumped) next = pos
        }
        return sb.toString() to next
    }

    private fun readRecords(buf: ByteArray, start: Int, count: Int): Pair<List<Record>, Int> {
        val out = ArrayList<Record>(count)
        var pos = start
        repeat(count) {
            if (pos + 2 > buf.size) return out to pos
            val (name, afterName) = readName(buf, pos)
            pos = afterName
            if (pos + 10 > buf.size) return out to pos
            val type = u16(buf, pos)
            val ttl = u32(buf, pos + 4)
            val rdlen = u16(buf, pos + 8)
            val rdataStart = pos + 10
            val rdataEnd = rdataStart + rdlen
            if (rdataEnd > buf.size) return out to buf.size
            out += Record(name, typeLabel(type), ttl, formatRdata(buf, type, rdataStart, rdlen))
            pos = rdataEnd
        }
        return out to pos
    }

    private fun typeLabel(type: Int): String = when (type) {
        TYPE_A -> "A"
        TYPE_NS -> "NS"
        TYPE_CNAME -> "CNAME"
        TYPE_SOA -> "SOA"
        TYPE_PTR -> "PTR"
        TYPE_MX -> "MX"
        TYPE_TXT -> "TXT"
        TYPE_AAAA -> "AAAA"
        TYPE_SRV -> "SRV"
        TYPE_CAA -> "CAA"
        else -> "TYPE$type"
    }

    private fun formatRdata(buf: ByteArray, type: Int, start: Int, len: Int): String = when (type) {
        TYPE_A -> if (len == 4) {
            "${buf[start].toInt() and 0xFF}.${buf[start + 1].toInt() and 0xFF}." +
                "${buf[start + 2].toInt() and 0xFF}.${buf[start + 3].toInt() and 0xFF}"
        } else {
            hex(buf, start, len)
        }

        TYPE_AAAA -> if (len == 16) {
            runCatching { InetAddress.getByAddress(buf.copyOfRange(start, start + 16)).hostAddress }
                .getOrElse { hex(buf, start, len) }
        } else {
            hex(buf, start, len)
        }

        TYPE_NS, TYPE_CNAME, TYPE_PTR -> readName(buf, start).first

        TYPE_MX -> if (len >= 3) "${u16(buf, start)} ${readName(buf, start + 2).first}" else hex(buf, start, len)

        TYPE_TXT -> readTxt(buf, start, len)

        TYPE_SOA -> readSoa(buf, start, len)

        TYPE_SRV -> if (len >= 7) {
            "优先级 ${u16(buf, start)} 权重 ${u16(buf, start + 2)} 端口 ${u16(buf, start + 4)} " +
                readName(buf, start + 6).first
        } else {
            hex(buf, start, len)
        }

        TYPE_CAA -> if (len >= 2) {
            val flags = buf[start].toInt() and 0xFF
            val tagLen = buf[start + 1].toInt() and 0xFF
            if (2 + tagLen > len) hex(buf, start, len)
            else {
                val tag = String(buf, start + 2, tagLen, Charsets.ISO_8859_1)
                val value = String(buf, start + 2 + tagLen, len - 2 - tagLen, Charsets.UTF_8)
                "flags=$flags $tag \"$value\""
            }
        } else {
            hex(buf, start, len)
        }

        else -> hex(buf, start, len)
    }

    /** TXT 的 RDATA 是「一个或多个 length-prefixed 字符串」，拼接显示。 */
    private fun readTxt(buf: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        var p = start
        val end = start + len
        while (p < end) {
            val n = buf[p].toInt() and 0xFF
            p++
            if (p + n > end) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String(buf, p, n, Charsets.UTF_8))
            p += n
        }
        return sb.toString()
    }

    private fun readSoa(buf: ByteArray, start: Int, len: Int): String {
        val (mname, p1) = readName(buf, start)
        val (rname, p2) = readName(buf, p1)
        if (p2 + 20 > start + len) return "$mname $rname"
        return "$mname $rname serial=${u32(buf, p2)} refresh=${u32(buf, p2 + 4)} " +
            "retry=${u32(buf, p2 + 8)} expire=${u32(buf, p2 + 12)} minimum=${u32(buf, p2 + 16)}"
    }

    private fun hex(buf: ByteArray, start: Int, len: Int): String {
        val n = minOf(len, 32)
        val sb = StringBuilder("hex:")
        for (i in 0 until n) {
            val v = buf[start + i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        if (len > n) sb.append("…($len B)")
        return sb.toString()
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)

    // ------------------------------------------------------------------ 传输

    /**
     * 发一次 UDP 查询。阻塞调用 —— 必须在 IO 线程使用。
     *
     * @param server DNS 服务器地址（IP 字面量最稳；域名会走系统解析）
     * @param timeoutMs 单次等待上限
     */
    fun query(server: String, host: String, type: RType, timeoutMs: Int = 3000): Reply {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val request = runCatching { buildQuery(host, type, id) }
            .getOrElse { throw DnsException("查询名非法：${it.message}") }
        val address = runCatching { InetAddress.getByName(server.trim().ifEmpty { "223.5.5.5" }) }
            .getOrElse { throw DnsException("DNS 服务器地址无法解析：$server") }
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.coerceIn(300, 20_000)
                socket.send(DatagramPacket(request, request.size, address, 53))
                // 4KB 覆盖绝大多数应答；EDNS0 的大响应会被 TC 位标记
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val reply = runCatching { parseReply(buf.copyOf(packet.length)) }
                    .getOrElse { throw DnsException("响应解析失败：${it.message}") }
                // ID 不匹配通常是旧应答串台；仍返回内容，由调用方决定是否采信
                reply
            }
        } catch (e: DnsException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw DnsException("等待 ${timeoutMs}ms 无应答（服务器不可达 / 53 端口被封 / 被防火墙丢弃）", e)
        } catch (e: Exception) {
            throw DnsException("查询失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
