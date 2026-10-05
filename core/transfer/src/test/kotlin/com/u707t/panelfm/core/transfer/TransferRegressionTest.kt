package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.model.VerifyMode
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
    fun `跨会话复制目录保留两者时整棵子树都重映射`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs()
            .dir("/src")
            .dir("/src/folder")
            .file("/src/folder/a.txt", 3)
        val dstVfs = FakeVfs()
            .dir("/dst")
            .dir("/dst/folder")
            .file("/dst/folder/old.txt", 2)
        val locator = FakeLocator(mapOf("src" to srcVfs, "dst" to dstVfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("src", "/src/folder")),
                destDir = uri("dst", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.KEEP_BOTH,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertTrue("原目录不能被覆盖", dstVfs.nodes.containsKey("/dst/folder/old.txt"))
        assertTrue("子文件必须进入保留两者目录", dstVfs.nodes.containsKey("/dst/folder (1)/a.txt"))
        assertTrue("旧目标目录不能被误写入", !dstVfs.nodes.containsKey("/dst/folder/a.txt"))
        scope.cancel()
    }

    @Test
    fun `跨会话复制目录跳过时不应继续写入子项`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").dir("/src/folder").file("/src/folder/a.txt", 3)
        val dstVfs = FakeVfs().dir("/dst").dir("/dst/folder").file("/dst/folder/old.txt", 2)
        val locator = FakeLocator(mapOf("src" to srcVfs, "dst" to dstVfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("src", "/src/folder")),
                destDir = uri("dst", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.SKIP,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertTrue("跳过目录后不能出现新子项", !dstVfs.nodes.containsKey("/dst/folder/a.txt"))
        assertTrue("已有目标必须保留", dstVfs.nodes.containsKey("/dst/folder/old.txt"))
        scope.cancel()
    }

    @Test
    fun `覆盖删除失败时不能继续写入目标`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 3)
            .dir("/dst")
            .file("/dst/a.txt", 2)
        vfs.failDelete = true
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Failed) delay(10) }

        assertTrue("源文件必须仍在", vfs.nodes.containsKey("/src/a.txt"))
        assertEquals("目标不能在删除失败后被打开写入", 2L, vfs.nodes["/dst/a.txt"]?.size)
        scope.cancel()
    }

    @Test
    fun `目标 openWrite 失败时源 reader 仍然关闭`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val dstVfs = FakeVfs().dir("/dst")
        dstVfs.failOpenWrite = true
        val locator = FakeLocator(mapOf("src" to srcVfs, "dst" to dstVfs))
        val engine = engine(scope, locator)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("src", "/src/a.txt")),
                destDir = uri("dst", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertEquals("openWrite 异常路径不能泄漏 reader", srcVfs.openedReaders, srcVfs.closedReaders)
        val state = task.state.value as TaskState.Done
        assertEquals(1, state.failed)
        scope.cancel()
    }

    @Test
    fun `VerifyMode HASH 实际校验传输内容`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/a.bin", 1024)
        val dstVfs = FakeVfs().dir("/dst")
        val locator = FakeLocator(mapOf("src" to srcVfs, "dst" to dstVfs))
        val engine = engine(scope, locator)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("src", "/src/a.bin")),
                destDir = uri("dst", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
                verify = VerifyMode.HASH,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(0, state.failed)
        assertTrue("校验后的目标内容应存在", dstVfs.nodes.containsKey("/dst/a.bin"))
        assertTrue(srcVfs.nodes["/src/a.bin"]!!.content.toByteArray().contentEquals(dstVfs.nodes["/dst/a.bin"]!!.content.toByteArray()))
        scope.cancel()
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
