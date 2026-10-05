package com.u707t.panelfm.core.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsUriTest {

    @Test
    fun `解析带端口与多级路径`() {
        val uri = VfsUri.parse("dav://192.168.28.156:5244/dav/media/a.mp4")
        assertEquals("dav", uri.scheme)
        assertEquals("192.168.28.156:5244", uri.authority)
        assertEquals("/dav/media/a.mp4", uri.path)
        assertEquals("a.mp4", uri.name)
        assertEquals("/dav/media", uri.parent?.path)
    }

    @Test
    fun `根路径与父路径`() {
        val root = VfsUri.parse("local://emulated/")
        assertTrue(root.isRoot)
        assertNull(root.parent)
        val child = root.child("Download")
        assertEquals("/Download", child.path)
        assertEquals("Download", child.name)
    }

    @Test
    fun `连接参数在子路径上保留`() {
        val uri = VfsUri.of("dav", "host:80", "/x", "c=7")
        val child = uri.child("y")
        assertEquals("c=7", child.query)
        assertEquals(7L, VfsUris.connectionId(child))
    }

    @Test
    fun `压缩包内部路径编解码`() {
        val host = VfsUri.parse("local://emulated/0/a.zip")
        val inner = VfsUri.archive(host, "dir/file.txt")
        assertEquals("archive", inner.scheme)
        val encoded = inner.path.trimStart('/').substringBefore("!/")
        assertEquals(host.toString(), VfsUri.decodeHost(encoded))
        assertTrue(inner.path.endsWith("!/dir/file.txt"))
    }

    @Test
    fun `同挂载点判断`() {
        assertTrue(VfsUri.parse("s3://photos/a").sameMount(VfsUri.parse("s3://photos/b/c")))
        assertTrue(!VfsUri.parse("s3://photos/a").sameMount(VfsUri.parse("s3://other/a")))
    }

    @Test
    fun `不同连接号即使主机相同也不是同一挂载点`() {
        val first = VfsUri.of("sftp", "nas:22", "/home/alice", "c=1")
        val second = VfsUri.of("sftp", "nas:22", "/home/bob", "c=2")
        val same = VfsUri.of("sftp", "nas:22", "/home/alice/docs", "c=1")
        assertTrue(!first.sameMount(second))
        assertTrue(first.sameMount(same))
        assertTrue(!first.sameMount(VfsUri.of("sftp", "nas:22", "/home/alice")))
    }
}
