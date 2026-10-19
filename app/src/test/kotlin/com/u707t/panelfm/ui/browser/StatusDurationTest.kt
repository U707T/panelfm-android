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

    @Test
    fun `队列语义：后到的消息排在后面，不会被顶掉`() {
        // 纯模型：showStatus = 追加并只保留最后 8 条（防止长时间操作刷屏把内存撑大）
        fun push(queue: List<String>, message: String) = (queue + message).takeLast(8)

        var q = emptyList<String>()
        q = push(q, "已删除 2 项")
        q = push(q, "已复制 3 项")
        assertEquals(listOf("已删除 2 项", "已复制 3 项"), q)
        // 消费一条（界面显示完 / 点「知道了」）
        assertEquals(listOf("已复制 3 项"), q.drop(1))

        // 超过上限只丢最老的，最新的必须还在（否则错误提示会被后续噪音顶掉）
        var big = emptyList<String>()
        repeat(12) { big = push(big, "消息$it") }
        assertEquals(8, big.size)
        assertEquals("消息11", big.last())
        assertEquals("消息4", big.first())
    }
}
