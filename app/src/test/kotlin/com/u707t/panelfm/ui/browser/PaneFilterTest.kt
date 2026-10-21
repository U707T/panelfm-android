package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤可取消（v1.3.5 修复的回归锁）：
 *  - 只清关键字不够 —— 类型过滤必须一起清，否则列表仍被卡住；
 *  - 清完之后 `filtered` 必须回到 false。
 */
class PaneFilterTest {

    private fun pane(search: String = "", kind: String? = null) =
        PaneState(tabs = listOf(PaneTab(com.u707t.panelfm.core.vfs.VfsUri("local", "emulated", "/"))))
            .copy(search = search, filterKind = kind)

    @Test
    fun `只设关键字时清除后恢复`() {
        val p = pane(search = "abc")
        assertTrue(p.filtered)
        val cleared = p.clearedFilter()
        assertEquals("", cleared.search)
        assertFalse(cleared.filtered)
    }

    @Test
    fun `只有类型过滤时也能清除`() {
        val p = pane(kind = "IMAGE")
        assertTrue(p.filtered)
        val cleared = p.clearedFilter()
        assertEquals(null, cleared.filterKind)
        assertFalse(cleared.filtered)
    }

    @Test
    fun `关键字与类型过滤同时存在时一起清除`() {
        val p = pane(search = "abc", kind = "VIDEO")
        val cleared = p.clearedFilter()
        assertEquals("", cleared.search)
        assertEquals(null, cleared.filterKind)
        assertFalse(cleared.filtered)
    }

    @Test
    fun `无过滤时清除是空操作`() {
        val p = pane()
        assertFalse(p.filtered)
        assertEquals(p, p.clearedFilter())
    }

    // ---------------- 过滤是纯客户端条件：改条件只重算内存列表，不重拉目录 ----------------

    private fun file(name: String, dir: Boolean = false) =
        com.u707t.panelfm.core.vfs.FileMetadata(
            uri = com.u707t.panelfm.core.vfs.VfsUri("local", "emulated", "/$name"),
            name = name,
            isDirectory = dir,
            size = if (dir) -1L else 1L,
        )

    @Test
    fun `关键字过滤只筛内存列表`() {
        val all = listOf(file("a.txt"), file("b.jpg"), file("c.txt"))
        assertEquals(listOf("a.txt", "c.txt"), applyPaneFilter(all, ".txt", null).map { it.name })
    }

    @Test
    fun `类型过滤保留目录与无后缀文件`() {
        val all = listOf(file("dir", dir = true), file("a.jpg"), file("b.txt"), file("README"))
        val out = applyPaneFilter(all, "", "IMAGE").map { it.name }
        // 目录与无后缀项不受类型过滤影响（否则用户连「进上级」都找不到）
        assertTrue(out.containsAll(listOf("dir", "README")))
        assertTrue(out.contains("a.jpg"))
        assertFalse(out.contains("b.txt"))
    }

    @Test
    fun `关键字与类型过滤叠加`() {
        val all = listOf(file("cat.jpg"), file("dog.jpg"), file("cat.txt"))
        assertEquals(listOf("cat.jpg"), applyPaneFilter(all, "cat", "IMAGE").map { it.name })
    }

    @Test
    fun `清空条件后必须能回到完整列表`() {
        val all = listOf(file("a.jpg"), file("b.txt"))
        val filtered = applyPaneFilter(all, "a", "IMAGE")
        assertEquals(1, filtered.size)
        // 这是「过滤设上就取消不了」那类 bug 的防线：源列表必须原样可用
        assertEquals(2, applyPaneFilter(all, "", null).size)
    }

    @Test
    fun `过滤支持通配符 正则与否定`() {
        val all = listOf(file("a.log"), file("b.txt"), file("c.log"), file("d.LOG"))
        assertEquals(
            listOf("a.log", "c.log", "d.LOG"),
            applyPaneFilter(all, "*.log", null).map { it.name },
        )
        assertEquals(listOf("a.log", "c.log"), applyPaneFilter(all, "/^[ac]\\.log$", null).map { it.name })
        assertEquals(listOf("b.txt"), applyPaneFilter(all, "!*.log", null).map { it.name })
    }
}
