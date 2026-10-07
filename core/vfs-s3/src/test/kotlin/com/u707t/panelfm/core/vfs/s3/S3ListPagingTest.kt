package com.u707t.panelfm.core.vfs.s3

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.nio.file.Files

/**
 * `S3Vfs.list` 端到端（第 6 批 🟡7）：>1000 条时应按 continuation-token 翻页拉全。
 * 用 MockWebServer 喂两页真实响应，断言两页条目合并且映射正确。
 *
 * 单测环境 `android.util.Xml` 是桩，通过 [S3Client.parserFactory] 接缝换成 kxml2。
 */
class S3ListPagingTest {

    private lateinit var originalParserFactory: () -> XmlPullParser

    @Before
    fun setUp() {
        originalParserFactory = S3Client.parserFactory
        S3Client.parserFactory = { KXmlParser() }
    }

    @After
    fun tearDown() {
        S3Client.parserFactory = originalParserFactory
    }

    private fun env() = VfsEnv(
        appDirs = AppDirs(
            Files.createTempDirectory("files").toString(),
            Files.createTempDirectory("cache").toString(),
        ),
        dispatchers = PanelDispatchers(
            io = Dispatchers.IO,
            vfs = Dispatchers.IO,
            decode = Dispatchers.Default,
            main = Dispatchers.Default,
        ),
        localNetworkAllowed = { true },
    )

    private fun vfs(endpoint: String, authority: String) = S3Vfs(
        S3Config(
            endpoint = endpoint,
            uriAuthority = authority,
            accessKey = "AK",
            secretKey = "SK",
            region = "us-east-1",
            pathStyle = true,
            downloadDomain = null,
            bucket = null,
            timeoutMs = 5_000L,
        ),
        env(),
    )

    private val page1 = """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
        """<Name>bucket</Name><Prefix>dir/</Prefix><KeyCount>2</KeyCount><MaxKeys>2</MaxKeys>""" +
        """<Delimiter>/</Delimiter><IsTruncated>true</IsTruncated>""" +
        """<NextContinuationToken>tok-1</NextContinuationToken>""" +
        """<Contents><Key>dir/f1.txt</Key><Size>10</Size></Contents>""" +
        """<CommonPrefixes><Prefix>dir/s1/</Prefix></CommonPrefixes>""" +
        """</ListBucketResult>"""

    private val page2 = """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
        """<Name>bucket</Name><Prefix>dir/</Prefix><KeyCount>1</KeyCount><MaxKeys>2</MaxKeys>""" +
        """<Delimiter>/</Delimiter><IsTruncated>false</IsTruncated>""" +
        """<Contents><Key>dir/f2.txt</Key><Size>20</Size></Contents>""" +
        """</ListBucketResult>"""

    @Test
    fun `列表按 continuation-token 翻页拉全且映射正确`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(page1))
            server.enqueue(MockResponse().setBody(page2))

            val endpoint = "http://${server.hostName}:${server.port}"
            val v = vfs(endpoint, "${server.hostName}:${server.port}")
            try {
                val items = v.list(VfsUri.of("s3", "bucket", "/dir"))
                assertEquals(
                    setOf("f1.txt", "f2.txt", "s1"),
                    items.map { it.name }.toSet(),
                )
                assertEquals(3, items.size)
                assertTrue(items.first { it.name == "s1" }.isDirectory)
                assertEquals(2, server.requestCount)
            } finally {
                v.close()
            }
        } finally {
            server.shutdown()
        }
    }
}
