package com.u707t.panelfm.core.vfs.s3

import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * AWS Signature V4 签名（S3）。
 *  - 可测试：对官方测试向量（Example: GET Object）逐字节一致
 *  - 支持 path-style 与 virtual-host style，支持 UNSIGNED-PAYLOAD（流式上传）
 */
object SigV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    data class Signed(
        val authorization: String,
        val amzDate: String,
        val contentSha256: String,
        val signedHeaders: String,
    )

    private val amzDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun signingKey(secret: String, date: String, region: String, service: String = "s3"): ByteArray {
        val kDate = hmac(("AWS4" + secret).toByteArray(Charsets.UTF_8), date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    /** RFC3986 编码（'+' 用 %20，空格用 %20，'/' 保留） */
    fun uriEncode(value: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder()
        value.forEach { ch ->
            val c = ch
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                c == '/' && !encodeSlash -> sb.append(c)
                else -> sb.append(URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20"))
            }
        }
        return sb.toString()
    }

    /**
     * @param method GET/PUT/POST/DELETE/HEAD
     * @param path 已经解码的路径（内部会做 canonical 编码）
     * @param query 参数对（值可为空）
     */
    fun sign(
        method: String,
        host: String,               // 含端口（若有）
        path: String,
        query: Map<String, String?>,
        headers: Map<String, String>,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        region: String,
        now: Instant = Instant.now(),
    ): Signed {
        val amzDate = amzDateFmt.format(now)
        val date = dateFmt.format(now)

        val canonicalUri = if (path.isEmpty()) "/" else "/" + path.trimStart('/').split('/').joinToString("/") { uriEncode(it) }
        val canonicalQuery = query.entries
            .sortedWith(compareBy({ uriEncode(it.key) }, { uriEncode(it.value ?: "") }))
            .joinToString("&") { "${uriEncode(it.key)}=${uriEncode(it.value ?: "")}" }

        val allHeaders = headers.toMutableMap()
        allHeaders["host"] = host
        allHeaders["x-amz-date"] = amzDate
        allHeaders["x-amz-content-sha256"] = payloadHash

        val sortedNames = allHeaders.keys.map { it.lowercase() }.distinct().sorted()
        val canonicalHeaders = sortedNames.joinToString("") { name ->
            "$name:${(allHeaders.entries.first { it.key.lowercase() == name }.value).trim()}\n"
        }
        val signedHeaders = sortedNames.joinToString(";")

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = listOf(ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest.toByteArray())).joinToString("\n")

        val signature = hex(hmac(signingKey(secretKey, date, region), stringToSign))
        // 注意格式：AWS 规范里逗号后没有空格（服务器虽宽容，但按规范最稳）
        val authorization = "$ALGORITHM Credential=$accessKey/$scope,SignedHeaders=$signedHeaders,Signature=$signature"

        return Signed(authorization, amzDate, payloadHash, signedHeaders)
    }
}
