package com.u707t.panelfm.core.vfs.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 官方测试向量（AWS 文档 "Signature Calculation: Transfer Payload in a Single Chunk"
 * 的 GET Object 示例），签名必须逐字节一致。
 */
class SigV4Test {

    private val ak = "AKIAIOSFODNN7EXAMPLE"
    private val sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val now = Instant.parse("2013-05-24T00:00:00Z")

    @Test
    fun `GET Object 签名与官方一致`() {
        val signed = SigV4.sign(
            method = "GET",
            host = "examplebucket.s3.amazonaws.com",
            path = "/test.txt",
            query = emptyMap(),
            headers = mapOf("range" to "bytes=0-9"),
            payloadHash = SigV4.EMPTY_SHA256,
            accessKey = ak,
            secretKey = sk,
            region = "us-east-1",
            now = now,
        )
        assertEquals("20130524T000000Z", signed.amzDate)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.authorization,
        )
    }

    @Test
    fun `带查询参数的请求签名结构正确`() {
        val signed = SigV4.sign(
            method = "GET",
            host = "examplebucket.s3.amazonaws.com",
            path = "/",
            query = mapOf(
                "max-parts" to "2",
                "part-number-marker" to "0",
                "uploadId" to "abc123",
            ),
            headers = emptyMap(),
            payloadHash = SigV4.EMPTY_SHA256,
            accessKey = ak,
            secretKey = sk,
            region = "us-east-1",
            now = now,
        )
        assertTrue(signed.authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request"))
        assertTrue(signed.authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(signed.authorization.contains("Signature="))
        // 同一请求两次签名结果必须一致（可复现）
        val again = SigV4.sign(
            "GET", "examplebucket.s3.amazonaws.com", "/",
            mapOf("max-parts" to "2", "part-number-marker" to "0", "uploadId" to "abc123"),
            emptyMap(), SigV4.EMPTY_SHA256, ak, sk, "us-east-1", now,
        )
        assertEquals(signed.authorization, again.authorization)
    }

    @Test
    fun `uriEncode 规则`() {
        assertEquals("a%20b", SigV4.uriEncode("a b"))
        assertEquals("a/b", SigV4.uriEncode("a/b", encodeSlash = false))
        assertEquals("a%2Fb", SigV4.uriEncode("a/b"))
        assertEquals("~-_.", SigV4.uriEncode("~-_."))
    }
}
