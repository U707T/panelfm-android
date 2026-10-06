package com.u707t.panelfm.core.vfs.s3

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「实际发送的路径」与「签名用的路径」必须一致。
 *
 * 回归背景（本测试发现并锁死）：原实现有两处各自都会造成 `SignatureDoesNotMatch`：
 *  ① 签名侧用 `URLDecoder.decode(encodedPath)` 反推未编码路径 —— `URLDecoder` 是**表单**语义，
 *     把路径里的 `+` 解成**空格**（key `a+b.txt` 被签成了 `a b.txt`）；
 *  ② 发送侧用 `HttpUrl.Builder.addPathSegment` —— OkHttp 认为 `+` 是安全字符，会把它**原样**
 *     留在请求路径里，而 AWS 的 canonical URI 要求 `+` → `%2B`。
 *
 * 现在两侧统一为「逐段 RFC3986 编码（只保留 `A-Za-z0-9-._~`）」，与 AWS SDK 行为一致。
 * 这里用**生产代码**的 `url()` 与 `signingPathOf()`，断言三个不变量：
 *  ① 编码结果 = 硬编码的期望值（与服务器的契约，包含 `+` 必须编码）；
 *  ② `canonicalUri(signingPathOf(encodedPath)) == encodedPath`（签名与发送一致）；
 *  ③ query 与 bucket 形态不影响路径。
 */
class S3SigningPathTest {

    private fun client(pathStyle: Boolean = true) = S3Client(
        S3Config(
            endpoint = "https://s3.example.com",
            uriAuthority = "s3.example.com:443",
            accessKey = "AK",
            secretKey = "SK",
            region = "us-east-1",
            pathStyle = pathStyle,
            downloadDomain = null,
            bucket = null,
            timeoutMs = 5_000L,
        )
    )

    /** 不变量 ②：签名路径能逐字节还原真实发送路径。 */
    private fun assertSigningMatchesWire(key: String, bucket: String? = "photos", pathStyle: Boolean = true) {
        val encoded = client(pathStyle).url(bucket, key).encodedPath
        assertEquals(
            "签名路径必须与真实发送路径一致：key=$key",
            encoded,
            SigV4.canonicalUri(S3Client.signingPathOf(encoded)),
        )
    }

    @Test
    fun `加号必须编码为 %2B 且两侧一致`() {
        val u = client().url("photos", "a+b.txt")
        // 契约：`+` 属于「必须编码」的字符（AWS canonical 要求），不能留字面量
        assertEquals("/photos/a%2Bb.txt", u.encodedPath)
        assertEquals(u.encodedPath, SigV4.canonicalUri(S3Client.signingPathOf(u.encodedPath)))
        assertSigningMatchesWire("plus+plus")
    }

    @Test
    fun `空格 中文 百分号 与保留字符都两侧一致`() {
        listOf(
            "plain.txt",
            "a b.txt",
            "中文 名+%2B.txt",
            "100%.txt",
            "a&b=c.txt",
            "dir one/sub+dir/中文.txt",
            "a?b#c.txt",
        ).forEach { assertSigningMatchesWire(it) }
    }

    @Test
    fun `多级目录逐段编码且保留斜杠`() {
        val u = client().url("photos", "dir one/sub+dir/中文.txt")
        assertEquals("/photos/dir%20one/sub%2Bdir/%E4%B8%AD%E6%96%87.txt", u.encodedPath)
        assertEquals(u.encodedPath, SigV4.canonicalUri(S3Client.signingPathOf(u.encodedPath)))
    }

    @Test
    fun `根路径与虚拟主机形态`() {
        // ListBuckets：路径为空 → canonical "/"
        assertEquals("/", client().url(null, null).encodedPath)
        assertEquals("/", SigV4.canonicalUri(S3Client.signingPathOf("/")))
        // virtual-host style：bucket 进 host，路径里只有 key
        val virtualHost = client(pathStyle = false).url("photos", "a+b.txt")
        assertEquals("/a%2Bb.txt", virtualHost.encodedPath)
        assertEquals(virtualHost.encodedPath, SigV4.canonicalUri(S3Client.signingPathOf(virtualHost.encodedPath)))
    }

    @Test
    fun `query 参数不进入路径`() {
        val u = client().url("photos", "a+b.txt", mapOf("list-type" to "2", "prefix" to "dir one/"))
        assertEquals("/photos/a%2Bb.txt", u.encodedPath)
        assertEquals(
            u.encodedPath,
            SigV4.canonicalUri(S3Client.signingPathOf(u.encodedPath)),
        )
    }
}
