package com.u707t.panelfm.core.vfs.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

/** WebDAV 上传临时名：与目标同目录的 `.name.panelfm.part`（commit 时 MOVE 成正式名）。 */
class DavPartTest {

    @Test
    fun `临时文件与目标同目录`() {
        assertEquals("/a/.b.txt.panelfm.part", WebDavVfs.partPathOf("/a/b.txt"))
        assertEquals("/.a.txt.panelfm.part", WebDavVfs.partPathOf("/a.txt"))
        assertEquals("/x/y/.z.zip.panelfm.part", WebDavVfs.partPathOf("/x/y/z.zip"))
    }
}
