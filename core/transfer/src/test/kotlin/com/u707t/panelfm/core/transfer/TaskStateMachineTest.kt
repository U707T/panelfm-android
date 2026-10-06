package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 任务状态机（v1.5.0「传输任务 ui / 逻辑」重构）：
 *  - 暂停排队中的任务 → 已暂停；恢复 → 排队中（而不是伪造一条假进度）；
 *  - 取消立即进入「正在取消…」（MT 同款文案），传输循环收尾后落到「已取消」；
 *  - 「先暂停、再取消」不再挂死 —— checkpoint 里取消优先于暂停等待（旧实现会永远
 *    停在 `while (paused)` 循环里，任务卡死、前台服务退不出去）；
 *  - 目录统计（plan）期间也响应取消（旧实现统计大目录时点取消毫无反应）。
 */
class TaskStateMachineTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun newTask(): TransferTask {
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val locator = FakeLocator(mapOf("one" to vfs))
        return TransferTask(
            request = TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            ),
            planner = FileOperationPlanner(locator),
            locator = locator,
            resumeStore = InMemoryResumeStore(),
        )
    }

    @Test
    fun `暂停排队中的任务显示已暂停，恢复回到排队`() {
        val task = newTask()
        assertEquals(TaskState.Queued, task.state.value)

        task.pause()
        assertEquals("排队中的任务暂停后应显示「已暂停」", TaskState.Paused, task.state.value)

        task.resume()
        assertEquals("没开跑过的任务恢复后回到「排队中」，等 worker 轮到时再正常统计", TaskState.Queued, task.state.value)
    }

    @Test
    fun `取消立即进入正在取消，随后由循环收尾为已取消`() = runBlocking {
        val task = newTask()
        task.cancel()
        assertEquals("取消应立刻有反馈（MT：正在取消操作…）", TaskState.Cancelling, task.state.value)

        // 入队后立刻取消的任务，worker 调 run() 时会在入口 checkpoint 直接收尾
        task.run()
        assertEquals(TaskState.Cancelled, task.state.value)
    }

    @Test
    fun `暂停中的任务被取消也会进入正在取消`() {
        val task = newTask()
        task.pause()
        task.cancel()
        assertEquals(TaskState.Cancelling, task.state.value)
    }

    @Test
    fun `暂停中取消不会永远挂在暂停等待里`() = runBlocking {
        val gate = TransferGate()
        gate.pause()
        val reached = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) {
            try {
                gate.checkpoint()
                reached += "continued"
            } catch (_: VfsException.Cancelled) {
                reached += "cancelled"
            }
        }
        delay(200) // 让它进入暂停等待（旧实现会在这里永远循环）
        gate.cancel()
        withTimeout(3_000) { job.join() }
        assertEquals(listOf("cancelled"), reached.toList())
    }

    @Test
    fun `目录统计期间的 checkpoint 会响应取消`() = runBlocking {
        val vfs = FakeVfs().dir("/src").dir("/src/sub").file("/src/a.txt", 10).file("/src/sub/b.txt", 10)
        val locator = FakeLocator(mapOf("one" to vfs))
        val planner = FileOperationPlanner(locator)
        var calls = 0
        try {
            planner.plan(
                TransferRequest(listOf(uri("one", "/src")), uri("one", "/dst"), TransferOp.COPY),
                checkpoint = {
                    calls++
                    if (calls >= 3) throw VfsException.Cancelled()
                },
            )
            fail("checkpoint 抛出取消后 plan 必须中止")
        } catch (_: VfsException.Cancelled) {
            // 预期路径
        }
        assertTrue("扫描期必须真的调用 checkpoint（实际 $calls 次）", calls >= 3)
    }
}
