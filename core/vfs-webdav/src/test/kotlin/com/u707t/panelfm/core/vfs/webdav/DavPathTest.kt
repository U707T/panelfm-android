package com.u707t.panelfm.core.vfs.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

/** WebDAV 路径规范化：目录 href 的尾斜杠统一去掉（AList 等服务器会给目录加 "/"）。 */
class DavPathTest {

    @Test
    fun stripsTrailingSlashForDirs() {
        assertEquals("/sub", DavXml.normalizeDirPath("/sub/"))
        assertEquals("/a/b", DavXml.normalizeDirPath("/a/b/"))
    }

    @Test
    fun keepsRootAndPlainPaths() {
        assertEquals("/", DavXml.normalizeDirPath("/"))
        assertEquals("/sub", DavXml.normalizeDirPath("/sub"))
        assertEquals("/a/b.txt", DavXml.normalizeDirPath("/a/b.txt"))
    }

    @Test
    fun listSkipsSelfButStatKeepsIt() {
        // 列目录（skipSelf=true）：自身节点不出现（尾斜杠形式也算自身）
        assertEquals(false, DavXml.shouldInclude("/sub/", "/sub", skipSelf = true))
        assertEquals(false, DavXml.shouldInclude("/sub", "/sub/", skipSelf = true))
        assertEquals(false, DavXml.shouldInclude("/", "/", skipSelf = true))
        assertEquals(true, DavXml.shouldInclude("/other", "/sub", skipSelf = true))

        // stat（skipSelf=false）：自身节点用于取类型/大小
        assertEquals(true, DavXml.shouldInclude("/a.txt", "/a.txt", skipSelf = false))
    }
}
