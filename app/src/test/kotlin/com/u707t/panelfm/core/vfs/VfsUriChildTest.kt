package com.u707t.panelfm.core.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `VfsUri.child()` 语义回归（防止 stat/list 路径拼接 bug 复发）。
 *
 * 背景：`LocalVfs.stat()` 曾把「文件自身 URI」当成「父目录 URI」再拼一次文件名，
 * 得到 `.../x.jpg/x.jpg` → 打开任何本地文件都报 `ENOTDIR (Not a directory)`。
 * 修法是拆出 `metaOf(uri, file)`（uri 为自身）与 `meta(parentUri, file)`（列表用）。
 * 这里锁死 child() 的语义，避免以后又把「自身 URI」再拼一层。
 */
class VfsUriChildTest {

    @Test
    fun `child 只追加一层文件名`() {
        val dir = VfsUri.of("local", "emulated", "/DCIM/HeyBox")
        assertEquals("/DCIM/HeyBox/a.jpg", dir.child("a.jpg").path)
        // 关键断言：再拼一次就会得到 a.jpg/a.jpg（历史 bug 的形态）
        assertNotEquals("/DCIM/HeyBox/a.jpg/a.jpg", dir.child("a.jpg").path)
    }

    @Test
    fun `自身 URI 的 parent 回到父目录`() {
        val file = VfsUri.of("local", "emulated", "/DCIM/HeyBox/a.jpg")
        assertEquals("/DCIM/HeyBox", file.parent?.path)
        assertEquals("a.jpg", file.name)
        // 列表场景：parent + child(name) == 自身
        assertEquals(file.path, file.parent?.child(file.name)?.path)
    }

    @Test
    fun `根目录没有 parent`() {
        val root = VfsUri.of("local", "emulated", "/")
        assertEquals(null, root.parent)
        assertEquals("/a", root.child("a").path)
    }

    @Test
    fun `尾部斜杠不影响 child 与 parent`() {
        val dir = VfsUri.of("local", "emulated", "/sdcard/dir/")
        assertEquals("/sdcard/dir/a.txt", dir.child("a.txt").path)
        val file = VfsUri.of("local", "emulated", "/sdcard/dir/a.txt")
        assertEquals("/sdcard/dir", file.parent?.path)
    }

    @Test
    fun `网络协议同样只拼一层`() {
        val davDir = VfsUri.of("dav", "192.168.1.9:5244", "/photos")
        assertEquals("/photos/2026", davDir.child("2026").path)
        val sftpFile = VfsUri.of("sftp", "nas:22", "/home/user/a.txt")
        assertEquals("/home/user", sftpFile.parent?.path)
        assertEquals(sftpFile.path, sftpFile.parent?.child(sftpFile.name)?.path)
    }
}
