package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * RAR 解压（junrar）回归测试。
 *
 * 样本与预期见 `src/test/resources/rar/README.md`（都来自 junrar 官方测试档，几十~几百字节）。
 * 覆盖：
 *  - RAR4 / RAR5 列目录 + 读取（RAR5 是目前最常见的格式，必须能解）；
 *  - 固实（solid）包乱序读条目（junrar 的 solid 重放逻辑）；
 *  - 口令三态：需要口令（NEEDED）/ 口令错（WRONG）/ 正确（OK），RAR4 与 RAR5 各一条；
 *  - 头加密：RAR5 挂载即抛 Auth；**RAR4 的「打开成功但零条目」必须被识别成需要口令**（不然是静默空包）；
 *  - 恶意路径条目（`..` 穿越）不得进索引；
 *  - 不是 RAR 的文件给出可读错误。
 */
class RarVfsTest {

    private fun env(): VfsEnv = VfsEnv(
        appDirs = AppDirs(Files.createTempDirectory("f").toString(), Files.createTempDirectory("c").toString()),
        dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    /** 把测试资源拷成临时文件（ArchiveVfs 需要可随机访问的本地文件） */
    private fun fixture(name: String): File {
        val dir = Files.createTempDirectory("rar").toFile()
        val out = File(dir, name)
        val stream: InputStream = javaClass.getResourceAsStream("/rar/$name")
            ?: error("缺少测试样本 /rar/$name")
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun rarVfs(file: File, password: String? = null): ArchiveVfs {
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        return ArchiveVfs(host, ArchiveVfs.ArchiveKind.RAR, file, env(), password = password)
    }

    private fun rootUri(file: File): VfsUri {
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        return VfsUri.of("archive", "rar", "/" + VfsUri.encodeHost(host.toString()) + "!/")
    }

    private fun innerUri(file: File, inner: String): VfsUri {
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        return VfsUri.of("archive", "rar", "/" + VfsUri.encodeHost(host.toString()) + "!/$inner")
    }

    private suspend fun readAll(vfs: ArchiveVfs, uri: VfsUri): ByteArray {
        val reader = vfs.openRead(uri)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            reader.close()
        }
    }

    // ------------------------------------------------------------------ 基本读取

    @Test
    fun `rar4 挂载 列目录 读取`() = runTest {
        val file = fixture("rar4.rar")
        val vfs = rarVfs(file)
        vfs.connect()

        val items = vfs.list(rootUri(file))
        assertEquals(2, items.size)
        assertTrue(items.any { it.name == "FILE1.TXT" && it.size == 7L })

        val content = readAll(vfs, innerUri(file, "FILE1.TXT"))
        assertEquals("file1\r\n", String(content))
        assertEquals(ArchiveVfs.PasswordCheck.OK, vfs.checkPassword())
    }

    @Test
    fun `rar5 挂载与读取（RAR5 解压）`() = runTest {
        val file = fixture("rar5.rar")
        val vfs = rarVfs(file)
        vfs.connect()

        assertEquals(2, vfs.list(rootUri(file)).size)
        assertEquals("file2\r\n", String(readAll(vfs, innerUri(file, "FILE2.TXT"))))
    }

    @Test
    fun `rar5 真压缩数据可以完整解出（两次读取一致）`() = runTest {
        val file = fixture("rar5-real-compressed.rar")
        val vfs = rarVfs(file)
        vfs.connect()

        val stat = vfs.stat(innerUri(file, "payload.bin"))
        assertEquals(2000L, stat.size)
        val first = readAll(vfs, innerUri(file, "payload.bin"))
        val second = readAll(vfs, innerUri(file, "payload.bin"))
        assertEquals(2000, first.size)
        assertTrue("两次解出的字节必须一致", first.contentEquals(second))
    }

    @Test
    fun `固实 rar4：乱序读条目也要解对（solid 重放）`() = runTest {
        val file = fixture("rar4-solid.rar")
        val vfs = rarVfs(file)
        vfs.connect()

        assertEquals("file4\n", String(readAll(vfs, innerUri(file, "file4.txt"))))
        assertEquals("file1\n", String(readAll(vfs, innerUri(file, "file1.txt"))))
        assertEquals("file3\n", String(readAll(vfs, innerUri(file, "file3.txt"))))
    }

    // ------------------------------------------------------------------ 口令

    @Test
    fun `rar4 数据加密：无口令 NEEDED 错口令 WRONG 对口令 OK`() = runTest {
        val file = fixture("rar4-data-encrypted.rar")

        val none = rarVfs(file)
        none.connect()
        assertEquals(ArchiveVfs.PasswordCheck.NEEDED, none.checkPassword())
        val err = runCatching { readAll(none, innerUri(file, "file1.txt")) }.exceptionOrNull()
        assertTrue("无口令读取应给出口令类错误，实际：$err", err is VfsException.Auth)

        val wrong = rarVfs(file, "wrong")
        wrong.connect()
        assertEquals(ArchiveVfs.PasswordCheck.WRONG, wrong.checkPassword())

        val right = rarVfs(file, "junrar")
        right.connect()
        assertEquals(ArchiveVfs.PasswordCheck.OK, right.checkPassword())
        assertEquals("file1\n", String(readAll(right, innerUri(file, "file1.txt"))))
    }

    @Test
    fun `rar5 数据加密：无口令 NEEDED 错口令 WRONG 对口令 OK`() = runTest {
        val file = fixture("rar5-data-encrypted.rar")

        val none = rarVfs(file)
        none.connect()
        assertEquals(ArchiveVfs.PasswordCheck.NEEDED, none.checkPassword())

        val wrong = rarVfs(file, "wrong")
        wrong.connect()
        assertEquals(ArchiveVfs.PasswordCheck.WRONG, wrong.checkPassword())

        val right = rarVfs(file, "junrar")
        right.connect()
        assertEquals(ArchiveVfs.PasswordCheck.OK, right.checkPassword())
        assertEquals("file1\n", String(readAll(right, innerUri(file, "file1.txt"))))
    }

    @Test
    fun `rar4 头加密：无口令不许静默空包（挂载抛 Auth），对口令可读`() = runTest {
        val file = fixture("rar4-header-encrypted.rar")

        // 无口令：junrar 会「打开成功但零条目」——必须被识别成需要口令
        val none = rarVfs(file)
        val err = runCatching { none.connect() }.exceptionOrNull()
        assertTrue("头加密 RAR4 无口令应抛 Auth，实际：$err", err is VfsException.Auth)

        val right = rarVfs(file, "junrar")
        right.connect()
        assertEquals(1, right.list(rootUri(file)).size)
        assertEquals("file1\n", String(readAll(right, innerUri(file, "file1.txt"))))
    }

    @Test
    fun `rar5 头加密：无口令挂载即 Auth，对口令可读`() = runTest {
        val file = fixture("rar5-header-encrypted.rar")

        val none = rarVfs(file)
        val err = runCatching { none.connect() }.exceptionOrNull()
        assertTrue("头加密 RAR5 无口令应抛 Auth，实际：$err", err is VfsException.Auth)

        val right = rarVfs(file, "junrar")
        right.connect()
        assertEquals("file1\n", String(readAll(right, innerUri(file, "file1.txt"))))
    }

    // ------------------------------------------------------------------ 恶意条目 / 非 RAR

    @Test
    fun `路径穿越条目被拒绝（rar）`() = runTest {
        for (name in listOf("parent-dir.rar", "mkdir-escape.rar")) {
            val file = fixture(name)
            val vfs = rarVfs(file)
            vfs.connect()
            assertEquals("$name 的恶意条目不得进索引", 0, vfs.list(rootUri(file)).size)
            val inner = runCatching { vfs.stat(innerUri(file, "../tmp/existing-file")) }.exceptionOrNull()
            assertTrue("$name 的恶意路径 stat 应 NotFound，实际：$inner", inner is VfsException.NotFound)
        }
    }

    @Test
    fun `不是 RAR 的文件给出可读错误`() = runTest {
        val dir = Files.createTempDirectory("rar-bad").toFile()
        val fake = File(dir, "fake.rar")
        fake.writeBytes(ByteArray(64) { it.toByte() })
        val vfs = rarVfs(fake)
        val err = runCatching { vfs.connect() }.exceptionOrNull()
        assertTrue("应给 VfsException，实际：$err", err is VfsException)
    }
}
