package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「后台」= 网络挂载（MT 语义）：
 *  - 只收网络协议路径：本地 / 压缩包不出现；
 *  - 每个挂载一行（取最近访问的那条）；
 *  - 排除当前窗格路径与失效路径；limit 生效。
 */
class DrawerRecentsTest {

    private fun dav(host: String, path: String, conn: Long) =
        VfsUris.withConnection(VfsUri("dav", host, path), conn)

    private val local = VfsUri("local", "emulated", "/Download")
    private val archive = VfsUri("archive", "zip", "/local%3A%2F%2Femulated!//a.zip!/x")

    @Test
    fun `本地与压缩包路径不出现`() {
        val out = drawerNetworkMounts(
            uris = listOf(local, archive, dav("nas", "/", 1)),
            exclude = emptySet(),
            exists = { true },
        )
        assertEquals(listOf(dav("nas", "/", 1)), out)
    }

    @Test
    fun `每个挂载只留最近访问的一行`() {
        val first = dav("nas", "/Movies", 7)
        val second = dav("nas", "/", 7)
        val out = drawerNetworkMounts(
            uris = listOf(first, second),
            exclude = emptySet(),
            exists = { true },
        )
        assertEquals(listOf(first), out)
    }

    @Test
    fun `排除当前窗格正在浏览的路径`() {
        val a = dav("nas", "/Movies", 7)
        val b = dav("nas", "/", 8)
        val out = drawerNetworkMounts(
            uris = listOf(a, b),
            exclude = setOf(a.toString()),
            exists = { true },
        )
        assertEquals(listOf(b), out)
    }

    @Test
    fun `失效路径被过滤`() {
        val out = drawerNetworkMounts(
            uris = listOf(dav("dead", "/", 1), dav("live", "/", 2)),
            exclude = emptySet(),
            exists = { it.authority == "live" },
        )
        assertEquals(listOf(dav("live", "/", 2)), out)
    }

    @Test
    fun `limit 生效且保持最近优先`() {
        val many = (1..8).map { dav("nas$it", "/", it.toLong()) }
        val out = drawerNetworkMounts(uris = many, exclude = emptySet(), exists = { true }, limit = 3)
        assertEquals(3, out.size)
        assertEquals(listOf("nas1", "nas2", "nas3"), out.map { it.authority })
    }
}
