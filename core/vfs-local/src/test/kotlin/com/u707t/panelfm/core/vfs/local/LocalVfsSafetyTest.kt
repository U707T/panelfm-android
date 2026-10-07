package com.u707t.panelfm.core.vfs.local

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.partNameOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * vfs-local 事故路径回归（第 4 批审计 🔵5）：
 *
 * 本地删除 / 写入是**用户数据第一现场**，此前零自动化覆盖。四条历史事故路径：
 *  1. 符号链接删除不跟随（`deleteRecursively`）；
 *  2. commit 原子替换已有文件（`ATOMIC_MOVE`）；
 *  3. 续传打开时截断偏移之后的残留尾巴（`LocalWriter` init）；
 *  4. 跨卷 rename 退化（`copyAndDelete`，从 `rename` 中抽出以便直接命中）。
 *
 * 说明：JVM 单测只走「不碰 `android.system.Os` / `StatFs`」的路径（delete / rename /
 * openWrite / commit）；`stat` / `list` 的权限字段覆盖仍需真机或后续注入 `LocalOs` 脸面。
 */
class LocalVfsSafetyTest {

    private fun env(): VfsEnv = VfsEnv(
        appDirs = AppDirs(Files.createTempDirectory("f").toString(), Files.createTempDirectory("c").toString()),
        dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    private fun uri(path: String): VfsUri = VfsUri.of("local", LocalVolumes.AUTHORITY_ROOT, path)

    private fun vfs(): LocalVfs = LocalVfs(env())

    private fun tempDir(): File = Files.createTempDirectory("lf").toFile()

    @Test
    fun `删除符号链接只删链接本身，不进入链接目标`() = runBlocking {
        val base = tempDir()
        val target = File(base, "target").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep")
        val link = File(base, "link").toPath()
        Files.createSymbolicLink(link, target.toPath())

        vfs().delete(listOf(uri(link.toAbsolutePath().toString())))

        assertTrue("链接目标里的文件必须还在（旧实现会递归删光）", File(target, "keep.txt").exists())
        assertTrue("链接本身必须被删除", !Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `删除悬空符号链接不抛异常且只删链接`() = runBlocking {
        val base = tempDir()
        val dangling = File(base, "dangling").toPath()
        Files.createSymbolicLink(dangling, File(base, "missing-target").toPath())

        vfs().delete(listOf(uri(dangling.toAbsolutePath().toString())))

        assertTrue("悬空链接也应被删除", !Files.exists(dangling, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `commit 原子替换已有文件且不残留 part`() = runBlocking {
        val base = tempDir()
        val target = File(base, "a.bin").apply { writeBytes(ByteArray(3) { 1 }) }
        val v = vfs()

        val writer = v.openWrite(uri(target.absolutePath), size = 5, offset = 0)
        writer.write("hello".toByteArray(), 0, 5)
        writer.commit()

        assertEquals(5L, target.length())
        assertArrayEquals("旧内容必须被整体替换", "hello".toByteArray(), target.readBytes())
        assertTrue("part 文件必须已被移走", !File(base, partNameOf("a.bin")).exists())
    }

    @Test
    fun `续传打开时截断偏移之后的残留尾巴`() = runBlocking {
        val base = tempDir()
        val target = File(base, "b.bin")
        // 模拟上次中断：.part 里多留了 10 字节
        File(base, partNameOf("b.bin")).writeBytes(ByteArray(10) { 9 })
        val v = vfs()

        val writer = v.openWrite(uri(target.absolutePath), size = 12, offset = 4)
        writer.write(byteArrayOf(7, 7, 7), 0, 3)
        writer.commit()

        assertArrayEquals(
            "前 4 字节保留、偏移后的残留尾巴必须被截掉",
            byteArrayOf(9, 9, 9, 9, 7, 7, 7),
            target.readBytes(),
        )
    }

    @Test
    fun `rename 同卷成功且拒绝覆盖已有目标`() = runBlocking {
        val base = tempDir()
        val src = File(base, "x.txt").apply { writeText("x") }
        val dst = File(base, "y.txt")
        val v = vfs()

        assertTrue(v.rename(uri(src.absolutePath), uri(dst.absolutePath)))
        assertTrue("源必须已被移走", !src.exists())
        assertEquals("x", dst.readText())

        val src2 = File(base, "z.txt").apply { writeText("z") }
        var conflicted = false
        try {
            v.rename(uri(src2.absolutePath), uri(dst.absolutePath))
        } catch (_: VfsException.Conflict) {
            conflicted = true
        }
        assertTrue("目标已存在时必须抛 Conflict（由引擎先删再改名）", conflicted)
    }

    @Test
    fun `跨卷退化 copyAndDelete：复制目录后删除源`() = runBlocking {
        val base = tempDir()
        val src = File(base, "tree").apply { mkdirs() }
        File(src, "a.txt").writeText("a")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("b")
        val dst = File(base, "tree-copy")

        assertTrue(vfs().copyAndDelete(src, dst))

        assertTrue("目标目录必须递归复制", File(dst, "sub/b.txt").readText() == "b")
        assertTrue("源目录必须已删除", !src.exists())
    }
}
