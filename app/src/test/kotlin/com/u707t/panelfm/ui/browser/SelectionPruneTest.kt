package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 选择集与当前列表的一致性（v2.2.5）。
 *
 * 背景：`selection` 是 URI 集合，`items` 会因过滤 / 刷新整体换一批。两者脱节时，
 * 顶栏「已选: N」（读 selection.size）与动作（读 selectedItems，与 items 取交集）
 * 就不是同一个数 —— 用户看到「已选 5」按删除只删 1 个，或「已选 1」却提示没有可删除项。
 */
class SelectionPruneTest {

    private fun file(name: String) = FileMetadata(
        uri = VfsUri.of("local", "emulated", "/dir/$name"),
        name = name,
        isDirectory = false,
        size = 1,
    )

    @Test
    fun `列表换一批后选择集只保留仍然存在的项，失效锚点一起清掉`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val c = file("c.txt")
        val pane = PaneState(items = listOf(a, b, c)).copy(
            selection = setOf(a.uri.toString(), b.uri.toString(), c.uri.toString()),
            selectionAnchor = b.uri.toString(),
            tapAnchor = c.uri.toString(),
        )

        val pruned = pane.withSelectionPruned(listOf(a, b))

        assertEquals(setOf(a.uri.toString(), b.uri.toString()), pruned.selection)
        assertEquals(b.uri.toString(), pruned.selectionAnchor)
        assertNull("锚点指向被隐藏的项时必须一起清掉", pruned.tapAnchor)
    }

    @Test
    fun `选择集全在列表里时原样返回，不产生无谓重组`() {
        val a = file("a.txt")
        val pane = PaneState(items = listOf(a)).copy(selection = setOf(a.uri.toString()))
        assertSame(pane, pane.withSelectionPruned(listOf(a)))
    }

    @Test
    fun `列表为空时选择集清空（加载失败后不能留一个假的已选）`() {
        val a = file("a.txt")
        val pane = PaneState(items = listOf(a)).copy(
            selection = setOf(a.uri.toString()),
            selectionAnchor = a.uri.toString(),
        )

        val pruned = pane.withSelectionPruned(emptyList())

        assertEquals(emptySet<String>(), pruned.selection)
        assertNull(pruned.selectionAnchor)
        assertFalse("hasSelection 必须跟着变假", pruned.hasSelection)
    }
}
