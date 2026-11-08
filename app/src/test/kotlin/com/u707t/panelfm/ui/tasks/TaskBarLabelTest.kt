package com.u707t.panelfm.ui.tasks

import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行尾的「一眼信息」（[trailingLabel]）与明细行（[taskDetailRows]）。
 *
 * 两者是「哪个状态显示什么」的唯一判据，任务页 / 传输对话框 / 底栏胶囊都取它们，
 * 所以必须钉死在 JVM 单测里 —— 项目没有 androidTest，沙盒也跑不了 Robolectric。
 */
class TaskBarLabelTest {

    private fun snapshot(
        state: TaskState,
        subtitle: String = "",
        retainedProgress: Float = -1f,
        retainedStats: String = "",
        retainedName: String = "",
    ) = TransferTaskSnapshot(
        id = "t",
        title = "复制 1 项",
        subtitle = subtitle,
        state = state,
        op = TransferOp.COPY,
        fromLabel = "Vedios",
        toLabel = "一键另存",
        retainedProgress = retainedProgress,
        retainedStats = retainedStats,
        retainedName = retainedName,
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

    // ---------------------------------------------------------------- 行尾

    @Test
    fun `暂停且留有保留帧时行尾是百分比`() {
        val s = snapshot(TaskState.Paused, retainedProgress = 0.3f, retainedStats = "1/3 项 · 282.82 M / 949.47 M")
        assertEquals("30%", s.trailingLabel())
    }

    @Test
    fun `排队中就被暂停时行尾退回状态词`() {
        val s = snapshot(TaskState.Paused)
        // 没有保留帧：不显示一个假的 0%
        assertEquals("已暂停", s.trailingLabel())
    }

    @Test
    fun `排队中显示状态词`() {
        assertEquals("排队中", snapshot(TaskState.Queued).trailingLabel())
    }

    @Test
    fun `运行中显示取整百分比`() {
        assertEquals("30%", snapshot(running(300_000_000, 995_000_000)).trailingLabel())
    }

    @Test
    fun `总大小未知时不显示假的百分比`() {
        assertEquals("统计中…", snapshot(running(0, 0)).trailingLabel())
    }

    // ---------------------------------------------------------------- 明细行

    @Test
    fun `运行中给名称 从 到 进度 速度`() {
        val rows = taskDetailRows(
            snapshot(running(300_000_000, 995_000_000), retainedName = "a.mp4")
        )
        assertEquals(listOf("名称", "从", "到", "进度", "速度"), rows.map { it.label })
        assertEquals("a.mp4", rows.first().value)
        assertEquals("Vedios", rows[1].value)
        assertEquals("一键另存", rows[2].value)
        // 速度行里带「剩」与项数
        assertTrue(rows[4].value.contains("剩 0:13"))
        assertTrue(rows[4].value.contains("1/3 项"))
    }

    @Test
    fun `暂停时保留最后一帧字节数并给出状态说明`() {
        val rows = taskDetailRows(
            snapshot(
                TaskState.Paused,
                retainedStats = "1/3 项 · 282.82 M / 949.47 M",
                retainedName = "a.mp4",
            )
        )
        assertEquals(listOf("名称", "从", "到", "进度", "状态"), rows.map { it.label })
        assertEquals("1/3 项 · 282.82 M / 949.47 M", rows[3].value)
        assertTrue(rows[4].value.startsWith("已暂停"))
    }

    @Test
    fun `排队中就被暂停时不伪造进度行`() {
        val rows = taskDetailRows(snapshot(TaskState.Paused))
        // 没有 retainedStats → 不出现「进度」；只剩「状态」，而不是进度 0
        assertEquals(listOf("从", "到", "状态"), rows.map { it.label })
    }

    @Test
    fun `失败行用错误色且带原因`() {
        val rows = taskDetailRows(snapshot(TaskState.Failed("磁盘已满")))
        val last = rows.last()
        assertEquals("失败", last.label)
        assertEquals("磁盘已满", last.value)
        assertTrue(last.isError)
    }

    @Test
    fun `完成显示结果而不是进度`() {
        val rows = taskDetailRows(
            snapshot(TaskState.Done(ok = 3, skipped = 0, failed = 0, bytes = 100, elapsedMs = 5000), subtitle = "成功 3 · 用时 0:05")
        )
        assertEquals("结果", rows.last().label)
        assertEquals("成功 3 · 用时 0:05", rows.last().value)
        assertFalse(rows.any { it.label == "速度" })
    }

    @Test
    fun `来源与目标为空时省略这两行`() {
        val rows = taskDetailRows(snapshot(TaskState.Queued).copy(fromLabel = "", toLabel = ""))
        assertEquals(listOf("状态"), rows.map { it.label })
    }
}
