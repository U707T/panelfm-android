package com.u707t.panelfm.tools

import com.u707t.panelfm.core.vfs.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteHttpServerTest {
    private val root = VfsUri.of("memory", "test", "/shared")
    private class MemoryVfs : VirtualFileSystem {
        override val id = "memory"
        override val scheme = "memory"
        override val label = "memory"
        override val capabilities = VfsCapabilities(writable = false)
        override val state = MutableStateFlow<VfsState>(VfsState.Ready)
        val reads = AtomicInteger()
        var readerFactory: () -> VfsReader = { ByteArrayReader("payload".toByteArray()) }
        override suspend fun connect() = Unit
        override suspend fun list(uri: VfsUri, options: ListOptions) = emptyList<FileMetadata>()
        override suspend fun stat(uri: VfsUri): FileMetadata {
            reads.incrementAndGet()
            return FileMetadata(uri, uri.name, isDirectory = false, size = 7)
        }
        override fun openRead(uri: VfsUri, offset: Long, length: Long) = readerFactory()
        override suspend fun mkdir(uri: VfsUri, parents: Boolean): Unit = error("read-only")
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?): Unit = error("read-only")
        override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = error("read-only")
        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter = error("read-only")
    }
    private fun server(vfs: MemoryVfs): RemoteHttpServer = RemoteHttpServer(object : VfsLocator {
        override fun find(uri: VfsUri): VirtualFileSystem = vfs
    }, bindAddress = { InetAddress.getByName("127.0.0.1") })

    @Test fun `anonymous clients cannot reach VFS and restart rotates authorization`() {
        val vfs = MemoryVfs()
        val server = server(vfs)
        try {
            val address = server.start(root)!!
            val url = URI(address)
            val anonymous = URL("http://127.0.0.1:${url.port}/file").openConnection() as HttpURLConnection
            anonymous.connectTimeout = 2000; anonymous.readTimeout = 2000
            assertEquals(403, anonymous.responseCode)
            anonymous.disconnect()
            assertEquals(0, vfs.reads.get())
            assertEquals("payload", URL(address + "file").readText())
            server.stop()
            val next = server.start(root)!!
            assertNotEquals(URI(address).path, URI(next).path)
            val stale = URL("http://127.0.0.1:${URI(next).port}${url.path}file").openConnection() as HttpURLConnection
            stale.connectTimeout = 2000; stale.readTimeout = 2000
            assertEquals(403, stale.responseCode)
            stale.disconnect()
        } finally { server.stop() }
    }

    @Test fun `stop closes the live reader and revokes an established download`() {
        val vfs = MemoryVfs()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        vfs.readerFactory = {
            object : VfsReader {
                override val size = 7L
                override val supportsSeek = false
                override val position = 0L
                override suspend fun seek(position: Long) = Unit
                override suspend fun readFullyAt(position: Long, length: Int) = ByteArray(0)
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(closed.await(3, TimeUnit.SECONDS)) { "reader was not closed" }
                    return -1
                }
                override fun close() { closed.countDown() }
            }
        }
        val server = server(vfs)
        try {
            val address = URI(server.start(root)!!)
            Socket("127.0.0.1", address.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET ${address.path}file HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                server.stop()
                assertTrue(closed.await(1, TimeUnit.SECONDS))
                assertFalse(server.running)
                assertEquals("", server.url)
            }
        } finally { server.stop() }
    }

    @Test fun `line parsing is bounded even without newline`() {
        try {
            readRemoteLine(ByteArrayInputStream(ByteArray(4097) { 65 }), 4096)
            fail("oversized line accepted")
        } catch (_: IOException) { }
        assertEquals("GET / HTTP/1.1", readRemoteLine(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray()), 4096))
    }

    @Test fun `authorization precedes path normalization and cannot be escaped`() {
        assertNull(authorizedRemotePath("/wrong/a", "secret"))
        assertNull(authorizedRemotePath("/secret/../private", "secret"))
        assertNull(authorizedRemotePath("/secret/%2e%2e/private", "secret"))
        assertNull(authorizedRemotePath("/secretextra/file", "secret"))
        assertEquals("/a+b.txt", authorizedRemotePath("/secret/a+b.txt", "secret"))
        assertNull(normalizeRemotePath("/a\\..\\private"))
        assertNull(normalizeRemotePath("/a\u0000b"))
    }
}
