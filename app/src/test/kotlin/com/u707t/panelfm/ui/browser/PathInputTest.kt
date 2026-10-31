package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部路径栏的纯逻辑：
 *  - 「跳转到路径」输入解析（系统绝对路径 / 相对 / ~ / 协议 URI / 压缩包内路径）；
 *  - 面包屑拆分（含压缩包宿主层级）；
 *  - 完整路径文本（复制 / 输入框初始值）。
 */
class PathInputTest {

    private val volumes = listOf(
        LocalVolume("emulated", "内部存储", "/storage/emulated/0"),
        LocalVolume("app", "应用私有目录", "/data/user/0/com.u707t.panelfm/files"),
        LocalVolume("vol:storage_ABCD-1234", "外置存储 ABCD-1234", "/storage/ABCD-1234"),
        LocalVolume("root", "根目录", "/"),
    )

    private fun ok(raw: String, current: VfsUri): PathInput.Result.Ok =
        PathInput.resolve(raw, current, volumes) as? PathInput.Result.Ok
            ?: error("应当解析成功：$raw")

    private fun bad(raw: String, current: VfsUri): PathInput.Result.Bad =
        PathInput.resolve(raw, current, volumes) as? PathInput.Result.Bad
            ?: error("应当解析失败：$raw")

    // ------------------------------------------------------------------ 相对 / 绝对

    @Test
    fun `相对路径按当前目录解析`() {
        val cur = VfsUri.parse("local://emulated/Download")
        assertEquals(VfsUri.parse("local://emulated/Download/Sub"), ok("Sub", cur).uri)
        assertEquals(VfsUri.parse("local://emulated/Music"), ok("../Music", cur).uri)
        assertEquals(VfsUri.parse("local://emulated/Download/b"), ok("a/../b", cur).uri)
    }

    @Test
    fun `波浪号回到当前存储根`() {
        val cur = VfsUri.parse("local://emulated/Download/Sub")
        assertEquals(VfsUri.parse("local://emulated/"), ok("~", cur).uri)
        assertEquals(VfsUri.parse("local://emulated/Music"), ok("~/Music", cur).uri)
    }

    @Test
    fun `系统绝对路径映射到内部存储`() {
        val cur = VfsUri.parse("local://emulated/")
        val r = ok("/storage/emulated/0/DCIM", cur)
        assertEquals(VfsUri.parse("local://emulated/DCIM"), r.uri)
        assertEquals("内部存储", r.label)
    }

    @Test
    fun `sdcard 与 mnt 别名归一`() {
        val cur = VfsUri.parse("local://emulated/")
        assertEquals(VfsUri.parse("local://emulated/DCIM"), ok("/sdcard/DCIM", cur).uri)
        assertEquals(VfsUri.parse("local://emulated/DCIM"), ok("/mnt/sdcard/DCIM", cur).uri)
        assertEquals(VfsUri.parse("local://emulated/DCIM"), ok("/storage/self/primary/DCIM", cur).uri)
    }

    @Test
    fun `外置卡与应用私有目录按最长前缀匹配`() {
        val cur = VfsUri.parse("local://emulated/")
        val sd = ok("/storage/ABCD-1234/Movies", cur)
        assertEquals("vol:storage_ABCD-1234", sd.uri.authority)
        assertEquals("/Movies", sd.uri.path)
        assertEquals("外置存储 ABCD-1234", sd.label)

        val app = ok("/data/user/0/com.u707t.panelfm/files/config", cur)
        assertEquals("app", app.uri.authority)
        assertEquals("/config", app.uri.path)
        assertEquals("应用私有目录", app.label)
    }

    @Test
    fun `不在任何卷里的绝对路径落到根目录卷`() {
        val r = ok("/system/build.prop", VfsUri.parse("local://emulated/"))
        assertEquals("local://root/system/build.prop", r.uri.toString())
        assertEquals("根目录", r.label)
    }

    @Test
    fun `本地根斜杠是当前卷根而不是文件系统根`() {
        val r = ok("/", VfsUri.parse("local://emulated/Download"))
        assertEquals("local://emulated/", r.uri.toString())
        val sd = ok("/", VfsUri.parse("local://vol:storage_ABCD-1234/Movies"))
        assertEquals("local://vol:storage_ABCD-1234/", sd.uri.toString())
    }

    // ------------------------------------------------------------------ 网络存储

    @Test
    fun `网络窗格里的绝对路径仍留在同一挂载点`() {
        val cur = VfsUri.parse("sftp://nas:22/home/u?c=7")
        val r = ok("/etc/nginx", cur)
        assertEquals("sftp://nas:22/etc/nginx?c=7", r.uri.toString())
        assertNull(r.label)
    }

    @Test
    fun `网络窗格里的相对路径保留连接参数`() {
        val cur = VfsUri.parse("sftp://nas:22/home/u?c=7")
        assertEquals("sftp://nas:22/home/u/sub?c=7", ok("sub", cur).uri.toString())
        assertEquals("sftp://nas:22/home?c=7", ok("..", cur).uri.toString())
    }

    @Test
    fun `协议 URI 原样接受`() {
        val cur = VfsUri.parse("local://emulated/")
        assertEquals("s3://photos/2026/a.jpg", ok("s3://photos/2026/a.jpg", cur).uri.toString())
        val dav = ok("dav://192.168.1.2:5244/dav/x", cur)
        assertEquals("dav", dav.uri.scheme)
        assertEquals("192.168.1.2:5244", dav.uri.authority)
    }

    @Test
    fun `local 协议支持空 authority 与旧 0 段写法`() {
        val cur = VfsUri.parse("local://emulated/")
        assertEquals("local://emulated/DCIM", ok("local:///sdcard/DCIM", cur).uri.toString())
        assertEquals("local://emulated/DCIM", ok("local://emulated/0/DCIM", cur).uri.toString())
        val root = ok("local://root/system", cur)
        assertEquals("根目录", root.label)
    }

    @Test
    fun `file 协议映射到本地卷`() {
        val cur = VfsUri.parse("local://emulated/")
        assertEquals("local://emulated/Download", ok("file:///storage/emulated/0/Download", cur).uri.toString())
    }

    @Test
    fun `不支持的协议给出明确提示`() {
        val cur = VfsUri.parse("local://emulated/")
        assertTrue(bad("content://media/external/images/1", cur).message.contains("content"))
        assertTrue(bad("https://example.com/a", cur).message.contains("https"))
    }

    // ------------------------------------------------------------------ 压缩包

    @Test
    fun `宿主路径加感叹号解析成压缩包内部`() {
        val cur = VfsUri.parse("local://emulated/Download")
        val r = ok("a.zip!/dir/sub", cur)
        assertEquals("archive", r.uri.scheme)
        assertEquals("zip", r.uri.authority)
        assertEquals("local://emulated/Download/a.zip", r.archiveHost.toString())
        assertEquals("dir/sub", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(r.uri.path))
    }

    @Test
    fun `绝对系统路径也能作为压缩包宿主`() {
        val r = ok("/storage/emulated/0/Download/a.7z!/x", VfsUri.parse("local://emulated/"))
        assertEquals("7z", r.uri.authority)
        assertEquals("local://emulated/Download/a.7z", r.archiveHost.toString())
    }

    @Test
    fun `压缩包里相对路径在包内解析且不越界`() {
        val host = VfsUri.parse("local://emulated/Download/a.zip")
        val cur = PathInput.archiveUri(host, "zip", "dir/sub")
        val r = ok("../b", cur)
        assertEquals("dir/b", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(r.uri.path))
        val up = ok("../../../../x", cur)
        assertEquals("x", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(up.uri.path))
        assertEquals(host.toString(), up.archiveHost.toString())
        // 压缩包里的 ~ 也是「包内根」，不是宿主存储根
        assertEquals("", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(ok("~", cur).uri.path))
        assertEquals("a", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(ok("~/a", cur).uri.path))
    }

    @Test
    fun `不认识的压缩包后缀直接报错`() {
        val msg = bad("notes.txt!/x", VfsUri.parse("local://emulated/")).message
        assertTrue(msg.contains("压缩包"))
        assertTrue(bad("!/x", VfsUri.parse("local://emulated/")).message.contains("压缩包"))
    }

    @Test
    fun `空输入`() {
        assertTrue(bad("   ", VfsUri.parse("local://emulated/")).message.contains("请输入"))
    }

    @Test
    fun `带引号的粘贴路径会被去掉引号`() {
        val r = ok("\" /sdcard/DCIM \"", VfsUri.parse("local://emulated/"))
        assertEquals("local://emulated/DCIM", r.uri.toString())
    }

    // ------------------------------------------------------------------ 面包屑

    @Test
    fun `本地面包屑从存储名开始`() {
        val crumbs = pathCrumbs(VfsUri.parse("local://emulated/Download/Sub"), "内部存储", volumes)
        assertEquals(listOf("内部存储", "Download", "Sub"), crumbs.map { it.label })
        assertEquals("local://emulated/", crumbs[0].uri.toString())
        assertEquals("local://emulated/Download/Sub", crumbs[2].uri.toString())
        assertEquals(listOf(false, false, true), crumbs.map { it.current })
    }

    @Test
    fun `网络面包屑用标签页名作为根`() {
        val crumbs = pathCrumbs(VfsUri.parse("sftp://nas:22/home/u"), "NAS-SFTP", volumes)
        assertEquals(listOf("NAS-SFTP", "home", "u"), crumbs.map { it.label })
    }

    @Test
    fun `根目录只有一级且是当前`() {
        val crumbs = pathCrumbs(VfsUri.parse("s3://photos/"), "对象存储", volumes)
        assertEquals(1, crumbs.size)
        assertTrue(crumbs[0].current)
    }

    @Test
    fun `压缩包面包屑包含宿主层级与包内层级`() {
        val host = VfsUri.parse("local://emulated/Download/a.zip")
        val uri = PathInput.archiveUri(host, "zip", "dir/sub")
        val crumbs = pathCrumbs(uri, "a.zip · ZIP", volumes)

        assertEquals(listOf("内部存储", "Download", "a.zip", "dir", "sub"), crumbs.map { it.label })
        // 宿主层级点击即退出压缩包
        assertEquals("local://emulated/Download", crumbs[1].uri.toString())
        assertEquals("内部存储", crumbs[1].mountLabel)
        // a.zip 指向包内根，不是宿主文件
        assertEquals("archive", crumbs[2].uri.scheme)
        assertNull(crumbs[2].mountLabel)
        assertEquals("", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(crumbs[2].uri.path))
        // 包内层级
        assertEquals("dir/sub", com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(crumbs[4].uri.path))
        assertTrue(crumbs[4].current)
        assertEquals(1, crumbs.count { it.current })
    }

    @Test
    fun `远程压缩包的面包屑根用主机名而不是压缩包标签`() {
        val host = VfsUri.parse("sftp://nas:22/home/u/a.zip?c=3")
        val crumbs = pathCrumbs(PathInput.archiveUri(host, "zip", ""), "a.zip · ZIP", volumes)
        assertEquals(listOf("nas", "home", "u", "a.zip"), crumbs.map { it.label })
        assertEquals("nas", crumbs[0].mountLabel)
        assertTrue(crumbs.last().current)
    }

    // ------------------------------------------------------------------ 完整路径文本

    @Test
    fun `完整路径文本是真实文件系统路径`() {
        assertEquals(
            "/storage/emulated/0/Download/a.txt",
            pathTextOf(VfsUri.parse("local://emulated/Download/a.txt"), volumes),
        )
        assertEquals(
            "/data/user/0/com.u707t.panelfm/files/config",
            pathTextOf(VfsUri.parse("local://app/config"), volumes),
        )
        assertEquals(
            "/system/build.prop",
            pathTextOf(VfsUri.parse("local://root/system/build.prop"), volumes),
        )
    }

    @Test
    fun `网络完整路径去掉内部连接参数`() {
        assertEquals(
            "sftp://nas:22/home/u",
            pathTextOf(VfsUri.parse("sftp://nas:22/home/u?c=7"), volumes),
        )
    }

    @Test
    fun `压缩包完整路径带感叹号`() {
        val host = VfsUri.parse("local://emulated/Download/a.zip")
        assertEquals(
            "/storage/emulated/0/Download/a.zip!/dir",
            pathTextOf(PathInput.archiveUri(host, "zip", "dir"), volumes),
        )
    }

    @Test
    fun `规范化的边界`() {
        assertEquals("/a/c", PathInput.canonicalPath("/a//b/../c"))
        assertEquals("/", PathInput.canonicalPath("/../../.."))
        assertEquals("../../x", PathInput.canonicalPath("../../x"))
        assertEquals("/storage/emulated/0/DCIM", PathInput.canonicalFs("/sdcard/DCIM/../DCIM"))
    }
}
