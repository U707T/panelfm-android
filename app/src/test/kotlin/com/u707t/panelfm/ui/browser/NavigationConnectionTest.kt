package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导航连接号选择（[pickConnectionId]）：
 * 跨主机 / 跨协议 / URI 自带 `?c=` 时不能沿用当前标签页的连接号。
 */
class NavigationConnectionTest {

    private val tab = PaneTab(
        uri = VfsUri.parse("sftp://nas:22/home/u?c=3"),
        connectionId = 3,
        label = "NAS",
    )

    private val lookup: (VfsUri) -> Long? = { uri -> if (uri.authority == "other:22") 5L else null }

    @Test
    fun `本地与压缩包永远不挂连接号`() {
        assertNull(pickConnectionId(VfsUri.parse("local://emulated/Download"), tab, 9, lookup))
        assertNull(
            pickConnectionId(
                VfsUri.parse("archive://zip/%2Fstorage%2Femulated%2F0%2Fa.zip!/x"),
                tab,
                9,
                lookup,
            ),
        )
    }

    @Test
    fun `显式连接号优先`() {
        assertEquals(9L, pickConnectionId(VfsUri.parse("sftp://nas:22/home/u"), tab, 9, lookup))
    }

    @Test
    fun `URI 自带的连接号优先于当前标签页`() {
        // 同主机不同账号：目标 URI 的 c=7 不能被 tab 的 c=3 盖掉
        assertEquals(7L, pickConnectionId(VfsUri.parse("sftp://nas:22/home/u?c=7"), tab, null, lookup))
    }

    @Test
    fun `同主机同协议没写 c 时沿用当前连接`() {
        assertEquals(3L, pickConnectionId(VfsUri.parse("sftp://nas:22/etc"), tab, null, lookup))
    }

    @Test
    fun `同协议不同主机按目标反查`() {
        assertEquals(5L, pickConnectionId(VfsUri.parse("sftp://other:22/data"), tab, null, lookup))
    }

    @Test
    fun `反查不到时也不能退回当前连接号`() {
        assertNull(pickConnectionId(VfsUri.parse("sftp://unknown:22/x"), tab, null, lookup))
    }

    @Test
    fun `跨协议按目标反查`() {
        val s3lookup: (VfsUri) -> Long? = { 8L }
        assertEquals(8L, pickConnectionId(VfsUri.parse("s3://bucket/x"), tab, null, s3lookup))
    }
}
