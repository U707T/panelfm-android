package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「长按动作菜单」目标项的回归测试（v1.9.1 修的一个高危 bug）。
 *
 * 背景：v1.9.0 把长按改成「只弹菜单、不再设选择」之后，菜单里的
 * 复制 / 移动 / 删除 / 压缩 / 加入剪贴板 全部退化成了 `targetSources()` —— 后者在
 * **没有选择**时返回**整个目录**：长按一个文件点「删除」会删掉整个目录的内容。
 *
 * 这条规则必须一直成立：**菜单目标永远来自「按下的那一项」或「包含它的选择集」，
 * 绝不来自整张列表。**
 */
class MenuTargetsTest {

    private fun item(name: String) = FileMetadata(
        uri = VfsUri.parse("local:///dir/$name"),
        name = name,
        isDirectory = false,
        size = 1,
        lastModified = 0,
    )

    private val a = item("a.txt")
    private val b = item("b.txt")
    private val c = item("c.txt")

    @Test
    fun `没有选择时只作用于按下的这一项`() {
        assertEquals(listOf(a), menuTargets(emptyList(), a))
    }

    @Test
    fun `按下的项不在选择集里时只作用于这一项（不碰选择集）`() {
        assertEquals(listOf(a), menuTargets(listOf(b, c), a))
    }

    @Test
    fun `按下的项在选择集里时作用于整个选择集`() {
        assertEquals(listOf(b, c), menuTargets(listOf(b, c), c))
        assertEquals(listOf(b), menuTargets(listOf(b), b))
    }

    @Test
    fun `目标是稳定标识：同名不同目录不会串味`() {
        val other = FileMetadata(
            uri = VfsUri.parse("local:///other/a.txt"),
            name = "a.txt",
            isDirectory = false,
            size = 1,
            lastModified = 0,
        )
        // 选择集里是「另一个目录的同名文件」→ 不能把它当成按下的这一项
        assertEquals(listOf(a), menuTargets(listOf(other), a))
    }

    @Test
    fun `目标永远非空：这是「不许退化成整个目录」的核心断言`() {
        listOf(emptyList(), listOf(b), listOf(b, c)).forEach { selection ->
            val targets = menuTargets(selection, a)
            assertEquals(1, targets.size)
            assertEquals(a, targets.single())
        }
    }
}
