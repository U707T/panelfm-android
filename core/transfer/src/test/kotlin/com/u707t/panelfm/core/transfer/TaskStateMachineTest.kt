package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *  - 目录统计（plan）期间也响应取消（旧实现统计大目录时点取消毫无反应）；
 *  - 第 4 批审计 🟡3：钉住「暂停占用并发位」的已知语义（彻底修法落地时此用例需改断言）。
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

    @Test
    fun `已知语义：暂停的任务占用并发位（后续任务等待，恢复后接上）`() = runBlocking {
        // 第 4 批审计 🟡3 · 轻量修法：并发位占用语义显式化到任务行文案，本用例把当前行为
        // 钉死 —— 彻底修法（暂停即退出 run() 释放槽位）落地时，此用例应改为断言
        // 「并发位被释放，t2 能先跑起来」。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/big.bin", 1_000_000)
        val dstVfs = FakeVfs().dir("/dst")
        val gate = CompletableDeferred<Unit>()
        srcVfs.readGate = gate
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = TransferEngine(
            planner = FileOperationPlanner(locator),
            locator = locator,
            resumeStore = InMemoryResumeStore(),
            dispatchers = PanelDispatchers(main = Dispatchers.Default),
            scope = scope,
            maxConcurrent = 1,
        )

        val t1 = engine.enqueue(
            TransferRequest(listOf(uri("srcv", "/src/big.bin")), uri("dstv", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(15_000) { while (srcVfs.readsStarted.get() == 0) delay(5) }
        t1.pause()
        assertEquals(TaskState.Paused, t1.state.value)

        val t2 = engine.enqueue(
            TransferRequest(listOf(uri("srcv", "/src/big.bin")), uri("dstv", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        gate.complete(Unit)
        delay(400) // 给 t2 充足的「本可开跑」窗口
        assertTrue("并发位被暂停任务占住，后续任务必须还在排队", t2.state.value is TaskState.Queued)

        t1.resume()
        withTimeout(15_000) {
            while (t1.state.value !is TaskState.Done || t2.state.value !is TaskState.Done) delay(10)
        }
        scope.cancel()
    }
}
