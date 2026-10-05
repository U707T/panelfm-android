package com.u707t.panelfm.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览模式默认值（用户反馈：**每次打开都是单列**）。
 *
 * 旧默认是 `AUTO`，而 AUTO 在手机（屏宽 < 600dp）会展开成单列 ——
 * 与本应用「打开即双列」的定位相悖。默认改为 `DUAL`，「自动切换」保留为显式选项。
 * 老数据兼容（v1.1.0 之前只有 `single_column` 布尔开关）在 `PrefsStore.settings` 里处理。
 */
class BrowseModeDefaultTest {

    @Test
    fun `默认浏览模式是双列`() {
        assertEquals("DUAL", AppSettings().browseMode)
    }

    @Test
    fun `默认值是三档之一`() {
        assertTrue(
            "默认值必须是 AUTO / SINGLE / DUAL 之一",
            AppSettings().browseMode in setOf("AUTO", "SINGLE", "DUAL"),
        )
    }
}
