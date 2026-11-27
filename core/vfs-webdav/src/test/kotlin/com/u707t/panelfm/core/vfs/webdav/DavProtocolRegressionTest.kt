package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files

class DavProtocolRegressionTest {
    private fun config(port: Int) = DavConfig("127.0.0.1", port, false, "/", "", null, "test", false, 1_000)
    private fun environment() = VfsEnv(
        AppDirs(Files.createTempDirectory("davfiles").toString(), Files.createTempDirectory("davcache").toString()),
        PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    @Test fun rangeMetadataValidatesStartLengthAndStatus() {
        validateDavRangeResponse(206, "bytes 10-13/20", 10, 4)
        validateDavRangeResponse(206, "bytes 10-11/12", 10, 4)
        validateDavRangeResponse(200, null, 0, 4)
        for (case in listOf(Triple(200, null, 10L), Triple(206, "bytes 0-3/20", 10L), Triple(206, "bytes 10-14/20", 10L), Triple(206, null, 10L))) {
            assertTrue(runCatching { validateDavRangeResponse(case.first, case.second, case.third, 4) }.isFailure)
        }
    }

    @Test fun boundedStreamReadsOnlyRequestedWindowAndShortEof() {
        var reads = 0
        val endless = object : InputStream() { override fun read(): Int { reads++; return 42 } }
        assertEquals(17, readDavBounded(endless, 17).size)
        assertEquals(17, reads)
        assertArrayEquals(byteArrayOf(1, 2), readDavBounded(byteArrayOf(1, 2).inputStream(), 10))
        assertTrue(runCatching { readDavBounded(object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }, 10) }.isFailure)
    }

    @Test fun ignoredRangeCannotReturnUnboundedBodyOrWrongOffset() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val vfs = WebDavVfs(config(server.port), environment())
            val reader = vfs.openRead(VfsUri.of("dav", "127.0.0.1:${server.port}", "/file"))
            try {
                server.enqueue(MockResponse().setBody("0123456789"))
                assertEquals("0123", reader.readFullyAt(0, 4).decodeToString())
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-5/6").setBody("45"))
                assertEquals("45", reader.readFullyAt(4, 4).decodeToString())
                server.enqueue(MockResponse().setBody("0123456789"))
                assertTrue(runCatching { reader.readFullyAt(4, 4) }.isFailure)
            } finally { reader.close(); vfs.close() }
        }
    }

    @Test fun idleTimeoutsAreFiniteButCallDurationUnlimited() {
        val http = DavHttp.client(config(1))
        assertTrue(http.readTimeoutMillis > 0)
        assertTrue(http.writeTimeoutMillis > 0)
        assertEquals(0, http.callTimeoutMillis)
    }

    @Test fun cancellationClosesHeaderWaitCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = DavHttp.client(config(1))
            val call = http.newCall(Request.Builder().url(server.url("/stall")).build())
            assertNull(withTimeoutOrNull(300) { call.awaitDavResponse().use { it.code } })
            assertTrue(call.isCanceled())
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
    @Test fun cancellingBodyReadClosesResponseBeforeIdleTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("0123456789").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            val vfs = WebDavVfs(config(server.port), environment())
            val reader = vfs.openRead(VfsUri.of("dav", "127.0.0.1:${server.port}", "/file"))
            try {
                assertNull(withTimeoutOrNull(300) { reader.readFullyAt(0, 4) })
            } finally { reader.close(); vfs.close() }
        }
    }
}
