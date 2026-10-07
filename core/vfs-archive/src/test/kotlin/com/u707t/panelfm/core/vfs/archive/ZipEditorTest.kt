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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZipEditor 整包重写的两个回归（第 5 批修复配套）：
 *  - 🔵6：重写保留 STORED 压缩方法、外部属性（权限位）与注释；
 *  - 🟡5：`onProgress` 可取消 —— 取消原样上抛、原包不动（不 commit 半成品）。
 */
class ZipEditorTest {

    private fun env(): VfsEnv = VfsEnv(
        appDirs = AppDirs(Files.createTempDirectory("f").toString(), Files.createTempDirectory("c").toString()),
        dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    private fun memoryLocator(mem: MemoryVfs): VfsLocator = object : VfsLocator {
        override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
    }

    @Test
    fun `重写保留 STORED 方法、外部属性与注释`() = runTest {
        val dir = Files.createTempDirectory("zipedit-meta").toFile()
        val zip = File(dir, "meta.zip")
        val data = "#!/bin/sh\necho hi\n".toByteArray()
        val crcValue = CRC32().apply { update(data) }.value
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip).use { out ->
            val e = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("run.sh").apply {
                method = ZipMethod.STORED.code
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = crcValue
                externalAttributes = 0b111101101 shl 16 // 0755 权限位
                comment = "keep-me"
                time = 1234567890000L
            }
            out.putArchiveEntry(e)
            out.write(data)
            out.closeArchiveEntry()
        }
        val host = VfsUri.of("mem", "one", "/meta.zip")
        val mem = MemoryVfs()
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        ZipEditor(vfs, memoryLocator(mem)).rewrite(rename = mapOf("run.sh" to "run2.sh"))

        val rewritten = File(dir, "rewritten.zip").apply { writeBytes(mem.content("/meta.zip")) }
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(rewritten).get().use { zf ->
            val e = zf.getEntry("run2.sh")!!
            assertEquals("STORED 应原样保留", ZipMethod.STORED.code, e.method)
            assertEquals("外部属性（权限位）应保留", 0b111101101 shl 16, e.externalAttributes)
            assertEquals("注释应保留", "keep-me", e.comment)
            assertEquals("内容应逐字节一致", "#!/bin/sh\necho hi\n", zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `重写中途取消：原样上抛、不 commit 半成品`() = runBlocking {
        val dir = Files.createTempDirectory("zipedit-cancel").toFile()
        val zip = File(dir, "cancel.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("big.bin"))
            zos.write(ByteArray(300 * 1024) { (it % 251).toByte() })
            zos.closeEntry()
        }
        val host = VfsUri.of("mem", "one", "/cancel.zip")
        val mem = MemoryVfs()
        val vfs = ArchiveVfs(host, ArchiveVfs.ArchiveKind.ZIP, zip, env())
        vfs.connect()
        var calls = 0
        val err = runCatching {
            ZipEditor(vfs, memoryLocator(mem)).rewrite(
                rename = mapOf("a.txt" to "a2.txt"),
                onProgress = { _, _ ->
                    calls++
                    throw CancellationException("用户取消")
                },
            )
        }.exceptionOrNull()
        assertTrue("取消必须原样上抛，实际 ${err?.javaClass?.name}", err is CancellationException)
        assertTrue("取消前应收到至少一次进度回调（逐块上报）", calls >= 1)
        assertTrue("取消不得写回压缩包（没有 commit）", mem.files["/cancel.zip"] == null)
    }

    /** 极简内存 VFS：只实现压缩测试所需的能力 */
    private class MemoryVfs : VirtualFileSystem {
        override val id = "mem"
        override val scheme = "mem"
        override val label = "mem"
        override val capabilities = com.u707t.panelfm.core.vfs.VfsCapabilities(
            rename = true, rangeRead = true, rangeWrite = true, writable = true,
        )
        val files = linkedMapOf<String, ByteArray>()
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
