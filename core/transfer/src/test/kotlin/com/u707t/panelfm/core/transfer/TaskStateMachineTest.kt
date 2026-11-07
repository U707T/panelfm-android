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
    fun `暂停立即让出（抛 PauseSignal），取消优先于暂停`() = runBlocking {
        // v2.2.5：暂停不再挂在闸门里（那会占着 worker 槽位，并发 2 时两个暂停就把队列锁死）。
        // checkpoint 遇到暂停立即抛 PauseSignal，任务 run() 收尾返回、引擎释放槽位。
        val gate = TransferGate()
        gate.pause()
        val reached = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) {
            try {
                gate.checkpoint()
                reached += "continued"
            } catch (_: PauseSignal) {
                reached += "paused"
            } catch (_: VfsException.Cancelled) {
                reached += "cancelled"
            }
        }
        withTimeout(3_000) { job.join() }
        assertEquals("暂停必须立即返回，不能原地挂起", listOf("paused"), reached.toList())

        // 暂停中取消：取消是第一优先级
        gate.cancel()
        val afterCancel = CopyOnWriteArrayList<String>()
        val job2 = launch(Dispatchers.Default) {
            try {
                gate.checkpoint()
                afterCancel += "continued"
            } catch (_: PauseSignal) {
                afterCancel += "paused"
            } catch (_: VfsException.Cancelled) {
                afterCancel += "cancelled"
            }
        }
        withTimeout(3_000) { job2.join() }
        assertEquals("暂停中取消必须报 Cancelled", listOf("cancelled"), afterCancel.toList())
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
    fun `暂停让出并发位：后续任务能先跑，恢复后原任务接上`() = runBlocking {
        // v2.2.5：暂停 → run() 收到 PauseSignal 返回 → worker 释放槽位；resume() 重新入队
        // 并从断点继续。旧语义是「暂停的任务占着并发位」（并发 2 时暂停两个任务，后续永远排队）。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src")
            .file("/src/big.bin", 1_000_000)
            .file("/src/other.bin", 10)
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

        // 放行被 readGate 卡住的读：任务会在下一个 checkpoint 抛 PauseSignal 并让出槽位
        gate.complete(Unit)

        val t2 = engine.enqueue(
            TransferRequest(listOf(uri("srcv", "/src/other.bin")), uri("dstv", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(15_000) { while (t2.state.value !is TaskState.Done) delay(10) }
        assertTrue("暂停的任务不该占位：后续任务必须能跑完", t2.state.value is TaskState.Done)
        assertEquals("t1 仍是已暂停", TaskState.Paused, t1.state.value)

        t1.resume()
        withTimeout(15_000) { while (t1.state.value !is TaskState.Done) delay(10) }
        scope.cancel()
    }
}
