package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
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

    /** 极简内存 VFS：只实现压缩测试所需的能力 */
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
