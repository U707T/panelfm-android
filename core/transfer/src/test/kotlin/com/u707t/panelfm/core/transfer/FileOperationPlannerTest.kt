package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationPlannerTest {

    private fun uri(authority: String, path: String) = VfsUri.of("fake", authority, path)

    @Test
    fun `递归枚举目录并统计大小`() = runTest {
        val vfs = FakeVfs()
            .dir("/src")
            .file("/src/a.txt", 100)
            .dir("/src/sub")
            .file("/src/sub/b.bin", 250)

        val planner = FileOperationPlanner(FakeLocator(mapOf("one" to vfs)))
        val plan = planner.plan(
            TransferRequest(
                sources = listOf(uri("one", "/src")),
                destDir = uri("one", "/dst"),
                op = TransferOp.COPY,
                conflict = ConflictPolicy.ASK,
            )
        )

        // 目录自身 + a.txt + sub + b.bin
        assertEquals(4, plan.items.size)
        assertEquals(350L, plan.totalBytes)
        assertEquals(2, plan.fileCount)
        assertEquals(2, plan.dirCount)
        assertTrue(plan.items.first().isDirectory)
    }

    @Test
    fun `同会话复制走服务端快路径`() = runTest {
        val vfs = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val planner = FileOperationPlanner(FakeLocator(mapOf("one" to vfs)))
        val plan = planner.plan(
            TransferRequest(listOf(uri("one", "/src/a.txt")), uri("one", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        assertEquals(FastPath.SERVER_COPY, plan.fastPath)
    }

    @Test
    fun `跨会话退化为本地中转`() = runTest {
        val a = FakeVfs().dir("/src").file("/src/a.txt", 10)
        val b = FakeVfs().dir("/dst")
        val planner = FileOperationPlanner(FakeLocator(mapOf("one" to a, "two" to b)))
        val plan = planner.plan(
            TransferRequest(listOf(uri("one", "/src/a.txt")), uri("two", "/dst"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
        )
        assertEquals(FastPath.NONE, plan.fastPath)
    }

    @Test
    fun `不支持重命名的协议移动走慢路径`() = runTest {
        val noRename = FakeVfs(capabilities = VfsCapabilities(rename = false))
            .dir("/src")
            .file("/src/a.txt", 5)
        val planner = FileOperationPlanner(FakeLocator(mapOf("one" to noRename)))
        val plan = planner.plan(
            TransferRequest(listOf(uri("one", "/src/a.txt")), uri("one", "/dst"), TransferOp.MOVE, ConflictPolicy.OVERWRITE)
        )
        assertEquals(FastPath.NONE, plan.fastPath)
    }

    @Test
    fun `禁止把目录复制到自身内部`() = runTest {
        val vfs = FakeVfs().dir("/src").dir("/src/inner")
        val planner = FileOperationPlanner(FakeLocator(mapOf("one" to vfs)))
        var thrown = false
        try {
            planner.plan(
                TransferRequest(listOf(uri("one", "/src")), uri("one", "/src/inner"), TransferOp.COPY, ConflictPolicy.OVERWRITE)
            )
        } catch (e: VfsException.IllegalArgument) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
