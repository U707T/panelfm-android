package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.model.VerifyMode
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
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
 *  4. 覆盖语义（第 4 批审计 🟡1/🟡2）：文件覆盖文件夹 = 整目录删除；可续传目标在传输
 *     失败 / 取消后**旧文件仍在**（不再预删）；不可续传目标保留「预删失败不写入」保险
 *  5. FakeVfs 与真实协议对齐：写入落 `.part`、commit 才替换正式名
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
    fun `服务端复制返回 false 时降级为流式复制`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 16)
            .dir("/dst")
        vfs.failServerSideCopy = true
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
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals("服务端能力返回 false 后，流式降级不应失败", 0, state.failed)
        assertTrue("降级后目标文件必须存在", vfs.nodes.containsKey("/dst/a.txt"))
        assertTrue(
            "降级后的内容必须与源一致",
            vfs.nodes["/src/a.txt"]!!.content.toByteArray().contentEquals(vfs.nodes["/dst/a.txt"]!!.content.toByteArray()),
        )
        scope.cancel()
    }

    @Test
    fun `服务端移动返回 false 时降级为复制后删除源`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 16)
            .dir("/dst")
        vfs.failRename = true
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.MOVE,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(0, state.failed)
        assertTrue("降级移动后目标文件必须存在", vfs.nodes.containsKey("/dst/a.txt"))
        assertTrue("降级移动成功后源文件必须删除", !vfs.nodes.containsKey("/src/a.txt"))
        scope.cancel()
    }

    @Test
    fun `快路径同名文件 KEEP_BOTH 和 SKIP 都遵守冲突策略`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 4)
            .dir("/dst")
            .file("/dst/a.txt", 2)
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val keepBoth = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.KEEP_BOTH,
            )
        )
        withTimeout(10_000) { while (keepBoth.state.value !is TaskState.Done) delay(10) }
        assertTrue("快路径 KEEP_BOTH 必须保留原目标", vfs.nodes.containsKey("/dst/a.txt"))
        assertTrue("快路径 KEEP_BOTH 必须创建新目标", vfs.nodes.containsKey("/dst/a (1).txt"))

        val skip = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.txt")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.SKIP,
            )
        )
        withTimeout(10_000) { while (skip.state.value !is TaskState.Done) delay(10) }
        assertTrue("快路径 SKIP 不能再创建第三个副本", !vfs.nodes.containsKey("/dst/a (2).txt"))
        scope.cancel()
    }

    @Test
    fun `目录覆盖目录走慢路径合并而不是先删除整个目标`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .dir("/src/folder")
            .file("/src/folder/new.txt", 4)
            .dir("/dst")
            .dir("/dst/folder")
            .file("/dst/folder/old.txt", 3)
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/folder")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(0, state.failed)
        assertTrue("目录覆盖必须保留目标目录内原有文件", vfs.nodes.containsKey("/dst/folder/old.txt"))
        assertTrue("源文件必须复制进已有目标目录", vfs.nodes.containsKey("/dst/folder/new.txt"))
        scope.cancel()
    }

    @Test
    fun `删除静默成功但目标仍在时不能写入目标`() = runBlocking {
        // 变异测试漏网点（审计 M2）：「delete() 返回成功 ≠ 目标已消失」这条保险没有被测到 ——
        // 只有「delete 抛异常」的分支有测试。这里补上另一半。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 3)
            .dir("/dst")
            .file("/dst/a.txt", 2)
        vfs.silentlyIgnoreDelete = true
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
        assertEquals("目标不能在「删除静默失败」后被覆盖写入", 2L, vfs.nodes["/dst/a.txt"]?.size)
        scope.cancel()
    }

    @Test
    fun `跨挂载点同路径时不套用别的会话的子目录映射`() = runBlocking {
        // 变异测试漏网点（审计 M3）：isSameOrDescendant() 里的 sameMount 守卫被删掉时全绿。
        // 场景：两个不同会话里有**同路径**的源（b:/src/folder/a.txt 与 c:/src/folder），
        // 其中一个（c 的目录）因 KEEP_BOTH 被重映射到「folder (1)」。
        // 若丢了挂载点判断，b 的文件会被误判成 c 目录的后代，从而被写进 folder (1) 里
        // —— 位置错了，而且和用户的预期（文件落在目标目录根下）不一致。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val destVfs = FakeVfs()
            .dir("/dst")
            .dir("/dst/folder")
            .file("/dst/folder/old.txt", 2)
        val fileVfs = FakeVfs().dir("/src").dir("/src/folder").file("/src/folder/a.txt", 3)
        val dirVfs = FakeVfs().dir("/src").dir("/src/folder").file("/src/folder/c.txt", 1)
        val locator = FakeLocator(mapOf("dest" to destVfs, "b" to fileVfs, "c" to dirVfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("b", "/src/folder/a.txt"), uri("c", "/src/folder")),
                destDir = uri("dest", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.KEEP_BOTH,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertTrue("b 的文件必须落在目标目录根下", destVfs.nodes.containsKey("/dst/a.txt"))
        assertTrue("c 的目录按 KEEP_BOTH 另建", destVfs.nodes.containsKey("/dst/folder (1)/c.txt"))
        assertTrue(
            "不能把 b 的文件塞进 c 的映射目录（跨挂载点误判）",
            !destVfs.nodes.containsKey("/dst/folder (1)/a.txt"),
        )
        assertTrue("原有目标目录必须保留", destVfs.nodes.containsKey("/dst/folder/old.txt"))
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

    @Test
    fun `文件覆盖文件夹时整目录删除并写入文件（对话框警示的语义回归）`() = runBlocking {
        // 第 4 批审计 🟡1：这个组合的「替换」实质 = 递归删除整个文件夹。行为不变，
        // 但对话框文案 / 默认项（默认「跳过」）与这条断言对齐，并钉住回归。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/a.txt", 4)
        val dstVfs = FakeVfs()
            .dir("/dst").dir("/dst/a.txt").file("/dst/a.txt/inner.txt", 2)
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = engine(scope, locator)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("srcv", "/src/a.txt")),
                destDir = uri("dstv", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(0, state.failed)
        val node = dstVfs.nodes["/dst/a.txt"]
        assertTrue("同名的文件夹必须被整体删除后写入文件", node != null && !node.isDirectory)
        assertTrue("原文件夹内的子项不能残留", !dstVfs.nodes.containsKey("/dst/a.txt/inner.txt"))
        assertEquals("文件内容来自源", 4L, node?.size)
        assertTrue(
            "内容必须与源一致",
            srcVfs.nodes["/src/a.txt"]!!.content.toByteArray()
                .contentEquals(node!!.content.toByteArray()),
        )
        assertTrue("part 不能残留", !dstVfs.nodes.containsKey("/dst/.a.txt.panelfm.part"))
        scope.cancel()
    }

    @Test
    fun `覆盖中途失败时旧目标文件仍在（可续传目标不预删）`() = runBlocking {
        // 第 4 批审计 🟡2：旧实现「先删旧文件、再传输」——中途失败 = 旧版本永久丢失。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/a.bin", 64)
        srcVfs.failRead = true // 传输一开头就断流
        val dstVfs = FakeVfs().dir("/dst").file("/dst/a.bin", 8) // 旧版本
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = engine(scope, locator)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("srcv", "/src/a.bin")),
                destDir = uri("dstv", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(1, state.failed)
        assertEquals("失败后旧目标必须原样保留（不预删）", 8L, dstVfs.nodes["/dst/a.bin"]?.size)
        scope.cancel()
    }

    @Test
    fun `覆盖中途取消时旧目标文件仍在且保留断点`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val srcVfs = FakeVfs().dir("/src").file("/src/big.bin", 1_000_000)
        val gate = CompletableDeferred<Unit>()
        srcVfs.readGate = gate
        val dstVfs = FakeVfs().dir("/dst").file("/dst/big.bin", 5) // 旧版本
        val store = InMemoryResumeStore()
        val locator = FakeLocator(mapOf("srcv" to srcVfs, "dstv" to dstVfs))
        val engine = engine(scope, locator, store)
        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("srcv", "/src/big.bin")),
                destDir = uri("dstv", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
            )
        )
        withTimeout(15_000) { while (srcVfs.readsStarted.get() == 0) delay(5) }
        task.cancel()
        gate.complete(Unit)
        withTimeout(10_000) { while (task.state.value !is TaskState.Cancelled) delay(10) }

        assertEquals("取消后旧目标必须原样保留（不预删）", 5L, dstVfs.nodes["/dst/big.bin"]?.size)
        assertTrue(
            "断点记录必须保留（下次入队从 .part 接上）",
            store.findFor(uri("srcv", "/src/big.bin"), uri("dstv", "/dst/big.bin")) != null,
        )
        scope.cancel()
    }

    @Test
    fun `不可续传目标：预删失败时不能继续写入目标`() = runBlocking {
        // 对照「可续传目标不预删」：不可续传目标（FTP / WebDAV / S3 / 压缩包）保留预删，
        // 预删失败必须中断写入（旧承诺，不变）。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs(capabilities = VfsCapabilities(resumable = Resumability.NONE))
            .dir("/src").file("/src/a.txt", 3)
            .dir("/dst").file("/dst/a.txt", 2)
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
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        val state = task.state.value as TaskState.Done
        assertEquals(1, state.failed)
        assertEquals("预删失败后目标保持原样", 2L, vfs.nodes["/dst/a.txt"]?.size)
        scope.cancel()
    }
}
