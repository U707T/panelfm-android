package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.vfs.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ArchiveTraversalSafetyTest {
    private class Store(private val shape: String) : VirtualFileSystem {
        override val id = "store"
        override val scheme = "mem"
        override val label = "test"
        override val capabilities = VfsCapabilities(writable = true)
        override val state = MutableStateFlow<VfsState>(VfsState.Ready)
        var output = "original output".toByteArray()
        var reads = 0
        var committed = false
        override suspend fun connect() = Unit
        override suspend fun stat(uri: VfsUri): FileMetadata = FileMetadata(
            uri = uri, name = uri.name, isDirectory = true, isSymlink = shape == "root-link")
        override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> = when (shape) {
            "file-link" -> listOf(FileMetadata(uri.child("link"), "link", isDirectory = false, isSymlink = true, size = 6))
            "directory-link" -> listOf(FileMetadata(uri.child("link"), "link", isDirectory = true, isSymlink = true))
            "cycle" -> listOf(FileMetadata.dir(VfsUri.of("mem", "one", "/src"), "src"))
            "deep" -> listOf(FileMetadata.dir(uri.child("d"), "d"))
            else -> emptyList()
        }
        override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader {
            reads++
            return ByteArrayReader("secret".toByteArray())
        }
        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
            val bytes = ByteArrayOutputStream()
            return object : VfsWriter {
                override val writtenBytes get() = bytes.size().toLong()
                override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
                override suspend fun flush() = Unit
                override suspend fun commit() { committed = true; output = bytes.toByteArray() }
                override suspend fun abort() = Unit
            }
        }
        override suspend fun mkdir(uri: VfsUri, parents: Boolean) = Unit
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = Unit
        override suspend fun rename(from: VfsUri, to: VfsUri) = false
    }

    @Test fun `所有压缩路径拒绝链接和循环且不发布半成品`() = runBlocking {
        for (format in ArchiveCompressor.Format.entries) {
            val passwords = if (format == ArchiveCompressor.Format.ZIP) listOf(null, "pw") else listOf(null)
            for (password in passwords) {
                for (shape in listOf("root-link", "file-link", "directory-link", "cycle", "deep")) {
                    val store = Store(shape)
                    val locator = object : VfsLocator { override fun find(uri: VfsUri): VirtualFileSystem = store }
                    val err = runCatching {
                        ArchiveCompressor(locator).compress(listOf(VfsUri.of("mem", "one", "/src")),
                            VfsUri.of("mem", "one", "/out.${format.ext}"), format, password = password)
                    }.exceptionOrNull()
                    assertTrue("$format/$password/$shape must reject, got $err", err is VfsException)
                    assertEquals("must not read link target", 0, store.reads)
                    assertFalse("must not commit partial archive", store.committed)
                    assertEquals("original output", store.output.toString(Charsets.UTF_8))
                }
            }
        }
    }
}
