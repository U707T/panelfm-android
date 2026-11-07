package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.3.0 传输任务 UI 重做用到的快照字段：
 *  - `fromLabel / toLabel`：MT 进度块的「从: / 到:」，只取末级目录名；
 *  - `retainedProgress / retainedName`：暂停 / 取消中沿用最后一帧，进度条不跳回 0%。
 */
class TaskSnapshotLabelTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun task(sources: List<VfsUri>, dest: VfsUri): TransferTask {
        val locator = FakeLocator(mapOf("a" to FakeVfs(), "b" to FakeVfs()))
        return TransferTask(
            request = TransferRequest(sources, dest, TransferOp.COPY),
            planner = FileOperationPlanner(locator),
            locator = locator,
            resumeStore = InMemoryResumeStore(),
        )
    }

    @Test
    fun `从与到取末级目录名`() {
        val snapshot = task(listOf(uri("a", "/Vedios/x.mp4")), uri("b", "/一键另存")).toSnapshot()
        assertEquals("Vedios", snapshot.fromLabel)
        assertEquals("一键另存", snapshot.toLabel)
    }

    @Test
    fun `目标为根目录时到显示斜杠`() {
        val snapshot = task(listOf(uri("a", "/x.mp4")), uri("b", "/")).toSnapshot()
        assertEquals("/", snapshot.toLabel)
    }

    @Test
    fun `暂停与取消中沿用保留的最后一帧`() {
        val paused = TransferTaskSnapshot(
            id = "p",
            title = "复制 1 项",
            subtitle = "已暂停",
            state = TaskState.Paused,
            op = TransferOp.COPY,
            retainedProgress = 0.42f,
            retainedName = "x.mp4",
        )
        assertEquals(0.42f, paused.overallProgress(), 0.0001f)
        assertEquals("x.mp4", paused.currentNameOrRetained())

        assertEquals(0.42f, paused.copy(state = TaskState.Cancelling).overallProgress(), 0.0001f)

        // 没有保留帧（例如「排队中就被暂停」）时退回 0，不崩、不伪造进度
        val bare = paused.copy(retainedProgress = -1f, retainedName = "")
        assertEquals(0f, bare.overallProgress(), 0.0001f)
        assertEquals("", bare.currentNameOrRetained())
    }
}
