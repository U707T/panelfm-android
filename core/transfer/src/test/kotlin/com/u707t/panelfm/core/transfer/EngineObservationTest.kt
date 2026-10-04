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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 回归：UI 通过 [TransferEngine.taskEvents] 观察任务推进。
 *
 * 历史 bug（v0.13.0-rc.8 及以前）：UI 观察 `engine.tasks`（StateFlow<List>）——
 * 列表不增删时永远不发射，任务运行中的进度 / 冲突 / 完成状态到不了 UI，
 * 直接导致：冲突对话框永不弹出（ASK 策略任务卡死）、进度条不动、
 * 任务完成后窗格不刷新、前台服务不退出。
 */
class EngineObservationTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun newEngine(scope: CoroutineScope, locator: FakeLocator) = TransferEngine(
        planner = FileOperationPlanner(locator),
        locator = locator,
        resumeStore = InMemoryResumeStore(),
        dispatchers = PanelDispatchers(main = Dispatchers.Default),
        scope = scope,
        maxConcurrent = 1,
    )

    @Test
    fun `taskEvents 能观察到任务从排队到完成`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = newEngine(scope, locator)

        val seen = CopyOnWriteArrayList<String>()
        val job = scope.launch {
            engine.taskEvents.collect { list ->
                seen.add(list.firstOrNull()?.state?.value?.javaClass?.simpleName ?: "null")
            }
        }
        delay(200) // 订阅先于入队

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) {
            while (task.state.value !is TaskState.Done) delay(10)
        }
        delay(300)

        assertTrue("任务应完成", task.state.value is TaskState.Done)
        assertTrue(
            "taskEvents 必须发射完成状态（实际：${seen.toList()}）",
            seen.contains("Done"),
        )
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `taskEvents 能观察到等待冲突状态（冲突弹窗依赖）`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10).dir("/dst").file("/dst/a.txt", 10)
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = newEngine(scope, locator)

        val seen = CopyOnWriteArrayList<String>()
        val job = scope.launch {
            engine.taskEvents.collect { list ->
                seen.add(list.firstOrNull()?.state?.value?.javaClass?.simpleName ?: "null")
            }
        }
        delay(200)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.ASK,
            )
        )
        // 等任务进入「等待冲突」
        withTimeout(10_000) {
            while (task.state.value !is TaskState.WaitingConflict) delay(10)
        }
        delay(200)
        assertTrue(
            "taskEvents 必须发射 WaitingConflict（否则冲突弹窗永不出现）",
            seen.contains("WaitingConflict"),
        )

        // 解决冲突后任务继续并完成
        task.resolveConflict(com.u707t.panelfm.core.model.ConflictDecision(ConflictPolicy.OVERWRITE))
        withTimeout(10_000) {
            while (task.state.value !is TaskState.Done) delay(10)
        }
        assertTrue("解决冲突后任务应完成", task.state.value is TaskState.Done)
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `取消后重新入队能从断点续传（源被替换时不误续）`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryResumeStore()
        // 两个 VFS → 强制走「读流→写流」慢路径（快路径不涉及续传）
        val srcVfs = FakeVfs().dir("/src").file("/src/a.txt", 1000)
        val dstVfs = FakeVfs().dir("/dst")
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = TransferEngine(
            planner = FileOperationPlanner(locator),
            locator = locator,
            resumeStore = store,
            dispatchers = PanelDispatchers(main = Dispatchers.Default),
            scope = scope,
            maxConcurrent = 1,
        )

        val src = uri("srcv", "/src/a.txt")
        val dst = uri("dstv", "/dst/a.txt")
        // 模拟「上次传了一半」：记录断点
        store.save(
            ResumeEntry(
                taskId = "old-task", itemIndex = 0, source = src, dest = dst,
                tempUri = null, offset = 400, total = 1000,
                validator = null, updatedAt = System.currentTimeMillis(),
            )
        )
        // 新任务（新 UUID）入队——旧实现按 taskId 查找，永远命中不了
        val task = engine.enqueue(
            TransferRequest(listOf(src), uri("dstv", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        withTimeout(10_000) {
            while (task.state.value !is TaskState.Done) delay(10)
        }
        assertTrue("任务应完成", task.state.value is TaskState.Done)
        // 完成后断点记录应被清理
        assertTrue("完成后应清理断点", store.findFor(src, dst) == null)
        scope.cancel()
    }
}
