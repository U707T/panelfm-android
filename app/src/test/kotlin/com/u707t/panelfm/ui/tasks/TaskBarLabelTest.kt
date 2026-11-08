package com.u707t.panelfm.ui.tasks

import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内联任务条 / 任务页的文案（v2.3.1 回归锁定）。
 *
 * 背景：v2.3.0 把「暂停 / 取消」从文字按钮换成图标后，行尾只剩百分比 ——
 * 暂停的任务在任务条上看起来仍在跑，正在取消的任务完全没有提示。
 * 这两个函数是纯函数，行为必须钉死在测试里。
 */
class TaskBarLabelTest {

    private fun snapshot(
        state: TaskState,
        subtitle: String = "",
        retainedProgress: Float = -1f,
        retainedStats: String = "",
    ) = TransferTaskSnapshot(
        id = "t",
        title = "复制 1 项",
        subtitle = subtitle,
        state = state,
        op = TransferOp.COPY,
        retainedProgress = retainedProgress,
        retainedStats = retainedStats,
    )

    private fun running(done: Long, total: Long) = TaskState.Running(
        index = 1,
        total = 3,
        currentName = "a.mp4",
        doneBytes = done,
        totalBytes = total,
        speedBps = 52_000_000,
        etaSeconds = 13,
    )

    @Test
    fun `暂停时第二行给出状态词与最后一帧字节数`() {
        val s = snapshot(
            TaskState.Paused,
            subtitle = "已暂停",
            retainedProgress = 0.3f,
            retainedStats = "1/3 项 · 282.82 M / 949.47 M",
        )
        assertEquals("已暂停 · 1/3 项 · 282.82 M / 949.47 M", s.barDetail())
        // 行尾仍是百分比：状态词与进度同时可见，不再是「30% + 播放图标」猜谜
        assertEquals("30%", s.trailingLabel())
    }

    @Test
    fun `排队中就被暂停时没有保留帧，只剩状态词不画空进度`() {
        val s = snapshot(TaskState.Paused, subtitle = "已暂停")
        assertEquals("已暂停", s.barDetail())
        assertEquals("已暂停", s.trailingLabel())
        assertEquals(-1f, s.retainedProgress, 0.0001f)
    }

    @Test
    fun `正在取消给出一句话说明而不是百分比`() {
        val s = snapshot(TaskState.Cancelling, subtitle = "正在取消操作…", retainedStats = "1/3 项")
        assertEquals("正在取消操作… · 1/3 项", s.barDetail())
    }

    @Test
    fun `排队中不重复行尾已经写着的状态词`() {
        assertEquals("", snapshot(TaskState.Queued).barDetail())
        assertEquals("排队中", snapshot(TaskState.Queued).trailingLabel())
    }

    @Test
    fun `运行中沿用统计行并带速度`() {
        val s = snapshot(running(300_000_000, 995_000_000), subtitle = "1/3 项 · 300.00 M / 995.00 M · 52.00 M/s · 剩 0:13")
        assertEquals(s.subtitle, s.barDetail())
        assertEquals("30%", s.trailingLabel())
    }

    @Test
    fun `总大小未知时不显示假的百分比`() {
        val s = snapshot(running(0, 0), subtitle = "")
        assertEquals("统计中…", s.trailingLabel())
    }
}
