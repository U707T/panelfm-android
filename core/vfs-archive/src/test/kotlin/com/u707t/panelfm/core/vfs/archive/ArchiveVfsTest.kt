package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveVfsTest {

    private fun env(): VfsEnv = VfsEnv(
        appDirs = AppDirs(Files.createTempDirectory("f").toString(), Files.createTempDirectory("c").toString()),
        dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    private fun makeZip(dir: File): File {
        val zip = File(dir, "test.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello zip".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dir/b.bin"))
            zos.write(ByteArray(2048) { (it % 251).toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dir/sub/c.txt"))
            zos.write("nested".toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    private fun makeTarGz(dir: File): File {
        val tar = File(dir, "test.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(tar.outputStream())).use { tos ->
            val content = "tar gz content".toByteArray()
            val entry = TarArchiveEntry("x.txt").apply { size = content.size.toLong() }
            tos.putArchiveEntry(entry)
            tos.write(content)
            tos.closeArchiveEntry()
        }
        return tar
    }

    @Test
    fun `zip 挂载 列目录 读取`() = runTest {
        val dir = Files.createTempDirectory("arc").toFile()
        val zip = makeZip(dir)
        val host = VfsUri.of("local", "emulated", "/${zip.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()

        val root = vfs.list(VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/"))
        assertTrue(root.any { it.name == "a.txt" })
        assertTrue(root.any { it.name == "dir" && it.isDirectory })

        val inner = VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/dir")
        val dirList = vfs.list(inner)
        assertEquals(2, dirList.size)
        assertTrue(dirList.any { it.name == "b.bin" && it.size == 2048L })

        val fileUri = VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/a.txt")
        val stat = vfs.stat(fileUri)
        assertEquals("a.txt", stat.name)
        assertEquals(9L, stat.size)

        val reader = vfs.openRead(fileUri)
        val buf = ByteArray(64)
        val n = reader.read(buf, 0, buf.size)
        reader.close()
        assertEquals("hello zip", String(buf, 0, n))
    }

    @Test
    fun `zip 支持随机读取`() = runTest {
        val dir = Files.createTempDirectory("arc2").toFile()
        val zip = makeZip(dir)
        val host = VfsUri.of("local", "emulated", "/${zip.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        val entry = VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/dir/b.bin")
        val reader = vfs.openRead(entry)
        val chunk = reader.readFullyAt(1024, 256)
        reader.close()
        assertEquals(256, chunk.size)
        assertTrue(chunk[0] == ((1024 % 251).toByte()))
    }

    @Test
    fun `tar gz 挂载与读取`() = runTest {
        val dir = Files.createTempDirectory("arc3").toFile()
        val tgz = makeTarGz(dir)
        val host = VfsUri.of("local", "emulated", "/${tgz.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.TAR_GZ, tgz, env())
        vfs.connect()
        val uri = VfsUri.of("archive", "targz", "/" + VfsUri.encodeHost(host.toString()) + "!/x.txt")
        assertEquals(14L, vfs.stat(uri).size)
        val reader = vfs.openRead(uri)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        reader.close()
        assertEquals("tar gz content", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `压缩目录为 zip 并回读验证`() = runTest {
        // 用内存 VFS 作为压缩源，验证 ArchiveCompressor 的写入路径
        val mem = MemoryVfs()
        mem.put("/src/1.txt", "one".toByteArray())
        mem.put("/src/sub/2.txt", "two".toByteArray())
        val outDir = Files.createTempDirectory("zipout").toFile()
        val target = VfsUri.of("mem", "one", "/out.zip")
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        ArchiveCompressor(locator).compress(listOf(VfsUri.of("mem", "one", "/src")), target)
        val bytes = mem.content("/out.zip")
        assertTrue("压缩结果应非空", bytes.isNotEmpty())

        // 把结果落到磁盘再用 ArchiveVfs 校验内容
        val file = File(outDir, "out.zip")
        file.writeBytes(bytes)
        val vfs = ArchiveVfs(target, ArchiveVfs.ArchiveKind.ZIP, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(target.toString()) + "!/"
        val root = vfs.list(VfsUri.of("archive", "zip", base))
        // 压缩的是 /src 目录本身 → 根下应有 src/，其下才是文件
        assertTrue("根下应有 src 目录，实际：${root.map { it.name }}", root.any { it.name == "src" && it.isDirectory })
        val inner = vfs.list(VfsUri.of("archive", "zip", base + "src"))
        assertTrue("src 下应有 1.txt，实际：${inner.map { it.name }}", inner.any { it.name == "1.txt" })
        assertTrue(inner.any { it.name == "sub" && it.isDirectory })
    }

    @Test
    fun `创建 tar gz 压缩包并回读`() = runTest {
        val mem = MemoryVfs()
        mem.put("/src/a.txt", "hello".toByteArray())
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        val dest = VfsUri.of("mem", "one", "/out.tar.gz")
        ArchiveCompressor(locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")),
            dest,
            ArchiveCompressor.Format.TAR_GZ,
        )
        val file = File.createTempFile("out", ".tar.gz").apply { writeBytes(mem.content("/out.tar.gz")) }
        val vfs = ArchiveVfs(dest, ArchiveVfs.ArchiveKind.TAR_GZ, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(dest.toString()) + "!/"
        assertTrue(vfs.list(VfsUri.of("archive", "targz", base)).any { it.name == "src" })
        val inner = vfs.list(VfsUri.of("archive", "targz", base + "src"))
        assertTrue(inner.any { it.name == "a.txt" && it.size == 5L })
    }

    @Test
    fun `ZIP 内部增删改名（整包重写）`() = runTest {
        val dir = Files.createTempDirectory("zipedit").toFile()
        val zip = makeZip(dir)
        val host = VfsUri.of("mem", "one", "/${zip.name}")
        val mem = MemoryVfs()
        mem.put("/new/added.txt", "added".toByteArray())
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? =
                if (uri.authority == "one") mem else null
        }
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        // mem 的 openWrite 落到内存，这里把结果写回磁盘验证
        ZipEditor(vfs, locator).rewrite(
            remove = setOf("a.txt"),
            rename = mapOf("dir/b.bin" to "dir/renamed.bin"),
            additions = listOf("added.txt" to VfsUri.of("mem", "one", "/new/added.txt")),
        )
        val rewritten = mem.content("/${zip.name}")
        assertTrue("重写结果非空", rewritten.isNotEmpty())
        val out = File(dir, "rewritten.zip").apply { writeBytes(rewritten) }
        val check = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, out, env())
        check.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        val root = check.list(VfsUri.of("archive", "zip", base))
        assertTrue("a.txt 应已删除：${root.map { it.name }}", root.none { it.name == "a.txt" })
        assertTrue("added.txt 应存在", root.any { it.name == "added.txt" })
        val dirList = check.list(VfsUri.of("archive", "zip", base + "dir"))
        assertTrue("重命名应生效：${dirList.map { it.name }}", dirList.any { it.name == "renamed.bin" })
    }

    @Test
    fun `ZIP 删除目录与改父目录（子树操作）`() = runTest {
        val dir = Files.createTempDirectory("zipmove").toFile()
        val zip = makeZip(dir)
        val host = VfsUri.of("mem", "one", "/${zip.name}")
        val mem = MemoryVfs()
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        // 1) 删除目录 dir —— 应连同 dir/ 下全部子项一起删除；
        // 2) a.txt → new/a.txt —— 改父目录（压缩包内的「移动」）
        ZipEditor(vfs, locator).rewrite(
            remove = setOf("dir"),
            rename = mapOf("a.txt" to "new/a.txt"),
        )
        val rewritten = mem.content("/${zip.name}")
        assertTrue("重写结果非空", rewritten.isNotEmpty())
        val out = File(dir, "moved.zip").apply { writeBytes(rewritten) }
        val check = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, out, env())
        check.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        val root = check.list(VfsUri.of("archive", "zip", base))
        assertTrue("dir 及其子项应全部删除：${root.map { it.name }}", root.none { it.name == "dir" })
        assertTrue("new 目录应存在：${root.map { it.name }}", root.any { it.name == "new" && it.isDirectory })
        val newList = check.list(VfsUri.of("archive", "zip", base + "new"))
        assertTrue("a.txt 应移动到 new/：${newList.map { it.name }}", newList.any { it.name == "a.txt" })
    }

    @Test
    fun `压缩包路径穿越条目被拒绝`() = runTest {
        val dir = Files.createTempDirectory("arc-traversal").toFile()
        val zip = File(dir, "unsafe.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            listOf("../escape.txt", "/absolute.txt", "safe/ok.txt").forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(name.toByteArray())
                zos.closeEntry()
            }
        }
        val host = VfsUri.of("local", "emulated", "/${zip.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        val root = vfs.list(VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/"))

        assertTrue("危险条目不能直接出现在根列表", root.none { it.name == ".." || it.name == "escape.txt" || it.name == "absolute.txt" })
        assertTrue("正常条目仍可浏览", root.any { it.name == "safe" && it.isDirectory })
    }

    @Test
    fun `隐藏与搜索过滤对目录生效（与 LocalVfs 同语义）`() = runTest {
        val dir = Files.createTempDirectory("arc-filter").toFile()
        val zip = File(dir, "filter.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            listOf("alpha/x.txt", "beta/y.txt", ".hidden/z.txt", "note.txt").forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }
        val host = VfsUri.of("local", "emulated", "/${zip.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        val root = VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/")

        val hiddenOff = vfs.list(root, ListOptions(showHidden = false)).map { it.name }
        assertTrue("隐藏目录不应出现：$hiddenOff", hiddenOff.none { it == ".hidden" })
        assertTrue("正常目录应保留：$hiddenOff", hiddenOff.containsAll(listOf("alpha", "beta", "note.txt")))

        val filtered = vfs.list(root, ListOptions(filter = "alph")).map { it.name }
        assertTrue("过滤后只应剩 alpha：$filtered", filtered == listOf("alpha"))

        assertTrue("默认（showHidden=true）应显示隐藏目录", vfs.list(root).any { it.name == ".hidden" })
    }

    @Test
    fun `空压缩包列目录稳定（0 条目不会触发重复重建）`() = runTest {
        val dir = Files.createTempDirectory("arc-empty").toFile()
        val zip = File(dir, "empty.zip")
        ZipOutputStream(zip.outputStream()).use { /* EOCD-only */ }
        val host = VfsUri.of("local", "emulated", "/${zip.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        val root = VfsUri.of("archive", "zip", "/" + VfsUri.encodeHost(host.toString()) + "!/")
        repeat(3) {
            assertTrue("空包列表应为空", vfs.list(root).isEmpty())
        }
        assertTrue(vfs.stat(root).isDirectory)
    }

    @Test
    fun `创建 tar bz2 压缩包并回读`() = runTest {
        val mem = MemoryVfs()
        mem.put("/src/a.txt", "hello bz2".toByteArray())
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        val dest = VfsUri.of("mem", "one", "/out.tar.bz2")
        ArchiveCompressor(locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")),
            dest,
            ArchiveCompressor.Format.TAR_BZ2,
        )
        assertEquals(ArchiveVfs.ArchiveKind.TAR_BZ2, ArchiveVfs.ArchiveKind.ofFileName("out.tar.bz2"))
        val file = File.createTempFile("out", ".tar.bz2").apply { writeBytes(mem.content("/out.tar.bz2")) }
        val vfs = ArchiveVfs(dest, ArchiveVfs.ArchiveKind.TAR_BZ2, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(dest.toString()) + "!/"
        assertTrue(vfs.list(VfsUri.of("archive", "tarbz2", base)).any { it.name == "src" })
        val inner = vfs.list(VfsUri.of("archive", "tarbz2", base + "src"))
        assertTrue(inner.any { it.name == "a.txt" && it.size == 9L })
    }

    @Test
    fun `压缩源会话缺失时明确失败而不是静默跳过`() = runTest {
        val mem = MemoryVfs()
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        val dest = VfsUri.of("mem", "one", "/out.zip")
        val err = runCatching {
            ArchiveCompressor(locator).compress(listOf(VfsUri.of("mem", "ghost", "/x.txt")), dest)
        }.exceptionOrNull()
        assertTrue("应抛 VfsException，实际 ${err?.javaClass?.name}", err is VfsException)
        assertTrue("不应写入目标", mem.content("/out.zip").isEmpty())
    }

    /** 极简内存 VFS：只实现压缩测试所需的能力 */
    // ---------------------------------------------------------------- 新增格式（tar.xz / 单文件流 / cpio / ar）

    @Test
    fun `后缀识别覆盖新增格式且复合后缀优先`() {
        val kind = { n: String -> ArchiveVfs.ArchiveKind.ofFileName(n) }
        assertEquals(ArchiveVfs.ArchiveKind.TAR_XZ, kind("a.tar.xz"))
        assertEquals(ArchiveVfs.ArchiveKind.TAR_XZ, kind("a.txz"))
        assertEquals(ArchiveVfs.ArchiveKind.TAR_GZ, kind("a.tar.gz"))
        assertEquals(ArchiveVfs.ArchiveKind.TAR_BZ2, kind("a.tar.bz2"))
        assertEquals(ArchiveVfs.ArchiveKind.GZIP, kind("a.txt.gz"))
        assertEquals(ArchiveVfs.ArchiveKind.XZ, kind("a.txt.xz"))
        assertEquals(ArchiveVfs.ArchiveKind.BZIP2, kind("a.txt.bz2"))
        assertEquals(ArchiveVfs.ArchiveKind.LZMA, kind("a.txt.lzma"))
        assertEquals(ArchiveVfs.ArchiveKind.UNIX_COMPRESS, kind("a.txt.Z"))
        assertEquals(ArchiveVfs.ArchiveKind.LZ4, kind("a.txt.lz4"))
        assertEquals(ArchiveVfs.ArchiveKind.CPIO, kind("boot.cpio"))
        assertEquals(ArchiveVfs.ArchiveKind.AR, kind("pkg.deb"))
        assertEquals(ArchiveVfs.ArchiveKind.ZIP, kind("book.epub"))
        assertEquals(ArchiveVfs.ArchiveKind.ZIP, kind("app.apks"))
        // 文档预览优先：OOXML 不进压缩包浏览
        assertNull(kind("a.docx"))
        assertNull(kind("a.txt"))
    }

    @Test
    fun `tar xz 挂载与读取`() = runTest {
        val dir = Files.createTempDirectory("arc-tarxz").toFile()
        val file = File(dir, "test.tar.xz")
        TarArchiveOutputStream(XZCompressorOutputStream(file.outputStream())).use { tos ->
            val content = "tar xz content".toByteArray()
            tos.putArchiveEntry(TarArchiveEntry("x.txt").apply { size = content.size.toLong() })
            tos.write(content)
            tos.closeArchiveEntry()
        }
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ofFileName(file.name)!!, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        assertEquals(14L, vfs.stat(VfsUri.of("archive", "tarxz", base + "x.txt")).size)
        assertEquals("tar xz content", readAll(vfs, VfsUri.of("archive", "tarxz", base + "x.txt")))
    }

    @Test
    fun `创建 tar xz 压缩包并回读`() = runTest {
        val mem = MemoryVfs()
        mem.put("/src/a.txt", "hello xz".toByteArray())
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        val dest = VfsUri.of("mem", "one", "/out.tar.xz")
        ArchiveCompressor(locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")),
            dest,
            ArchiveCompressor.Format.TAR_XZ,
        )
        val file = File.createTempFile("out", ".tar.xz").apply { writeBytes(mem.content("/out.tar.xz")) }
        val vfs = ArchiveVfs(dest, ArchiveVfs.ArchiveKind.TAR_XZ, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(dest.toString()) + "!/"
        assertTrue(vfs.list(VfsUri.of("archive", "tarxz", base)).any { it.name == "src" })
        val inner = vfs.list(VfsUri.of("archive", "tarxz", base + "src"))
        assertTrue(inner.any { it.name == "a.txt" && it.size == 8L })
    }

    @Test
    fun `单文件 gz 虚拟为一个条目且可读出`() = runTest {
        val dir = Files.createTempDirectory("arc-gz").toFile()
        val file = File(dir, "note.txt.gz")
        GzipCompressorOutputStream(file.outputStream()).use { it.write("hello single gz".toByteArray()) }
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.GZIP, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        val root = vfs.list(VfsUri.of("archive", "gzip", base))
        assertEquals("note.txt", root.single().name)
        assertTrue("单流条目不是目录", !root.single().isDirectory)
        assertTrue("大小未知（-1）而不是猜一个错数字", root.single().size < 0)
        val uri = VfsUri.of("archive", "gzip", base + "note.txt")
        assertNull("大小未知时 reader.size 应为 null", vfs.openRead(uri).size)
        assertEquals("hello single gz", readAll(vfs, uri))
    }

    @Test
    fun `单文件压缩流均可读取（xz bz2 lzma lz4）`() = runTest {
        data class Case(
            val fileName: String,
            val kind: ArchiveVfs.ArchiveKind,
            val wrap: (java.io.OutputStream) -> java.io.OutputStream,
        )
        val cases = listOf(
            Case("a.txt.xz", ArchiveVfs.ArchiveKind.XZ) { o -> XZCompressorOutputStream(o) },
            Case("a.txt.bz2", ArchiveVfs.ArchiveKind.BZIP2) { o -> BZip2CompressorOutputStream(o) },
            Case("a.txt.lzma", ArchiveVfs.ArchiveKind.LZMA) { o -> LZMACompressorOutputStream(o) },
            Case("a.txt.lz4", ArchiveVfs.ArchiveKind.LZ4) { o -> FramedLZ4CompressorOutputStream(o) },
        )
        cases.forEach { case ->
            val dir = Files.createTempDirectory("arc-single").toFile()
            val file = File(dir, case.fileName)
            case.wrap(file.outputStream()).use { it.write("payload ${case.fileName}".toByteArray()) }
            val host = VfsUri.of("local", "emulated", "/${file.name}")
            val vfs = ArchiveVfs(host, case.kind, file, env())
            vfs.connect()
            val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
            assertEquals("${case.fileName} 应解出原名", "a.txt", vfs.list(VfsUri.of("archive", case.kind.id, base)).single().name)
            assertEquals(
                "payload ${case.fileName}",
                readAll(vfs, VfsUri.of("archive", case.kind.id, base + "a.txt")),
            )
            vfs.close()
        }
    }

    /**
     * `.Z`（Unix compress）：commons-compress 只有解压侧，没有写侧 —— 用固定样本。
     * 样本按 compress 格式编码（block mode、9 位 LSB 打包），并已用 gzip 的 uncompress
     * 与 commons-compress 双向交叉验证（见提交说明）。
     */
    @Test
    fun `单文件 Z（compress）固定样本读取`() = runTest {
        val hex = "1f9d9068cab061f306c498376de0c82933670e08326fe880d002820e4389010716cc4810"
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        val dir = Files.createTempDirectory("arc-z").toFile()
        val file = File(dir, "hello.txt.Z").apply { writeBytes(bytes) }
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.UNIX_COMPRESS, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        assertEquals("hello.txt", vfs.list(VfsUri.of("archive", "z", base)).single().name)
        assertEquals("hello compress dot Z test hello hello", readAll(vfs, VfsUri.of("archive", "z", base + "hello.txt")))
    }

    @Test
    fun `cpio 挂载与读取`() = runTest {
        val dir = Files.createTempDirectory("arc-cpio").toFile()
        val file = File(dir, "init.cpio")
        CpioArchiveOutputStream(file.outputStream()).use { cos ->
            val content = "cpio content".toByteArray()
            val entry = CpioArchiveEntry(CpioConstants.FORMAT_NEW, "boot/ramdisk.txt").apply {
                size = content.size.toLong()
            }
            cos.putArchiveEntry(entry)
            cos.write(content)
            cos.closeArchiveEntry()
        }
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.CPIO, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        assertTrue(vfs.list(VfsUri.of("archive", "cpio", base)).any { it.name == "boot" && it.isDirectory })
        val inner = vfs.list(VfsUri.of("archive", "cpio", base + "boot"))
        assertTrue(inner.any { it.name == "ramdisk.txt" && it.size == 12L })
        assertEquals("cpio content", readAll(vfs, VfsUri.of("archive", "cpio", base + "boot/ramdisk.txt")))
    }

    @Test
    fun `ar 挂载与读取（deb 外壳）`() = runTest {
        val dir = Files.createTempDirectory("arc-ar").toFile()
        val file = File(dir, "pkg.deb")
        ArArchiveOutputStream(file.outputStream()).use { aos ->
            val content = "ar content".toByteArray()
            aos.putArchiveEntry(ArArchiveEntry("data.txt", content.size.toLong()))
            aos.write(content)
            aos.closeArchiveEntry()
        }
        val host = VfsUri.of("local", "emulated", "/${file.name}")
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.AR, file, env())
        vfs.connect()
        val base = "/" + VfsUri.encodeHost(host.toString()) + "!/"
        val root = vfs.list(VfsUri.of("archive", "ar", base))
        assertTrue(root.any { it.name == "data.txt" && it.size == 10L })
        assertEquals("ar content", readAll(vfs, VfsUri.of("archive", "ar", base + "data.txt")))
    }

    /** 顺序读出条目的全部内容（UTF-8） */
    private suspend fun readAll(vfs: ArchiveVfs, uri: VfsUri): String {
        val reader = vfs.openRead(uri)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(512)
        try {
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { reader.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private class MemoryVfs : VirtualFileSystem {
        override val id = "mem"
        override val scheme = "mem"
        override val label = "mem"
        override val capabilities = com.u707t.panelfm.core.vfs.VfsCapabilities(
            rename = true, rangeRead = true, rangeWrite = true, writable = true,
        )
        private val files = linkedMapOf<String, ByteArray>()
        private val _state = MutableStateFlow(com.u707t.panelfm.core.vfs.VfsState.Ready)
        override val state: StateFlow<com.u707t.panelfm.core.vfs.VfsState> = _state

        fun put(path: String, data: ByteArray) { files[path] = data }
        fun content(path: String) = files[path] ?: ByteArray(0)

        override suspend fun connect() = Unit

        override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> {
            val prefix = uri.path.trimEnd('/') + "/"
            val out = ArrayList<FileMetadata>()
            files.keys.filter { it.startsWith(prefix) }.forEach { key ->
                val rest = key.removePrefix(prefix)
                val name = rest.substringBefore('/')
                if (out.none { it.name == name }) {
                    out.add(
                        FileMetadata(
                            uri = uri.child(name),
                            name = name,
                            isDirectory = rest.contains('/'),
                            size = files[key]?.size?.toLong() ?: 0,
                        )
                    )
                }
            }
            return out
        }

        override suspend fun stat(uri: VfsUri): FileMetadata {
            files[uri.path]?.let { return FileMetadata(uri = uri, name = uri.name, isDirectory = false, size = it.size.toLong()) }
            return FileMetadata.dir(uri, uri.name)
        }

        override suspend fun mkdir(uri: VfsUri, parents: Boolean) = Unit
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = Unit
        override suspend fun rename(from: VfsUri, to: VfsUri) = true

        override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
            com.u707t.panelfm.core.vfs.ByteArrayReader(files[uri.path] ?: ByteArray(0))

        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
            val buffer = ByteArrayOutputStream()
            return object : VfsWriter {
                override val writtenBytes: Long get() = buffer.size().toLong()
                override suspend fun write(buffer2: ByteArray, offset2: Int, length2: Int) { buffer.write(buffer2, offset2, length2) }
                override suspend fun flush() = Unit
                override suspend fun commit() { files[uri.path] = buffer.toByteArray() }
                override suspend fun abort() = Unit
            }
        }
    }
}
