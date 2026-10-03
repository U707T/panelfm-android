package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {

    @Test
    fun `大小格式化`() {
        assertEquals("512 B", Fmt.size(512))
        assertEquals("1.0 KB", Fmt.size(1024))
        assertEquals("1.5 MB", Fmt.size(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", Fmt.size(2L * 1024 * 1024 * 1024))
        assertEquals("", Fmt.size(-1))
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
