package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务结束后自动收场（v1.5.0「传输任务 ui 不消失」的修法）：
 *  - 完成 / 已取消：保留片刻供用户看到结果，然后自动移除；
 *  - 失败：**不自动移除**（错误必须留痕，由用户「移除 / 清空」处理）。
 */
class TaskAutoDismissTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun engine(scope: CoroutineScope, locator: FakeLocator, retentionMs: Long) = TransferEngine(
        planner = FileOperationPlanner(locator),
        locator = locator,
        resumeStore = InMemoryResumeStore(),
        dispatchers = PanelDispatchers(main = Dispatchers.Default),
        scope = scope,
        maxConcurrent = 1,
        finishedRetentionMs = retentionMs,
    )

    @Test
    fun `完成的任务在保留期后被自动移除`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10).dir("/dst")
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator, retentionMs = 2_000)

        val task = engine.enqueue(
            TransferRequest(listOf(uri("one", "/src/a.txt")), uri("one", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        delay(200)
        assertTrue("保留期内必须还能看到任务结果", engine.tasks.value.contains(task))

        withTimeout(10_000) { while (engine.tasks.value.contains(task)) delay(10) }
        assertFalse("保留期后应自动收走（任务条/列表不再常驻）", engine.tasks.value.contains(task))
        // 任务对象本身的状态不受影响（保留期到期只是清列表，不是改状态）
        assertTrue(task.state.value is TaskState.Done)
        scope.cancel()
    }

    @Test
    fun `失败的任务不会被自动移除`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").dir("/dst")
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator, retentionMs = 200)

        // 源不存在 → 任务失败
        val task = engine.enqueue(
            TransferRequest(listOf(uri("one", "/src/missing.txt")), uri("one", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Failed) delay(10) }

        delay(600) // 远超保留期
        assertTrue("失败任务必须留痕（不能自动消失）", engine.tasks.value.contains(task))
        scope.cancel()
    }

    @Test
    fun `取消的任务同样在保留期后自动收走`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 两个 VFS → 强制走「读流 → 写流」慢路径，并且用 readGate 把取消点确定性地钉在
        // 传输进行中：等到第一次读真的开始（此时任务不可能自己完成），再取消、再放行。
        // 快路径（同 VFS 的 serverSideCopy）不经过 read()，整条路径没有读停滞点，
        // 直接「入队后立刻取消」可能赶不上 —— v1.5.0 首次 CI 上撞到的竞态。
        val srcVfs = FakeVfs().dir("/src").file("/src/big.bin", 1_000_000)
        val dstVfs = FakeVfs().dir("/dst")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        srcVfs.readGate = gate
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = engine(scope, locator, retentionMs = 500)

        val task = engine.enqueue(
            TransferRequest(listOf(uri("srcv", "/src/big.bin")), uri("dstv", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(15_000) { while (srcVfs.readsStarted.get() == 0) delay(5) }
        task.cancel()
        gate.complete(Unit)

        withTimeout(10_000) { while (task.state.value !is TaskState.Cancelled) delay(10) }

        withTimeout(10_000) { while (engine.tasks.value.contains(task)) delay(10) }
        assertFalse("已取消的任务也该收场", engine.tasks.value.contains(task))
        scope.cancel()
    }
}
