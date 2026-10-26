package com.u707t.panelfm.core.common

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端口探测引擎（v2.1.2：从网络工具箱的端口面板合并到局域网扫描）。
 *
 * 重点锁住「闪退」的那几条边界：端口总数上限（含多段区间拼起来的）、
 * banner 读取的字节上限、以及探测失败必须变成 open=false 而不是抛异常。
 */
class PortScanTest {

    // ------------------------------------------------------------------ 端口解析

    @Test
    fun `端口列表解析支持区间与混写`() {
        assertEquals(listOf(22, 80, 443), PortScan.parsePorts("22,80,443"))
        assertEquals(listOf(1, 2, 3), PortScan.parsePorts("1-3"))
        assertEquals(listOf(22, 79, 80, 81), PortScan.parsePorts(" 80 ，22  79-81 "))
        assertEquals(listOf(22, 80, 443), PortScan.parsePorts("22;80\n443"))
        // 越界与垃圾片段被忽略
        assertEquals(emptyList<Int>(), PortScan.parsePorts("0,70000,abc"))
        // 去重 + 升序
        assertEquals(listOf(80, 443), PortScan.parsePorts("443,80,80"))
        // 反向区间按小→大处理
        assertEquals(listOf(20, 21, 22), PortScan.parsePorts("22-20"))
    }

    @Test
    fun `多段区间也受总数上限约束`() {
        // 旧实现只按「单段 2048」限，拼几段就能变回 6 万个端口（协程扇出 → OOM）
        val many = PortScan.parsePortsDetailed("1-2048,2049-4096,4097-6144,6145-8192")
        assertEquals(PortScan.MAX_PORTS, many.list.size)
        assertTrue(many.truncated)

        val wide = PortScan.parsePortsDetailed("1-65535")
        assertEquals(PortScan.MAX_PORTS, wide.list.size)
        assertTrue(wide.truncated)

        // 正常输入不算截断
        assertFalse(PortScan.parsePortsDetailed("22,80,443").truncated)
    }

    @Test
    fun `端口说明表有内容且不重复`() {
        assertTrue(PortScan.CommonPorts.size >= 30)
        assertEquals(
            PortScan.CommonPorts.size,
            PortScan.CommonPorts.map { it.first }.toSet().size,
        )
        assertEquals("SSH / SFTP", PortScan.portLabel(22))
        assertEquals("OpenList / Alist WebDAV", PortScan.portLabel(5244))
        assertNull(PortScan.portLabel(65534))
    }

    // ------------------------------------------------------------------ banner

    @Test
    fun `banner 只取一行且只留可打印字符`() {
        val ssh = "SSH-2.0-OpenSSH_9.6\r\nmore data".toByteArray(Charsets.ISO_8859_1)
        assertEquals("SSH-2.0-OpenSSH_9.6", PortScan.readBannerHead(ByteArrayInputStream(ssh)))

        // 二进制（TLS 告警之类）：一个可打印字符都没有 → null，不是乱码
        val binary = ByteArray(64) { 0x01 }
        assertNull(PortScan.readBannerHead(ByteArrayInputStream(binary)))

        // 空流 / 立即 EOF
        assertNull(PortScan.readBannerHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `banner 读取有字节上限`() {
        // 对端不换行地灌数据：旧实现 readLine() 会一直堆到堆爆
        val flood = ByteArray(4096) { 'A'.code.toByte() }
        val head = PortScan.readBannerHead(ByteArrayInputStream(flood))
        assertEquals(PortScan.BANNER_LIMIT, head!!.length)
    }

    // ------------------------------------------------------------------ 探测 / 排序

    @Test
    fun `探测失败不抛异常只返回未开放`() {
        // 127.0.0.1:1 正常是拒绝连接；沙箱里没有网络时也只会超时——两种都必须收成 false
        val r = PortScan.probe("127.0.0.1", 1, timeoutMs = 120)
        assertFalse(r.open)
        assertNull(r.banner)
        assertEquals(1, r.port)
        assertTrue(r.elapsedMs >= 0)
    }

    @Test
    fun `结果排序按主机末段数字而非字典序`() {
        val results = listOf(
            PortScan.Result("192.168.1.10", 22, true, null, 1),
            PortScan.Result("192.168.1.9", 5244, true, null, 1),
            PortScan.Result("192.168.1.9", 22, true, null, 1),
        )
        val sorted = PortScan.sortResults(results)
        assertEquals(listOf("192.168.1.9:22", "192.168.1.9:5244", "192.168.1.10:22"), sorted.map { "${it.host}:${it.port}" })
    }

    @Test
    fun `汇总与文本导出包含关键信息`() {
        val results = listOf(
            PortScan.Result("192.168.1.9", 22, true, "SSH-2.0-x", 12),
            PortScan.Result("192.168.1.9", 80, false, null, 30),
        )
        assertEquals("开放 1 / 共 2 次探测 · 用时 0:00", PortScan.summary(1, 2, 0))
        val text = PortScan.render("局域网扫描", "网段 192.168.1.0/24 × 端口 22,80", results, 0)
        assertTrue(text.startsWith("# 局域网扫描"))
        assertTrue(text.contains("OPEN  \t192.168.1.9:22\tSSH / SFTP\tSSH-2.0-x\t12ms"))
        assertTrue(text.contains("closed\t192.168.1.9:80"))
    }
}
