package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 解析出的条目**必须保留父 URI 上的连接号 `?c=`**。
 *
 * 为什么这条重要：`?c=<connectionId>` 是会话定位（`SessionLocator.find`）与会话隔离
 * （`VfsUri.sameMount`）的唯一依据。一旦解析层把 query 丢掉：
 *  - 网络视频/音频的播放预检（`locator.find`）找不到会话 → 「存储会话不可用」；
 *  - 同主机多账号会被判成同一挂载点。
 */
class DavQueryPreserveTest {

    private val multiStatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/movie.mp4</d:href>
            <d:propstat><d:prop>
              <d:displayname>movie.mp4</d:displayname>
              <d:getcontentlength>1024</d:getcontentlength>
              <d:getlastmodified>Mon, 05 Oct 2026 10:00:00 GMT</d:getlastmodified>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    // 说明：单测环境里 Xml.newPullParser() 是 Android 桩、返回 null，
    // 所以这里直接测真正出问题的那一环 —— `DavXml.entryUri()`（parseMultiStatus 内部用它建条目）。
    // 旧实现用 VfsUri.of(scheme, authority, path) 建 URI，**丢掉了 query**。

    @Test
    fun `stat 解析出的条目保留连接号`() {
        val requested = VfsUri.of("dav", "nas:5244", "/movie.mp4", "c=7")
        val entry = DavXml.entryUri(requested, "/movie.mp4")
        assertEquals(
            "解析结果必须保留 ?c=，否则播放预检找不到会话",
            7L,
            VfsUris.connectionId(entry),
        )
    }

    @Test
    fun `列目录解析出的条目同样保留连接号`() {
        val requested = VfsUri.of("dav", "nas:5244", "/", "c=7")
        val entry = DavXml.entryUri(requested, "/sub/a.txt")
        assertNotNull(VfsUris.connectionId(entry))
        assertEquals("nas:5244", entry.authority)
        assertEquals("/sub/a.txt", entry.path)
    }

    @Test
    fun `无连接号时不凭空造一个`() {
        val requested = VfsUri.of("dav", "nas:5244", "/movie.mp4")
        val entry = DavXml.entryUri(requested, "/movie.mp4")
        assertEquals(null, VfsUris.connectionId(entry))
    }

    @Test
    fun `其它 query 参数也一并保留`() {
        val requested = VfsUri.of("dav", "nas:5244", "/movie.mp4", "c=7&x=1")
        val entry = DavXml.entryUri(requested, "/movie.mp4")
        assertTrue(
            "query 必须整体保留，实际：${entry.query}",
            entry.query.orEmpty().contains("x=1"),
        )
    }
}
