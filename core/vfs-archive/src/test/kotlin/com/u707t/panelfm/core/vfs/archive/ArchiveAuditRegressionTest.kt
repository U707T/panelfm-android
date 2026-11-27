package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.zip.CRC32
import java.util.zip.Inflater

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveAuditRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun before() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun after() { Dispatchers.resetMain() }
    private fun uri(path: String) = VfsUri.of("mem", "one", path)
    private fun env() = VfsEnv(AppDirs(tmp.root.path, tmp.root.path), PanelDispatchers())

    private class Store : VirtualFileSystem, VfsFileIdentityResolver {
        val identities = mutableMapOf<String, String>()
        override fun fileIdentityPath(uri: VfsUri): String? = identities[uri.path]
        override fun sameFileIdentity(left: VfsUri, right: VfsUri): Boolean? {
            val a = identities[left.path] ?: return null
            val b = identities[right.path] ?: return null
            return a == b
        }
        override val id = "audit-store"
        override val scheme = "mem"
        override val label = "test"
        override val capabilities = VfsCapabilities(writable = true)
        override val state = MutableStateFlow<VfsState>(VfsState.Ready)
        val nodes = linkedMapOf<String, FileMetadata>()
        val files = linkedMapOf<String, ByteArray>()
        var opens = 0
        var reads = 0
        var aborts = 0
        fun put(uri: VfsUri, data: ByteArray) { nodes[uri.path] = FileMetadata(uri, uri.name, false, size = data.size.toLong()); files[uri.path] = data }
        fun dir(uri: VfsUri, link: Boolean = false) { nodes[uri.path] = FileMetadata(uri, uri.name, true, isSymlink = link) }
        override suspend fun connect() = Unit
        override suspend fun stat(uri: VfsUri) = nodes[uri.path] ?: throw VfsException.NotFound(uri)
        override suspend fun list(uri: VfsUri, options: ListOptions) = nodes.values.filter { it.uri.parent == uri }
        override suspend fun mkdir(uri: VfsUri, parents: Boolean) { dir(uri) }
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = Unit
        override suspend fun rename(from: VfsUri, to: VfsUri) = false
        override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader { reads++; return ByteArrayReader(files.getValue(uri.path)) }
        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
            opens++
            val out = ByteArrayOutputStream()
            return object : VfsWriter {
                override val writtenBytes get() = out.size().toLong()
                override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { out.write(buffer, offset, length) }
                override suspend fun flush() = Unit
                override suspend fun commit() { put(uri, out.toByteArray()) }
                override suspend fun abort() { aborts++ }
            }
        }
    }
    private fun locator(store: Store) = object : VfsLocator { override fun find(uri: VfsUri): VirtualFileSystem = store }
    private fun archive(file: File, kind: ArchiveVfs.ArchiveKind = ArchiveVfs.ArchiveKind.ZIP) =
        ArchiveVfs(uri("/host.${kind.id}"), kind, file, env())
    private fun zip(file: File, name: String, bytes: ByteArray) {
        ZipArchiveOutputStream(file).use { out ->
            out.putArchiveEntry(ZipArchiveEntry(name).apply { method = ZipMethod.STORED.code; size = bytes.size.toLong(); crc = CRC32().also { it.update(bytes) }.value })
            out.write(bytes); out.closeArchiveEntry()
        }
    }
    private suspend fun readAll(vfs: ArchiveVfs, uri: VfsUri) {
        val reader = vfs.openRead(uri)
        try { val b = ByteArray(8192); while (reader.read(b, 0, b.size) >= 0) {} } finally { reader.close() }
    }

    @Test fun `existing targets are preserved for every creation format`() = runBlocking {
        for (format in ArchiveCompressor.Format.entries) {
            val store = Store(); store.put(uri("/source"), byteArrayOf(1)); store.put(uri("/out"), byteArrayOf(9))
            val error = runCatching { ArchiveCompressor(locator(store)).compress(listOf(uri("/source")), uri("/out"), format) }.exceptionOrNull()
            assertTrue(error is VfsException.IllegalArgument); assertEquals(0, store.opens); assertArrayEquals(byteArrayOf(9), store.files["/out"])
        }
    }
    @Test fun `self and output inside selected directory are refused before writing`() = runBlocking {
        val store = Store(); store.dir(uri("/source")); store.put(uri("/a"), byteArrayOf(1))
        for ((src, dest) in listOf(uri("/a") to uri("/a"), uri("/source") to uri("/source/out.zip"))) {
            assertTrue(runCatching { ArchiveCompressor(locator(store)).compress(listOf(src), dest) }.exceptionOrNull() is VfsException.IllegalArgument)
        }
        assertEquals(0, store.opens)
    }
    @Test fun `canonical destination inside source is refused even with a different URI path`() = runBlocking {
        val store = Store(); store.dir(uri("/source"))
        store.identities["/source"] = "/real/source"
        store.identities["/alias/out.zip"] = "/real/source/nested/out.zip"
        val error = runCatching { ArchiveCompressor(locator(store)).compress(listOf(uri("/source")), uri("/alias/out.zip")) }.exceptionOrNull()
        assertTrue(error is VfsException.IllegalArgument); assertEquals(0, store.opens)
    }
    @Test fun `7z preserves empty root and nested directories with and without password`() = runBlocking {
        for (password in listOf(null, "pw")) {
            val store = Store(); store.dir(uri("/empty")); store.dir(uri("/tree")); store.dir(uri("/tree/nested"))
            ArchiveCompressor(locator(store)).compress(listOf(uri("/empty"), uri("/tree")), uri("/out"), ArchiveCompressor.Format.SEVEN_Z, password = password)
            val file = tmp.newFile(); file.writeBytes(store.files.getValue("/out"))
            val builder = SevenZFile.builder().setFile(file); if (password != null) builder.setPassword(password)
            builder.get().use { z ->
                val names = mutableSetOf<String>(); var e = z.nextEntry
                while (e != null) { assertTrue(e.isDirectory); names += e.name.trimEnd('/'); e = z.nextEntry }
                assertEquals(setOf("empty", "tree", "tree/nested"), names)
            }
        }
    }
    @Test fun `flat integrity enumeration includes a file below sixteen directories and checks stored CRC`() = runBlocking {
        val file = tmp.newFile(); val name = (1..25).joinToString("/") { "d$it" } + "/data"; zip(file, name, "safe".toByteArray())
        val offset = ZipFile.builder().setFile(file).get().use { it.getEntry(name).dataOffset }
        RandomAccessFile(file, "rw").use { it.seek(offset); it.write('X'.code) }
        archive(file).use { v ->
            v.connect(); val entries = v.integrityEntries(); assertEquals(1, entries.size)
            assertTrue(runCatching { readAll(v, entries.single().uri) }.exceptionOrNull() is VfsException.ProtocolError)
        }
    }
    @Test fun `unsafe hidden entries prevent an all contents passed result`() = runBlocking {
        val file = tmp.newFile(); zip(file, "../hidden", byteArrayOf(1))
        archive(file).use { v -> assertTrue(runCatching { v.integrityEntries() }.exceptionOrNull() is VfsException.Unsupported) }
    }
    @Test fun `zip additions reject directory symlinks and leave host unchanged`() = runBlocking {
        val file = tmp.newFile(); zip(file, "original", byteArrayOf(1)); val original = file.readBytes()
        val store = Store(); store.put(uri("/host.zip"), original); store.dir(uri("/source")); store.dir(uri("/source/link"), link = true)
        archive(file).use { v ->
            val error = runCatching { ZipEditor(v, locator(store)).rewrite(additions = listOf("source" to uri("/source"))) }.exceptionOrNull()
            assertTrue(error is VfsException.Unsupported); assertEquals(0, store.reads); assertEquals(0, store.opens)
            assertArrayEquals(original, store.files["/host.zip"])
        }
    }
    @Test fun `zip addition chunk progress can cancel before publishing`() = runBlocking {
        val file = tmp.newFile(); zip(file, "original", byteArrayOf(1)); val original = file.readBytes()
        val store = Store(); store.put(uri("/host.zip"), original); store.put(uri("/big"), ByteArray(1024 * 1024))
        var chunks = 0
        archive(file).use { v ->
            val error = runCatching { ZipEditor(v, locator(store)).rewrite(additions = listOf("big" to uri("/big")), onProgress = { _, _ -> if (++chunks == 2) throw CancellationException("stop") }) }.exceptionOrNull()
            assertTrue(error is CancellationException); assertEquals(2, chunks); assertEquals(0, store.opens)
            assertArrayEquals(original, store.files["/host.zip"])
        }
    }
    @Test fun `zip additions share traversal depth and repeated directory guards`() = runBlocking {
        for (deep in listOf(false, true)) {
            val file = tmp.newFile(); zip(file, "original", byteArrayOf(1)); val original = file.readBytes()
            val store = Store(); store.put(uri("/host.zip"), original); store.dir(uri("/source"))
            if (deep) {
                var parent = uri("/source")
                repeat(66) { parent = parent.child("d"); store.dir(parent) }
            }
            archive(file).use { v ->
                val additions = if (deep) listOf("source" to uri("/source"))
                    else listOf("first" to uri("/source"), "second" to uri("/source"))
                val error = runCatching { ZipEditor(v, locator(store)).rewrite(additions = additions) }.exceptionOrNull()
                assertTrue(error is VfsException.ProtocolError); assertEquals(0, store.opens)
                assertArrayEquals(original, store.files["/host.zip"])
            }
        }
    }
    @Test fun `stored ZIP actual size is checked independently of CRC`() = runBlocking {
        val file = tmp.newFile(); zip(file, "data", "safe".toByteArray())
        val bytes = file.readBytes()
        val central = bytes.indices.first { i -> i + 3 < bytes.size && bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
        bytes[central + 24] = 5 // central uncompressed size; payload/CRC/compressed size remain unchanged
        file.writeBytes(bytes)
        archive(file).use { v ->
            val item = v.integrityEntries().single()
            assertTrue(runCatching { readAll(v, item.uri) }.exceptionOrNull() is VfsException.ProtocolError)
        }
    }
    @Test fun `resource ownership closes on constructor or positioning failure only`() {
        var closed = 0; val resource = AutoCloseable { closed++ }
        val expected = IOException("header")
        assertSame(expected, runCatching { closeOnFailure(resource) { throw expected } }.exceptionOrNull())
        assertEquals(1, closed)
        closeOnFailure(resource) { 1 }; assertEquals(1, closed)
    }
    @Test fun `encrypted header read failure closes raw source`() {
        var closed = false
        val source = object : InputStream() { override fun read(): Int = throw IOException("truncated"); override fun close() { closed = true } }
        assertTrue(runCatching { ZipCryptoReader.decrypt(source, "pw", null, 8) }.exceptionOrNull() is IOException)
        assertTrue(closed)
    }
    @Test fun `closing encrypted deflate stream ends its inflater even if source close throws`() {
        val inflater = Inflater(true)
        val source = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { throw IOException("close") } }
        val stream = ZipCryptoReader.OwnedInflaterInputStream(source, inflater)
        assertTrue(runCatching { stream.close() }.exceptionOrNull() is IOException)
        assertTrue("Inflater must be ended deterministically", runCatching { inflater.inflate(ByteArray(1)) }.isFailure)
        stream.close()
    }
}
