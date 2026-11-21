package com.u707t.panelfm.core.vfs

import org.junit.Assert.*
import org.junit.Test

class VfsUriRoundTripTest {
    @Test fun `合法问号井号百分号文件名经存储往返保持原样`() {
        for (scheme in listOf("local", "sftp", "s3")) {
            for (path in listOf("/a?b.txt", "/a#b%20.txt", "/%3F/%25?中文+空 格")) {
                val uri = VfsUri.of(scheme, "store", path, if (scheme == "local") null else "c=42&x=1")
                assertEquals(uri, VfsUri.parse(uri.toString()))
                assertEquals(uri.toString(), VfsUri.parse(uri.toString()).toString())
            }
        }
    }

    @Test fun `旧明文百分号路径不被当成URL自动解码`() {
        assertEquals("/a%20b.txt", VfsUri.parse("local://emulated/a%20b.txt").path)
        assertEquals("/中文 空格", VfsUri.parse("local://emulated/中文 空格").path)
        assertEquals("sftp://nas:22/a?c=42", VfsUri.parse("sftp://nas:22/a?c=42").toString())
    }

    @Test fun `带连接号及保留字符的网络宿主在嵌套归档中仍可还原`() {
        val host = VfsUri.of("sftp", "nas:22", "/home/a?b%3F.zip", "c=42&x=1")
        val inner = VfsUri.archive(host, "dir/a?b.txt")
        val restored = VfsUri.parse(inner.toString())
        assertEquals(inner, restored)
        val encoded = restored.path.trimStart('/').substringBefore("!/")
        assertEquals(host, VfsUri.parse(VfsUri.decodeHost(encoded)))
        assertEquals("dir/a?b.txt", restored.path.substringAfter("!/"))
        val nested = VfsUri.archive(inner, "a#b?c%20.txt")
        assertEquals(nested, VfsUri.parse(nested.toString()))
    }

    @Test fun `旧未转义网络归档宿主的c参数不会变成外层query`() {
        val legacy = "archive://zip/sftp%3A%2F%2Fnas%3A22%2Fhome%2Fa.zip?c=42!/dir/x"
        val parsed = VfsUri.parse(legacy)
        assertNull(parsed.query)
        assertEquals("dir/x", parsed.path.substringAfter("!/"))
        val host = VfsUri.parse(VfsUri.decodeHost(parsed.path.trimStart('/').substringBefore("!/")))
        assertEquals(42L, VfsUris.connectionId(host))
        assertEquals(parsed, VfsUri.parse(parsed.toString()))
    }
}
