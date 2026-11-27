package com.u707t.panelfm.core.vfs.s3

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

class S3ProtocolRegressionTest {
    private fun config(endpoint: String) = S3Config(endpoint, "endpoint", "AK", "SK", "us-east-1", true, null, null, 1_000)
    private fun environment() = VfsEnv(
        AppDirs(Files.createTempDirectory("s3files").toString(), Files.createTempDirectory("s3cache").toString()),
        PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
    )

    @Test fun crossBucketFastPathsNeverIssueCopyOrDelete() = runBlocking {
        val vfs = S3Vfs(config("http://127.0.0.1:1"), environment())
        try {
            val from = VfsUri.of("s3", "source", "/a")
            val to = VfsUri.of("s3", "destination", "/a")
            assertFalse(vfs.rename(from, to))
            assertFalse(vfs.serverSideCopy(from, to))
        } finally { vfs.close() }
    }

    @Test fun multipartCompletionHashMatchesActualUtf8Body() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<CompleteMultipartUploadResult/>"))
            val client = S3Client(config(server.url("/").toString()))
            try {
                client.completeMultipart("bucket", "key", "upload", listOf(1 to "etag"))
                val request = server.takeRequest()
                val bytes = request.body.readByteArray()
                assertTrue(bytes.isNotEmpty())
                assertEquals(SigV4.sha256Hex(bytes), request.getHeader("x-amz-content-sha256"))
                assertNotEquals(SigV4.EMPTY_SHA256, request.getHeader("x-amz-content-sha256"))
            } finally { client.close() }
        }
    }

    @Test fun ignoredRangeIsBoundedAndNonzeroRangeCannotReadFileHead() = runBlocking {
        MockWebServer().use { server ->
            val client = S3Client(config(server.url("/").toString()))
            try {
                server.enqueue(MockResponse().setBody("0123456789"))
                assertEquals("0123", client.getBytes("bucket", "key", 0, 4).decodeToString())
                server.enqueue(MockResponse().setBody("0123456789"))
                assertTrue(runCatching { client.getBytes("bucket", "key", 4, 4) }.isFailure)
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-5/6").setBody("45"))
                assertEquals("45", client.getBytes("bucket", "key", 4, 4).decodeToString())
                assertEquals(0, client.getBytes("bucket", "key", 0, 0).size)
            } finally { client.close() }
        }
    }

    @Test fun rangeMetadataRejectsWrongOffsetsAndOverlongIntervals() {
        validateS3RangeResponse(206, "bytes 4-7/10", 4, 4)
        validateS3RangeResponse(206, "bytes 4-5/6", 4, 4)
        validateS3RangeResponse(200, null, 0, 4)
        for (case in listOf(Triple(200, null, 4L), Triple(206, "bytes 0-3/10", 4L), Triple(206, "bytes 4-8/10", 4L), Triple(206, null, 4L))) {
            assertTrue(runCatching { validateS3RangeResponse(case.first, case.second, case.third, 4) }.isFailure)
        }
    }

    @Test fun boundedStreamNeverReadsBeyondWindowAndHandlesShortEof() {
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int { reads++; return 42 }
        }
        assertEquals(17, readBounded(endless, 17).size)
        assertEquals(17, reads)
        assertArrayEquals(byteArrayOf(1, 2), readBounded(byteArrayOf(1, 2).inputStream(), 10))
    }

    @Test fun idleTimeoutIsFiniteButWholeTransferDurationIsUnlimited() {
        val client = S3Client(config("http://127.0.0.1:1"))
        try {
            assertTrue(client.http.readTimeoutMillis > 0)
            assertTrue(client.http.writeTimeoutMillis > 0)
            assertEquals(0, client.http.callTimeoutMillis)
        } finally { client.close() }
    }

    @Test fun cancellingHeaderWaitCancelsLiveHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = S3Client(config(server.url("/").toString()))
            val call = client.http.newCall(Request.Builder().url(server.url("/stall")).build())
            try {
                assertNull(withTimeoutOrNull(300) { call.awaitS3Response().use { it.code } })
                assertTrue(call.isCanceled())
            } finally { client.close() }
        }
    }
    @Test fun cancellingBodyReadClosesResponseBeforeIdleTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("0123456789").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            val client = S3Client(config(server.url("/").toString()))
            try {
                assertNull(withTimeoutOrNull(300) { client.getBytes("bucket", "key", 0, 4) })
            } finally { client.close() }
        }
    }
}
