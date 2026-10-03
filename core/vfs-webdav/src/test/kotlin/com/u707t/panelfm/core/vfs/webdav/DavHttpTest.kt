package com.u707t.panelfm.core.vfs.webdav

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** DavHttp URL 拼接 + 重定向策略（保方法保 body / 跨主机去凭据 / 根路径保留尾斜杠）。 */
class DavHttpTest {

    private fun cfg(basePath: String = "/dav") = DavConfig(
        host = "192.168.1.9",
        port = 5244,
        secure = false,
        basePath = basePath,
        user = "admin",
        password = "x",
        userAgent = "test",
        trustSelfSigned = false,
        timeoutMs = 10_000,
    )

    // ------------------------------------------------------------------ URL 拼接

    @Test
    fun urlPrependsBasePath() {
        assertEquals(
            "http://192.168.1.9:5244/dav/sub%20dir/%E4%B8%AD%E6%96%87.txt",
            DavHttp.url(cfg(), "/sub dir/中文.txt").toString(),
        )
    }

    @Test
    fun rootKeepsTrailingSlash() {
        // 挂载点根：必须保留尾斜杠（部分服务器对集合根缺斜杠会 301/404）
        assertEquals("http://192.168.1.9:5244/dav/", DavHttp.url(cfg(), "/").toString())
        assertEquals("http://192.168.1.9:5244/dav/", DavHttp.url(cfg(), "").toString())
    }

    @Test
    fun rootBasePath() {
        assertEquals("http://192.168.1.9:5244/", DavHttp.url(cfg("/"), "/").toString())
        assertEquals("http://192.168.1.9:5244/a/b", DavHttp.url(cfg("/"), "/a/b").toString())
    }

    @Test
    fun nestedBasePath() {
        assertEquals(
            "http://192.168.1.9:5244/remote.php/dav/files/u/",
            DavHttp.url(cfg("/remote.php/dav/files/u"), "/").toString(),
        )
    }

    // ------------------------------------------------------------------ 重定向

    private fun buildResponse(request: Request, code: Int, location: String?): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { if (location != null) header("Location", location) }
            .body("".toResponseBody(null))
            .build()

    @Test
    fun propfindRedirectKeepsMethodAndBody() {
        val body = "<?xml version=\"1.0\"?>".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url("http://127.0.0.1:5244/dav").method("PROPFIND", body).build()
        val next = DavRedirectInterceptor.redirectRequest(req, buildResponse(req, 301, "/dav/"))!!
        assertEquals("PROPFIND", next.method)
        assertNotNull(next.body)
        assertEquals("/dav/", next.url.encodedPath)
    }

    @Test
    fun getRedirectStaysGet() {
        val req = Request.Builder().url("http://127.0.0.1:5244/a").get().build()
        val next = DavRedirectInterceptor.redirectRequest(req, buildResponse(req, 302, "/b"))!!
        assertEquals("GET", next.method)
        assertNull(next.body)
    }

    @Test
    fun crossHostRedirectStripsAuthorization() {
        val body = "x".toRequestBody(null)
        val req = Request.Builder().url("http://localhost:5244/dav")
            .header("Authorization", "Basic abc")
            .method("PROPFIND", body)
            .build()
        val next = DavRedirectInterceptor.redirectRequest(
            req,
            buildResponse(req, 301, "http://127.0.0.1:5244/dav/"),
        )!!
        assertNull(next.header("Authorization"))
        assertEquals("PROPFIND", next.method)
        assertEquals("/dav/", next.url.encodedPath)
    }

    @Test
    fun sameHostRedirectKeepsAuthorization() {
        val body = "x".toRequestBody(null)
        val req = Request.Builder().url("http://localhost:5244/dav")
            .header("Authorization", "Basic abc")
            .method("PROPFIND", body)
            .build()
        val next = DavRedirectInterceptor.redirectRequest(
            req,
            buildResponse(req, 301, "http://localhost:5244/dav/"),
        )!!
        assertEquals("Basic abc", next.header("Authorization"))
    }

    @Test
    fun noLocationStopsFollowing() {
        val req = Request.Builder().url("http://127.0.0.1:5244/dav").get().build()
        assertNull(DavRedirectInterceptor.redirectRequest(req, buildResponse(req, 301, null)))
    }
}
