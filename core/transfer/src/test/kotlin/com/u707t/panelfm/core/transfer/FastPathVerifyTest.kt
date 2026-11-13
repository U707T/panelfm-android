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
 * 校验档位与「服务端快路径」的互斥关系（v2.4.1）。
 *
 * 背景：`TransferTask.run()` 里有一条 v1.0.6 埋下的闸门
 * `plan.fastPath != NONE && request.verify == NONE`。v2.4.0 把默认校验档位从
 * 从未生效的 NONE 改成 SIZE 之后，**所有同卷 MOVE 都被这条闸门拦掉**，rename
 * 退化成「读 + 写 + 删」。这组测试把新的判定钉住：
 *  - SIZE / HASH 下 `SERVER_MOVE` 仍然走 rename（不读字节）；
 *  - HASH 下 `SERVER_COPY` 仍必须降级到慢路径（内容校验需要真读）。
 */
class FastPathVerifyTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    private fun engine(scope: CoroutineScope, locator: FakeLocator) = TransferEngine(
        planner = FileOperationPlanner(locator),
        locator = locator,
        resumeStore = InMemoryResumeStore(),
        dispatchers = PanelDispatchers(main = Dispatchers.Default),
        scope = scope,
        maxConcurrent = 1,
    )

    @Test
    fun `SIZE 校验下的同卷 MOVE 仍然走 rename 快路径`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").file("/src/a.bin", 4096).dir("/dst")
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.bin")),
                destDir = uri("one", "/dst"),
                op = TransferOp.MOVE,
                conflict = ConflictPolicy.OVERWRITE,
                verify = VerifyMode.SIZE,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertEquals("rename 不该打开读流（打开过 = 走了慢路径）", 0, vfs.openedReaders)
        assertTrue("目标应已出现", vfs.nodes.containsKey("/dst/a.bin"))
        assertTrue("源应已被移走", !vfs.nodes.containsKey("/src/a.bin"))
        assertEquals(1, (task.state.value as TaskState.Done).ok)
        scope.cancel()
    }

    @Test
    fun `HASH 校验下同卷 COPY 必须降级到慢路径`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vfs = FakeVfs().dir("/src").file("/src/a.bin", 4096).dir("/dst")
        val locator = FakeLocator(mapOf("one" to vfs))
        val engine = engine(scope, locator)

        val task = engine.enqueue(
            TransferRequest(
                sources = listOf(uri("one", "/src/a.bin")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.OVERWRITE,
                verify = VerifyMode.HASH,
            )
        )
        withTimeout(10_000) { while (task.state.value !is TaskState.Done) delay(10) }

        assertTrue("HASH 必须真读内容（源与目标各一遍）", vfs.openedReaders > 0)
        assertTrue(vfs.nodes.containsKey("/dst/a.bin"))
        scope.cancel()
    }
}
