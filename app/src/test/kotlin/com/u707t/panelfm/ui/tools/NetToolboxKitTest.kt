package com.u707t.panelfm.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络工具箱纯逻辑：请求头宽松解析 / URL 归一化 / ping 参数夹取 / 响应体截断。 */
class NetToolboxKitTest {

    @Test
    fun headerLinesAreParsedLoose() {
        val parsed = NetToolboxKit.parseHeaderLines(
            """
            User-Agent: PanelFM/2.0
            Accept: application/json

            这一行没有冒号会被忽略
            X-Token: a:b:c
            """.trimIndent(),
        )
        assertEquals(3, parsed.size)
        assertEquals("User-Agent" to "PanelFM/2.0", parsed[0])
        assertEquals("Accept" to "application/json", parsed[1])
        // 值里的冒号保留（只按第一个冒号切分）
        assertEquals("X-Token" to "a:b:c", parsed[2])
    }

    @Test
    fun headerEmptyValueKeptAndKeyTrimmed() {
        assertEquals(listOf("X-Empty" to ""), NetToolboxKit.parseHeaderLines("X-Empty:"))
        assertEquals(listOf("A" to "b: c"), NetToolboxKit.parseHeaderLines(" A : b: c "))
        // 只有冒号、没有 key 的行忽略
        assertEquals(emptyList<Pair<String, String>>(), NetToolboxKit.parseHeaderLines(": value"))
    }

    @Test
    fun urlWithoutSchemeGetsHttp() {
        assertEquals("http://192.168.1.9:8080/api", NetToolboxKit.normalizeUrl("192.168.1.9:8080/api"))
        assertEquals("http://example.com:8080/x", NetToolboxKit.normalizeUrl("example.com:8080/x"))
        assertEquals("https://example.com/x", NetToolboxKit.normalizeUrl("https://example.com/x"))
        assertEquals("", NetToolboxKit.normalizeUrl("   "))
    }

    @Test
    fun pingArgsAreClamped() {
        assertEquals(
            listOf("ping", "-c", "1", "-s", "65500", "-w", "1", "example.com"),
            NetToolboxKit.buildPingArgs("example.com", count = 0, size = 999_999, timeoutSec = -5),
        )
        assertEquals(
            listOf("ping", "-c", "100", "-s", "8", "-w", "120", "8.8.8.8"),
            NetToolboxKit.buildPingArgs(" 8.8.8.8 ", count = 9999, size = 1, timeoutSec = 9999),
        )
    }

    @Test
    fun pingArgsNormal() {
        assertEquals(
            listOf("ping", "-c", "4", "-s", "64", "-w", "4", "www.baidu.com"),
            NetToolboxKit.buildPingArgs("www.baidu.com", 4, 64, 4),
        )
    }

    @Test
    fun hexDumpFormatsLinesAndTruncates() {
        val dump = NetToolboxKit.hexDump("AB".toByteArray(Charsets.UTF_8))
        val firstLine = dump.lineSequence().first()
        assertTrue(firstLine.startsWith("00000000"))
        assertTrue(firstLine.contains("41 42"))
        assertTrue(firstLine.contains("|AB|"))

        // 超过 limit 截断并标注
        val truncated = NetToolboxKit.hexDump(ByteArray(32), limit = 16)
        assertTrue(truncated.contains("已截断"))

        // 空输入 → 空输出（UI 侧显示「（空响应体）」）
        assertEquals("", NetToolboxKit.hexDump(ByteArray(0)))
    }

    @Test
    fun presetsHaveSaneDefaults() {
        // 「默认选中」= 取预设列表第一个（输入框初值，见 NetToolboxScreen）；本测试锁定该约定
        assertEquals("223.5.5.5", NetToolboxKit.PingPresets.first())
        assertEquals("https://www.baidu.com", NetToolboxKit.HttpPresets.first())
        // HTTP 预设都带协议头，normalizeUrl 不得做任何改动（点 chip 后可直接发送）
        NetToolboxKit.HttpPresets.forEach {
            assertEquals(it, NetToolboxKit.normalizeUrl(it))
        }
        // 预设间不得重复
        assertEquals(NetToolboxKit.PingPresets.size, NetToolboxKit.PingPresets.toSet().size)
        assertEquals(NetToolboxKit.HttpPresets.size, NetToolboxKit.HttpPresets.toSet().size)
    }

    @Test
    fun decodeBodyPlainAndTruncated() {
        assertEquals("你好", NetToolboxKit.decodeBody("你好".toByteArray(Charsets.UTF_8)))
        val big = ByteArray(10) { 'A'.code.toByte() }
        val text = NetToolboxKit.decodeBody(big, limit = 4)
        assertTrue(text.startsWith("AAAA"))
        assertTrue(text.contains("已截断"))
    }
}
