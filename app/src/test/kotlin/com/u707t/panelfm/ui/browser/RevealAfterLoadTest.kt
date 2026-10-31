package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「跳转到文件」/「搜索结果定位」的加载后定位：
 * 目标必须等**新目录的列表**回来才落成选择 + 滚动，不能在旧列表上就把请求消费掉。
 */
class RevealAfterLoadTest {

    private fun file(path: String) = FileMetadata(
        uri = VfsUri.parse("local://emulated/$path"),
        name = path.substringAfterLast('/'),
        isDirectory = false,
    )

    @Test
    fun `目标在新列表里时选中并滚动`() {
        val target = file("Download/a.txt").uri.toString()
        val state = PaneState(revealUri = target)
        val loaded = listOf(file("Download/b.txt"), file("Download/a.txt"))

        val after = state.applyPendingReveal(loaded)

        assertEquals(setOf(target), after.selection)
        assertEquals(target, after.scrollToUri)
        assertNull(after.revealUri)
    }

    @Test
    fun `目标不在新列表里时只清请求不动已有选择`() {
        val keep = file("Download/keep.txt").uri.toString()
        val state = PaneState(revealUri = file("Download/gone.txt").uri.toString(), selection = setOf(keep))
        val after = state.applyPendingReveal(listOf(file("Download/other.txt")))

        assertNull(after.revealUri)
        assertEquals(setOf(keep), after.selection)
        assertNull(after.scrollToUri)
    }

    @Test
    fun `没有待定位请求时原样返回`() {
        val state = PaneState()
        assertSame(state, state.applyPendingReveal(listOf(file("x.txt"))))
    }

    @Test
    fun `目录同样可以定位`() {
        val dir = FileMetadata.dir(VfsUri.parse("local://emulated/Download/Sub"), "Sub")
        val state = PaneState(revealUri = dir.uri.toString())
        val after = state.applyPendingReveal(listOf(dir))
        assertEquals(setOf(dir.uri.toString()), after.selection)
        assertEquals(dir.uri.toString(), after.scrollToUri)
    }
}
