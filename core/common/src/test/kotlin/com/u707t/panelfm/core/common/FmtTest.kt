package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {

    @Test
    fun `大小格式化`() {
        // MT 观感：单位单字母 + **两位小数**（截图实测「储存: 384.95G/479.51G」）
        assertEquals("512 B", Fmt.size(512))
        assertEquals("1.00 K", Fmt.size(1024))
        assertEquals("1.50 M", Fmt.size(1024L * 1024 * 3 / 2))
        assertEquals("2.00 G", Fmt.size(2L * 1024 * 1024 * 1024))
        assertEquals("384.88 G", Fmt.size(413_265_051_648L))
        assertEquals("", Fmt.size(-1))
        // 紧凑写法（MT 顶栏「384.95G/479.51G」、侧拉栏「384.71G已用」）
        assertEquals("384.88G", Fmt.sizeCompact(413_265_051_648L))
        assertEquals("1.00K", Fmt.sizeCompact(1024))
    }

    @Test
    fun `权限字符串`() {
        assertEquals("rwxr-xr-x", Fmt.mode(0b111101101))
        assertEquals("rw-r--r--", Fmt.mode(0b110100100))
    }

    @Test
    fun `剩余时间格式`() {
        assertEquals("12:03", Fmt.eta(723))
        assertEquals("1:00:00", Fmt.eta(3600))
        assertEquals("--:--", Fmt.eta(-5))
    }

    @Test
    fun `百分比`() {
        assertEquals(50, Fmt.percent(50, 100))
        assertEquals(0, Fmt.percent(1, 0))
        assertEquals(100, Fmt.percent(200, 100))
        assertTrue(Fmt.speed(1024).endsWith("/s"))
    }
}
