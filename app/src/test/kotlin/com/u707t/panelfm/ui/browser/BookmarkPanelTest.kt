package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 书签抽屉的纯逻辑：
 *  - 行标题 / 副标题（MT 观感：网络行显示「协议@连接名 路径」）；
 *  - 长按拖动排序的落点（含越界夹取、无效输入原样返回）；
 *  - 抽屉高度松手后的吸附档位。
 */
class BookmarkPanelTest {

    private val local = VfsUri("local", "emulated", "/Download")
    private val localRoot = VfsUri("local", "emulated", "/")
    private val dav = VfsUris.withConnection(VfsUri("dav", "nas", "/Vedios"), 7)

    private val pathOf: (VfsUri) -> String = { uri ->
        if (uri.scheme == "local") "/storage/emulated/0" + uri.displayPath else uri.displayPath
    }

    // ------------------------------------------------------------------ 行标题

    @Test
    fun `本地行用末级目录名`() {
        assertEquals("Download", bookmarkRowTitle(local, connectionName = null))
    }

    @Test
    fun `本地根目录退回内部存储`() {
        assertEquals("内部存储", bookmarkRowTitle(localRoot, connectionName = null))
    }

    @Test
    fun `网络行优先用连接名`() {
        assertEquals("我的 NAS", bookmarkRowTitle(dav, connectionName = "我的 NAS"))
    }

    @Test
    fun `网络行没有连接名时用末级目录名`() {
        assertEquals("Vedios", bookmarkRowTitle(dav, connectionName = "   "))
    }

    // ------------------------------------------------------------------ 行副标题

    @Test
    fun `本地副标题是真实文件系统路径`() {
        assertEquals("/storage/emulated/0/Download", bookmarkRowSubtitle(local, null, pathOf))
    }

    @Test
    fun `网络副标题带协议与连接名`() {
        assertEquals("dav@我的 NAS /Vedios", bookmarkRowSubtitle(dav, "我的 NAS", pathOf))
    }

    @Test
    fun `网络副标题没有连接名时退回 authority`() {
        assertEquals("dav@nas /Vedios", bookmarkRowSubtitle(dav, null, pathOf))
    }

    // ------------------------------------------------------------------ 拖动排序

    private val ids = listOf(11L, 22L, 33L, 44L)

    @Test
    fun `向下拖动一行`() {
        assertEquals(listOf(22L, 11L, 33L, 44L), moveBookmarkIds(ids, 11L, 1))
    }

    @Test
    fun `向上拖动两行`() {
        assertEquals(listOf(11L, 44L, 22L, 33L), moveBookmarkIds(ids, 44L, -2))
    }

    @Test
    fun `拖出列表边界被夹到最后一位`() {
        assertEquals(listOf(22L, 33L, 44L, 11L), moveBookmarkIds(ids, 11L, 99))
    }

    @Test
    fun `拖出列表边界被夹到第一位`() {
        assertEquals(listOf(44L, 11L, 22L, 33L), moveBookmarkIds(ids, 44L, -99))
    }

    @Test
    fun `没移动时原样返回同一个列表`() {
        assertSame(ids, moveBookmarkIds(ids, 22L, 0))
    }

    @Test
    fun `未知 id 原样返回`() {
        assertSame(ids, moveBookmarkIds(ids, 99L, 3))
    }

    @Test
    fun `夹取到边界但位置没变时也原样返回`() {
        // 末项再往下拖：落点仍是自己 → 不该写库（调用方按引用比较判断）
        assertSame(ids, moveBookmarkIds(ids, 44L, 5))
    }

    // ------------------------------------------------------------------ 抽屉吸附

    @Test
    fun `半屏附近吸附回半屏`() {
        assertEquals(BookmarkDrawerAnchor.HALF, bookmarkDrawerAnchor(0.5f))
        assertEquals(BookmarkDrawerAnchor.HALF, bookmarkDrawerAnchor(0.62f))
    }

    @Test
    fun `拉到一半以上吸附到全屏`() {
        assertEquals(BookmarkDrawerAnchor.FULL, bookmarkDrawerAnchor(0.8f))
        assertEquals(BookmarkDrawerAnchor.FULL, bookmarkDrawerAnchor(0.94f))
    }

    @Test
    fun `拖到半屏以下足够多则收起`() {
        assertEquals(BookmarkDrawerAnchor.CLOSE, bookmarkDrawerAnchor(0.2f))
        assertEquals(BookmarkDrawerAnchor.CLOSE, bookmarkDrawerAnchor(0f))
    }

    @Test
    fun `标签页顺序与 MT 一致`() {
        assertEquals(listOf("最近", "书签"), BookmarkTab.entries.map { it.label })
    }

    @Test
    fun `最近列表过滤掉压缩包内部路径`() {
        val archive = VfsUri("archive", "zip", "/local%3A%2F%2Femulated!//a.zip!/x")
        assertEquals(listOf(local, dav), recentPathsForDrawer(listOf(local, archive, dav)))
    }
}
