package com.u707t.panelfm.ui.tasks

import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.sortedForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 传输对话框「显示哪个任务」的选取规则（v2.3.2）。
 *
 * 这里刻意**不复用** `sortedForDisplay()`：它把「等待冲突」排在进行中之前（列表里合理，
 * 因为那是需要用户处理的事），但对话框是进度展示 —— 直接取第一个会让两侧都在传时，
 * 对话框一直显示那个卡在冲突上的任务，进度条不动，看起来像卡死。
 */
class TaskDialogFocusTest {

    private fun snapshot(id: String, state: TaskState) = TransferTaskSnapshot(
        id = id,
        title = "任务 $id",
        subtitle = "",
        state = state,
        op = TransferOp.COPY,
    )

    private fun running(tag: String) = TaskState.Running(
        index = 1, total = 1, currentName = "$tag.mp4",
        doneBytes = 1, totalBytes = 2, speedBps = 1, etaSeconds = 1,
    )

    private fun conflictInfo() = ConflictInfo(
        index = 1,
        total = 1,
        sourceName = "a.txt",
        sourceSize = 1,
        sourceModified = 0,
        destName = "a.txt",
        destSize = 1,
        destModified = 0,
        isDirectory = false,
        isMove = false,
        sourceIsDirectory = false,
        token = 1,
    )

    @Test
    fun `优先显示正在传的任务而不是等待冲突的`() {
        val conflict = snapshot("c", TaskState.WaitingConflict(conflictInfo()))
        val running = snapshot("r", running("a"))
        // 展示排序里冲突在前（那是「要用户处理」的排序），对话框必须反过来
        assertEquals("c", listOf(conflict, running).sortedForDisplay().first().id)
        assertEquals("r", listOf(conflict, running).dialogFocus()?.id)
    }

    @Test
    fun `没有在传时先给等待冲突再给暂停`() {
        val paused = snapshot("p", TaskState.Paused)
        val conflict = snapshot("c", TaskState.WaitingConflict(conflictInfo()))
        assertEquals("c", listOf(paused, conflict).dialogFocus()?.id)
        assertEquals("p", listOf(paused).dialogFocus()?.id)
    }

    @Test
    fun `同档位保持传入顺序（生产侧传入的是入队顺序）`() {
        val a = snapshot("a", running("a"))
        val b = snapshot("b", running("b"))
        assertEquals("a", listOf(a, b).dialogFocus()?.id)
        assertEquals("b", listOf(b, a).dialogFocus()?.id)
    }

    @Test
    fun `排队优先于已结束的任务`() {
        val done = snapshot("d", TaskState.Done(ok = 1, skipped = 0, failed = 0, bytes = 10, elapsedMs = 100))
        val queued = snapshot("q", TaskState.Queued)
        assertEquals("q", listOf(done, queued).dialogFocus()?.id)
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(emptyList<TransferTaskSnapshot>().dialogFocus())
    }
}
