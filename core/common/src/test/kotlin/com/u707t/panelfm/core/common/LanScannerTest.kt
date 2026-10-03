package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScannerTest {

    @Test
    fun `网段内主机地址枚举`() {
        val hosts = LanScanner.hosts("192.168.28")
        assertEquals(254, hosts.size)
        assertEquals("192.168.28.1", hosts.first())
        assertEquals("192.168.28.254", hosts.last())
    }

    @Test
    fun `自定义范围`() {
        val hosts = LanScanner.hosts("10.0.0", from = 10, to = 12)
        assertEquals(listOf("10.0.0.10", "10.0.0.11", "10.0.0.12"), hosts)
    }

    @Test
    fun `本机网段前缀格式合法`() {
        // 沙箱/CI 里可能没有可用网段，这里只校验格式
        LanScanner.localPrefixes().forEach { prefix ->
            assertTrue("前缀应形如 a.b.c：$prefix", Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}$""").matches(prefix))
        }
    }
}
