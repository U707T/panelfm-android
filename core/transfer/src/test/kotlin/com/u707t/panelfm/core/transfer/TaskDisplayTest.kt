package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.TransferOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务条 / 任务页展示模型（v1.5.0）：
 *  - 排序：需要关注的在先（冲突 → 取消中 → 进行中 → 暂停 → 排队 → 失败 → 完成 → 取消），
 *    同级保持入队先后（列表不因状态跳动而乱序）；
 *  - 任务条过滤：进行中 + 失败展示；完成 / 已取消不常驻（引擎保留期后收走）。
 */
class TaskDisplayTest {

    private fun snapshot(id: String, state: TaskState) = TransferTaskSnapshot(
        id = id,
        title = "复制 1 项",
        subtitle = "",
        state = state,
        op = TransferOp.COPY,
    )

    private fun conflictState() = TaskState.WaitingConflict(
        ConflictInfo(
            index = 0, total = 1,
            sourceName = "a.txt", sourceSize = 1, sourceModified = 0,
            destName = "a.txt", destSize = 1, destModified = 0,
            isDirectory = false, isMove = false,
        )
    )

    @Test
    fun `展示排序：需要关注的在前，同级保持入队先后`() {
        val running1 = snapshot("r1", TaskState.Running(1, 3, "a", 10, 30, 0, -1))
        val running2 = snapshot("r2", TaskState.Running(1, 3, "b", 10, 30, 0, -1))
        // 故意打乱输入顺序
        val input = listOf(
            snapshot("d", TaskState.Done(1, 0, 0, 10, 5)),
            snapshot("z", TaskState.Cancelled),
            snapshot("q", TaskState.Queued),
            running1,
            snapshot("f", TaskState.Failed("boom")),
            snapshot("c", conflictState()),
            snapshot("p", TaskState.Paused),
            running2,
            snapshot("x", TaskState.Cancelling),
        )
        val sorted = input.sortedForDisplay().map { it.id }
        assertEquals(
            listOf("c", "x", "r1", "r2", "p", "q", "f", "d", "z"),
            sorted,
        )
    }

    @Test
    fun `任务条只展示进行中与失败，完成和已取消不常驻`() {
        val input = listOf(
            snapshot("d", TaskState.Done(1, 0, 0, 10, 5)),
            snapshot("z", TaskState.Cancelled),
            snapshot("q", TaskState.Queued),
            snapshot("r", TaskState.Running(1, 3, "a", 10, 30, 0, -1)),
            snapshot("f", TaskState.Failed("boom")),
            snapshot("c", conflictState()),
            snapshot("p", TaskState.Paused),
            snapshot("x", TaskState.Cancelling),
        )
        val bar = input.forBrowserBar().map { it.id }
        assertEquals(listOf("c", "x", "r", "p", "q", "f"), bar)
    }

    @Test
    fun `状态分类口径统一（进行中、已结束、可自动收场）`() {
        assertTrue(TaskState.Queued.isActive)
        assertTrue(TaskState.Running(0, 0, "", 0, 0, 0, -1).isActive)
        assertTrue(TaskState.Paused.isActive)
        assertTrue(conflictState().isActive)
        assertTrue(TaskState.Cancelling.isActive)
        assertFalse(TaskState.Done(1, 0, 0, 1, 1).isActive)

        assertTrue(TaskState.Done(1, 0, 0, 1, 1).isFinished)
        assertTrue(TaskState.Cancelled.isFinished)
        assertTrue(TaskState.Failed("x").isFinished)
        assertFalse(TaskState.Running(0, 0, "", 0, 0, 0, -1).isFinished)

        assertTrue(TaskState.Done(1, 0, 0, 1, 1).dismissesAutomatically)
        assertTrue(TaskState.Cancelled.dismissesAutomatically)
        assertFalse("失败必须留痕，不自动收走", TaskState.Failed("x").dismissesAutomatically)
    }

    @Test
    fun `进度换算：总进度与当前文件进度`() {
        val running = snapshot(
            "r",
            TaskState.Running(
                index = 2, total = 8, currentName = "f.bin",
                doneBytes = 25, totalBytes = 100, speedBps = 10, etaSeconds = 5,
                itemDoneBytes = 5, itemTotalBytes = 10,
            ),
        )
        assertEquals(0.25f, running.overallProgress(), 0.0001f)
        assertEquals(0.5f, running.itemProgress(), 0.0001f)

        val noItem = snapshot("r", TaskState.Running(1, 2, "x", 1, 10, 0, -1))
        assertEquals("无当前文件信息时返回 -1（UI 隐藏这一层）", -1f, noItem.itemProgress(), 0.0001f)

        assertEquals(1f, snapshot("d", TaskState.Done(1, 0, 0, 1, 1)).overallProgress(), 0.0001f)
        assertEquals(0f, snapshot("z", TaskState.Cancelled).overallProgress(), 0.0001f)
        assertEquals(0f, snapshot("q", TaskState.Queued).overallProgress(), 0.0001f)
    }
}
