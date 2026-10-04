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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮修复的回归测试（v0.13.0-rc.9）：
 *  1. 覆盖自身保护（把「复制」到源自身的父目录时绝不能先删目标）
 *  2. 续传记录按路径查找（旧实现按任务 id，永远命中不了）
 *  3. 并发下调后实际并发度不超过设定值
 */
class TransferRegressionTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun engine(scope: CoroutineScope, locator: FakeLocator, store: ResumeStore = InMemoryResumeStore()) =
        TransferEngine(
            planner = FileOperationPlanner(locator),
            locator = locator,
            resumeStore = store,
            dispatchers = PanelDispatchers(main = Dispatchers.Default),
            scope = scope,
            maxConcurrent = 1,
        )

    @Test
    fun `覆盖自身时不删除源（防数据丢失）`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 同会话 → 服务端快路径；源与目标同名（destDir 就是源所在目录）
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/src"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        // 源文件必须还在（旧实现会先 delete 目标 = 删掉源本身）
        assertTrue("源文件必须仍然存在", vfs.nodes.containsKey("/src/a.txt"))
        scope.cancel()
    }

    @Test
    fun `续传记录按 source-dest 查找（跨任务可命中）`() {
        val store = InMemoryResumeStore()
        val src = uri("one", "/a.bin")
        val dst = uri("one", "/b.bin")
        runBlocking {
            store.save(
                ResumeEntry(
                    taskId = "task-1", itemIndex = 0, source = src, dest = dst,
                    tempUri = null, offset = 100, total = 200, validator = null,
                    updatedAt = 1L,
                )
            )
            // 换一个任务 id 仍然能查到（旧接口按 taskId 查找 → 永远 null）
            val found = store.findFor(src, dst)
            assertEquals(100L, found?.offset)
            store.clearFor(src, dst)
            assertTrue(store.findFor(src, dst) == null)
        }
    }

    @Test
    fun `并发下调后运行中的任务数不超过新设定`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 两个慢 VFS 会话（不同会话 → 慢路径，transferFile 有 gate.checkpoint 可挂起）
        val a = FakeVfs().dir("/src").file("/src/a.bin", 100)
        val b = FakeVfs().dir("/dst")
        val locator = FakeLocator(mapOf("a" to a, "b" to b))
        val engine = engine(scope, locator)
        engine.updateConcurrency(1)

        val t1 = engine.enqueue(TransferRequest(listOf(uri("a", "/src/a.bin")), uri("b", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE))
        val t2 = engine.enqueue(TransferRequest(listOf(uri("a", "/src/a.bin")), uri("b", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE))

        // 并发上限是 1：任意时刻最多一个任务处于 Running（含完成后立即）
        withTimeout(15_000) {
            while (t1.state.value !is TaskState.Done || t2.state.value !is TaskState.Done) {
                val running = listOf(t1, t2).count { it.state.value is TaskState.Running }
                assertTrue("同时运行的任务数应 ≤ 1，实际 $running", running <= 1)
                delay(5)
            }
        }
        scope.cancel()
    }
}
