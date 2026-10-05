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
}
