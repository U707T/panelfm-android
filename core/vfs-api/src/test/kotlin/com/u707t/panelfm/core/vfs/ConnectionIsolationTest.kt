package com.u707t.panelfm.core.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同主机多账号的**会话隔离**回归（审计报告 R5）。
 *
 * 背景：`VfsUri.sameMount` 是「目录自包含检查」和「子树重映射」的判据。
 * 它只有在 URI 带 `?c=<connectionId>` 时才能区分「同主机不同账号」；
 * 一旦某个入口漏掉 `c=`（书签 / 最近路径 / 同步 / 返回上级都曾漏过），
 * 两个不同账号就会被判成同一挂载点 —— `isInside` 会误报「目标在源内部」直接拒绝操作。
 *
 * 这里锁死两件事：① `withConnection` 能稳定补上并保留 `c=`；② 补上之后 `sameMount` 才正确。
 */
class ConnectionIsolationTest {

    private fun uri(path: String, conn: Long?) = VfsUri.of(
        "sftp", "nas:22", path, conn?.let { "c=$it" },
    )

    @Test
    fun `同一连接的不同路径是同一挂载点`() {
        val a = uri("/home/alice", 1L)
        val b = uri("/home/alice/docs", 1L)
        assertTrue(a.sameMount(b))
    }

    @Test
    fun `同主机不同账号不是同一挂载点`() {
        val alice = uri("/home/alice", 1L)
        val bob = uri("/home/bob", 2L)
        assertFalse("不同连接号必须隔离", alice.sameMount(bob))
    }

    /**
     * 只有一侧带 `c=` 时，`sameMount` 判为 **false**（而不是退回只比主机）。
     *
     * 这是保守方向：宁可「少认一次同一挂载点」，也不要「把两个账号认成同一个」。
     * 但它仍然是个真实缺陷 —— 判成不同挂载点会让
     * `FileOperationPlanner.isInside`（目录自包含检查）与 `TransferTask.isSameOrDescendant`
     * （KEEP_BOTH / SKIP 的子树重映射）**漏判**，所以「所有入口都补 c=」依然是必须的
     * （见审计报告 R5：书签 / 最近路径 / 同步 / 返回上级都曾漏掉）。
     */
    @Test
    fun `只有一侧带连接号时保守判为不同挂载点（漏判而非误判）`() {
        val withId = uri("/home/alice", 1L)
        val withoutId = uri("/home/alice", null)
        assertFalse("一侧缺 c= 时必须保守判为不同挂载点", withId.sameMount(withoutId))
        assertFalse("反向也必须一致", withoutId.sameMount(withId))
    }

    @Test
    fun `两侧都缺连接号时只能退回只比主机`() {
        val a = uri("/home/alice", null)
        val b = uri("/home/bob", null)
        assertTrue("都没有 c= 时无法区分账号，只能判为同一挂载点", a.sameMount(b))
    }

    @Test
    fun `withConnection 补上连接号后判定恢复正确`() {
        val bob = uri("/home/bob", null)
        val stamped = VfsUris.withConnection(bob, 2L)
        assertEquals(2L, VfsUris.connectionId(stamped))
        assertFalse("补上 c=2 后必须与 c=1 隔离", uri("/home/alice", 1L).sameMount(stamped))
    }

    @Test
    fun `withConnection 幂等且不污染其他 query 参数`() {
        val base = VfsUri.of("dav", "nas:5244", "/dav", "x=1")
        val once = VfsUris.withConnection(base, 7L)
        val twice = VfsUris.withConnection(once, 7L)
        assertEquals(once, twice)
        assertEquals(7L, VfsUris.connectionId(twice))
        assertTrue("其它参数必须保留：${twice.query}", twice.query!!.contains("x=1"))
    }

    @Test
    fun `stripped 只去掉连接号，保留路径`() {
        val stamped = VfsUris.withConnection(VfsUri.of("s3", "photos", "/a/b"), 3L)
        val stripped = VfsUris.stripped(stamped)
        assertEquals("/a/b", stripped.path)
        assertEquals("photos", stripped.authority)
        assertNull(stripped.query)
    }
}
