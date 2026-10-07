package com.u707t.panelfm.core.vfs.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SMB 路径切分纯函数（第 6 批 🟡8 修复后的回归）。 */
class SmbPathTest {

    @Test
    fun `常规路径切出共享名与反斜杠相对路径`() {
        assertEquals("share" to "a\\b", splitSmbPath("/share/a/b", null))
        assertEquals("share" to "a", splitSmbPath("/share/a", null))
        assertEquals("share" to "", splitSmbPath("/share", null))
        assertEquals("share" to "", splitSmbPath("/share/", null))
    }

    @Test
    fun `根路径用默认共享兜底`() {
        assertEquals("public" to "", splitSmbPath("/", "public"))
        assertEquals("public" to "", splitSmbPath("", "public"))
    }

    @Test
    fun `根路径且无默认共享返回 null（调用方给引导文案）`() {
        assertNull(splitSmbPath("/", null))
        assertNull(splitSmbPath("", null))
    }

    @Test
    fun `多余斜杠与空段被裁剪`() {
        assertEquals("share" to "dir\\file.txt", splitSmbPath("//share//dir/file.txt", null))
    }
}
