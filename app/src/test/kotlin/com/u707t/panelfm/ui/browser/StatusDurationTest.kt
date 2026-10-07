package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态提示的显示时长（审计 U9）：错误类消息必须停得更久，否则用户还没看到就被顶掉。 */
class StatusDurationTest {

    @Test
    fun `错误类消息显示 6 秒`() {
        listOf("保存失败：超时", "压缩错误", "读取异常", "无法确定压缩包所在目录", "⚠️ 备份失败（本次未生成备份）").forEach {
            assertEquals("「$it」应停 6s", 6_000L, statusDurationMs(it))
        }
    }

    @Test
    fun `普通消息显示 2 点 6 秒`() {
        listOf("已复制 3 项到剪贴板（可到其他目录粘贴）", "已保存（UTF-8，1234 字节）", "已移入回收站 2 项（可还原）").forEach {
            assertEquals("「$it」应停 2.6s", 2_600L, statusDurationMs(it))
        }
    }

    @Test
    fun `错误提示一定比普通提示停得久`() {
        assertTrue(statusDurationMs("失败") > statusDurationMs("成功"))
    }
}
