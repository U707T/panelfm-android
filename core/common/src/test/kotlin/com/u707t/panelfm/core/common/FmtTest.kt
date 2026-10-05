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

    /**
     * 显示格式必须与系统 locale 无关（B10）。
     *
     * 旧实现用 `String.format(...)` 不带 locale：在阿拉伯语 / 部分印度语 locale 下
     * 数字会被本地化成非 ASCII（`1٫50 M`、`٠١:٠٢`），
     * 而文件名、校验值、播放时间这类「要跟别人对齐比对」的文本必须是固定 ASCII。
     */
    @Test
    fun `格式化不随系统 locale 变化`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("1.50 M", Fmt.size(1024L * 1024 * 3 / 2))
            assertEquals("12:03", Fmt.eta(723))
            // 十六进制 / 十进制都必须是 ASCII
            assertTrue(Fmt.size(1024L * 1024 * 3 / 2).all { it.code < 128 })
            assertTrue(Fmt.eta(723).all { it.code < 128 })
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    /**
     * 列表时间格式器的缓存必须随 [Fmt.datePattern] 失效（B11）。
     *
     * 旧实现每次调用都 `DateTimeFormatter.ofPattern(...)`（列表滚动时每行重建一次）；
     * 改成缓存后，切「显示秒」开关必须能立刻换格式，不能还留着旧 formatter。
     */
    @Test
    fun `时间格式缓存随设置失效`() {
        val original = Fmt.showSeconds
        try {
            Fmt.showSeconds = false
            val compact = Fmt.time(1_700_000_000_000L)
            Fmt.showSeconds = true
            val withSeconds = Fmt.time(1_700_000_000_000L)
            assertTrue("不带秒：$compact", compact.length < withSeconds.length)
            // 两次调用结果稳定（缓存不会串）
            assertEquals(withSeconds, Fmt.time(1_700_000_000_000L))
        } finally {
            Fmt.showSeconds = original
        }
    }

    @Test
    fun `权限特殊位渲染`() {
        // setuid + 执行位 → 's'；有 setuid 无执行位 → 'S'
        assertEquals("-rwsr-xr-x", Fmt.modeLong(0x800 or 0b111_101_101, isDirectory = false))
        assertEquals("-rwSr--r--", Fmt.modeLong(0x800 or 0b110_100_100, isDirectory = false))
        // sticky：目录 1777 → drwxrwxrwt
        assertEquals("drwxrwxrwt", Fmt.modeLong(0x200 or 0b111_111_111, isDirectory = true))
    }
}
