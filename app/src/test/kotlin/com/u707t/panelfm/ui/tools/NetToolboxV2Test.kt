package com.u707t.panelfm.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络工具箱能力：DNS 报文编解码 / 智能解码 / JSON 美化 / 受限头剔除 / 统计。
 *  端口解析已随「端口探测」合并到 core/common 的 PortScan（测试见 PortScanTest）。 */
class NetToolboxV2Test {

    // ------------------------------------------------------------------ DNS 报文

    @Test
    fun `查询报文头部与问题段正确`() {
        val q = DnsKit.buildQuery("www.example.com", DnsKit.RType.A, id = 0x1234)
        // ID
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        // flags：RD=1
        assertEquals(0x01, q[2].toInt() and 0xFF)
        assertEquals(0x00, q[3].toInt() and 0xFF)
        // QDCOUNT=1，其余 0
        assertEquals(1, q[5].toInt() and 0xFF)
        assertEquals(0, q[7].toInt() and 0xFF)
        // 域名编码：3www 7example 3com 0
        assertEquals(3, q[12].toInt() and 0xFF)
        assertEquals("www", String(q, 13, 3, Charsets.ISO_8859_1))
        assertEquals(7, q[16].toInt() and 0xFF)
        assertEquals("example", String(q, 17, 7, Charsets.ISO_8859_1))
        assertEquals(0, q[q.size - 5].toInt() and 0xFF) // 根标签
        // QTYPE=A(1) / QCLASS=IN(1)：各占 2 字节，低字节为 1
        assertEquals(1, q[q.size - 3].toInt() and 0xFF)
        assertEquals(1, q[q.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `IPv4 自动转反向解析域名`() {
        assertEquals("4.4.8.8.in-addr.arpa", DnsKit.normalizeQueryName("8.8.4.4"))
        assertEquals("example.com", DnsKit.normalizeQueryName("  example.com.  "))
        // 带协议头的粘贴内容要能直接查
        assertEquals("www.example.com", DnsKit.normalizeQueryName("https://www.example.com/a?b=1"))
    }

    @Test
    fun `超长域名与非法标签被拒绝`() {
        val long = (1..64).joinToString("") { "a" } + ".com"
        assertFalse(runCatching { DnsKit.buildQuery(long, DnsKit.RType.A, 1) }.isSuccess)
    }

    @Test
    fun `解析带压缩指针的应答`() {
        // 手工拼一个应答：ID=0x1234, flags=0x8180(NOERROR), QD=1, AN=1
        // 问题段 www.example.com A IN；答案段 指针(0x0C) A 60s 1.2.3.4
        val q = DnsKit.buildQuery("www.example.com", DnsKit.RType.A, id = 0x1234)
        val head = q.copyOf(12).also {
            it[2] = 0x81.toByte() // QR=1 RD=1
            it[3] = 0x80.toByte() // RA=1 RCODE=0
            it[7] = 0x01          // ANCOUNT=1
        }
        val answer = byteArrayOf(
            0xC0.toByte(), 0x0C, // 指向 offset 12 的名字
            0x00, 0x01,          // TYPE=A
            0x00, 0x01,          // CLASS=IN
            0x00, 0x00, 0x00, 0x3C, // TTL=60
            0x00, 0x04,          // RDLENGTH=4
            1, 2, 3, 4,
        )
        val packet = head + q.copyOfRange(12, q.size) + answer
        val reply = DnsKit.parseReply(packet)
        assertEquals(0x1234, reply.id)
        assertTrue(reply.ok)
        assertEquals(1, reply.answers.size)
        val rec = reply.answers.first()
        assertEquals("www.example.com", rec.name)
        assertEquals("A", rec.type)
        assertEquals(60L, rec.ttl)
        assertEquals("1.2.3.4", rec.value)
    }

    @Test
    fun `NXDOMAIN 与截断位可读`() {
        val q = DnsKit.buildQuery("nope.example", DnsKit.RType.A, id = 7)
        val head = q.copyOf(12).also {
            it[2] = 0x83.toByte() // QR=1 TC=1 RCODE=3
            it[3] = 0x03.toByte()
        }
        val reply = DnsKit.parseReply(head + q.copyOfRange(12, q.size))
        assertEquals(3, reply.rcode)
        assertTrue(reply.truncated)
        assertFalse(reply.ok)
        assertTrue(reply.rcodeText().startsWith("NXDOMAIN"))
    }

    @Test
    fun `畸形报文不死循环也不抛异常`() {
        // 自指压缩指针：必须靠跳转上限兜住
        val evil = ByteArray(20)
        evil[12] = 0xC0.toByte()
        evil[13] = 0x0C
        val reply = DnsKit.parseReply(evil)
        assertNotNull(reply)
        // 过短报文直接拒绝（调用方会当成「响应解析失败」）
        assertTrue(runCatching { DnsKit.parseReply(ByteArray(4)) }.isFailure)
    }

    @Test
    fun `TXT 多段拼接与 MX 优先级`() {
        // 手工构造一条 TXT（两段）与一条 MX
        val name = byteArrayOf(1, 'a'.code.toByte(), 0)
        fun rr(type: Int, rdata: ByteArray): ByteArray = name +
            byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0, 10, 0, rdata.size.toByte()) + rdata
        val txt = rr(16, byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 2, 'd'.code.toByte(), 'e'.code.toByte()))
        val mx = rr(15, byteArrayOf(0, 10) + name)
        val head = ByteArray(12).also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
            it[7] = 2
        }
        val reply = DnsKit.parseReply(head + txt + mx)
        assertEquals(2, reply.answers.size)
        assertEquals("abc de", reply.answers[0].value)
        assertEquals("10 a", reply.answers[1].value)
    }

    // ------------------------------------------------------------------ 响应体

    @Test
    fun `二进制内容自动识别并转 HEX`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        assertTrue(NetToolboxKit.looksBinary(png))
        val smart = NetToolboxKit.decodeBodySmart(png)
        assertTrue(smart.binary)
        assertTrue(smart.text.contains("89 50 4E 47"))
    }

    @Test
    fun `GBK 中文不再乱码`() {
        val gbk = "中文测试".toByteArray(charset("GBK"))
        val smart = NetToolboxKit.decodeBodySmart(gbk)
        assertFalse(smart.binary)
        assertEquals("中文测试", smart.text)
        assertEquals("GBK", smart.charset)
    }

    @Test
    fun `纯文本不当成二进制`() {
        val text = "hello 世界\nline2".toByteArray(Charsets.UTF_8)
        assertFalse(NetToolboxKit.looksBinary(text))
        val smart = NetToolboxKit.decodeBodySmart(text)
        assertEquals("hello 世界\nline2", smart.text)
    }

    // ------------------------------------------------------------------ JSON 美化

    @Test
    fun `JSON 缩进正确且非法输入返回 null`() {
        val pretty = NetToolboxKit.prettyJson("""{"a":1,"b":[1,2],"c":{"d":"x:y"}}""")
        assertNotNull(pretty)
        assertTrue(pretty!!.contains("\n  \"a\": 1"))
        assertTrue(pretty.contains("\"d\": \"x:y\"")) // 字符串里的冒号不能被当结构
        assertNull(NetToolboxKit.prettyJson("not json"))
        assertNull(NetToolboxKit.prettyJson("""{"a":1""")) // 括号不配对
        assertNull(NetToolboxKit.prettyJson("""{"a":"unclosed}"""))
    }

    // ------------------------------------------------------------------ 请求头

    @Test
    fun `受限请求头被剔除并点名`() {
        val (ok, rejected) = NetToolboxKit.splitHeaders(
            listOf(
                "Host" to "example.com",
                "Content-Length" to "10",
                "Content-Type" to "application/json",
                "X-Token" to "abc",
            ),
        )
        assertEquals(listOf("X-Token" to "abc"), ok)
        assertEquals(listOf("Host", "Content-Length"), rejected)
        // Content-Type 交给 RequestBody，不算「被拒」
        assertFalse(rejected.any { it.equals("Content-Type", ignoreCase = true) })
    }

    @Test
    fun `方法体规则与自动超时`() {
        assertFalse(NetToolboxKit.methodAllowsBody("GET"))
        assertFalse(NetToolboxKit.methodAllowsBody("HEAD"))
        assertTrue(NetToolboxKit.methodAllowsBody("DELETE"))
        // 次数 20 → 22 秒（旧实现固定 4 秒，20 次根本跑不完）
        assertEquals(22, NetToolboxKit.autoDeadlineSec(20))
        assertEquals(3, NetToolboxKit.autoDeadlineSec(0))
    }

    // ------------------------------------------------------------------ Ping 统计

    @Test
    fun `ping 输出逐行统计`() {
        var s = PingStats()
        s = PingStats.feed(s, "PING 223.5.5.5 (223.5.5.5) 56(84) bytes of data.")
        s = PingStats.feed(s, "64 bytes from 223.5.5.5: icmp_seq=1 ttl=52 time=12.3 ms")
        s = PingStats.feed(s, "64 bytes from 223.5.5.5: icmp_seq=2 ttl=52 time=18.7 ms")
        assertEquals(2, s.received)
        assertEquals(12.3, s.min!!, 0.001)
        assertEquals(18.7, s.max!!, 0.001)
        assertEquals(15.5, s.avg!!, 0.001)
        assertEquals(6.4, s.jitter!!, 0.001)

        s = PingStats.feed(s, "4 packets transmitted, 3 received, 25% packet loss, time 3004ms")
        assertEquals(4, s.transmitted)
        assertEquals(3, s.received)
        assertEquals(25.0, s.lossPercent, 0.001)
        assertTrue(s.statsLineSeen)
        assertTrue(s.summary().contains("丢包 25%"))
    }

    @Test
    fun `空统计不会崩`() {
        val s = PingStats()
        assertEquals(0.0, s.lossPercent, 0.0)
        assertNull(s.avg)
        assertNull(s.jitter)
        assertTrue(s.summary().isNotEmpty())
    }
}
