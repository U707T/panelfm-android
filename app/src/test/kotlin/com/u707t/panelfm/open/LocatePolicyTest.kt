package com.u707t.panelfm.open

import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「定位还是复制」的策略回归：出错时只会退化成复制，不会让收文件失败。 */
class LocatePolicyTest {

    @Test
    fun `开关打开且路径可浏览时定位`() {
        val decision = decideLocate(true, "/storage/emulated/0/Download/a.pdf")
        assertEquals(LocateDecision.Locate("emulated", "/Download/a.pdf"), decision)
    }

    @Test
    fun `外置卡定位到 vol 卷`() {
        assertEquals(
            LocateDecision.Locate("vol:storage_1234-ABCD", "/movie/b.mp4"),
            decideLocate(true, "/storage/1234-ABCD/movie/b.mp4"),
        )
    }

    @Test
    fun `开关关闭时一律复制`() {
        assertEquals(LocateDecision.Copy, decideLocate(false, "/storage/emulated/0/Download/a.pdf"))
    }

    @Test
    fun `反查不到时复制`() {
        assertEquals(LocateDecision.Copy, decideLocate(true, null))
        assertEquals(LocateDecision.Copy, decideLocate(true, ""))
        assertEquals(LocateDecision.Copy, decideLocate(true, "primary:Download/a.pdf"))
    }

    @Test
    fun `受保护目录复制而不是定位`() {
        assertEquals(
            LocateDecision.Copy,
            decideLocate(true, "/storage/emulated/0/Android/data/com.tencent.mm/files/a.pdf"),
        )
        assertEquals(LocateDecision.Copy, decideLocate(true, "/data/local/tmp/x"))
    }

    @Test
    fun `根目录不定位`() {
        assertEquals(LocateDecision.Copy, decideLocate(true, "/"))
    }

    // ------------------------------------------------------------------ 批量结果

    private fun item(name: String, copy: Boolean) = IncomingState.BatchItem(
        name = name,
        uri = VfsUri.of("local", if (copy) "incoming" else "emulated", "/$name"),
        isCopy = copy,
        mime = null,
        size = 1,
    )

    @Test
    fun `全部失败时给出第一条原因`() {
        val result = batchResultOf(emptyList(), listOf("a.pdf：没有读取权限", "b.txt：文件不存在"))
        assertTrue(result is IncomingState.Error)
        assertEquals("a.pdf：没有读取权限", (result as IncomingState.Error).message)
    }

    @Test
    fun `一项都没成功且没有原因时也有兜底文案`() {
        val result = batchResultOf(emptyList(), emptyList())
        assertEquals("接收失败", (result as IncomingState.Error).message)
    }

    @Test
    fun `部分成功时保留失败明细`() {
        val result = batchResultOf(listOf(item("a.pdf", copy = false)), listOf("b.txt：没有读取权限"))
        assertTrue(result is IncomingState.Batch)
        val batch = result as IncomingState.Batch
        assertEquals(1, batch.items.size)
        assertEquals(1, batch.failed.size)
        assertTrue(!batch.items.first().isCopy)
    }
}
