package com.u707t.panelfm.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WebDAV URL 解析（复刻 MT 的 URL 单行输入）与回填。 */
class WebDavUrlTest {

    @Test
    fun parsesFullUrl() {
        val parts = WebDavUrl.parse("http://192.168.250.106:5244/dav")!!
        assertEquals(false, parts.secure)
        assertEquals("192.168.250.106", parts.host)
        assertEquals(5244, parts.port)
        assertEquals("/dav", parts.path)
    }

    @Test
    fun httpsAndDefaultPorts() {
        val https = WebDavUrl.parse("https://nas.example.com/dav/files")!!
        assertEquals(true, https.secure)
        assertEquals(443, https.port)
        assertEquals("/dav/files", https.path)

        val http = WebDavUrl.parse("http://192.168.1.9")!!
        assertEquals(80, http.port)
        assertEquals("/", http.path)
    }

    @Test
    fun withoutSchemeAssumesHttp() {
        val parts = WebDavUrl.parse("192.168.1.9:5244/dav/")!!
        assertEquals(false, parts.secure)
        assertEquals("192.168.1.9", parts.host)
        assertEquals(5244, parts.port)
        assertEquals("/dav", parts.path)
    }

    @Test
    fun decodesPercentEncodingAndStripsQuery() {
        val parts = WebDavUrl.parse("http://host:8080/dav/%E4%B8%AD%E6%96%87/?x=1#f")!!
        assertEquals("/dav/中文", parts.path)
    }

    @Test
    fun ipv6Literal() {
        val parts = WebDavUrl.parse("http://[fe80::1]:5244/dav")!!
        assertEquals("fe80::1", parts.host)
        assertEquals(5244, parts.port)
        assertEquals("/dav", parts.path)
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(WebDavUrl.parse(""))
        assertNull(WebDavUrl.parse("   "))
        assertNull(WebDavUrl.parse("http://"))
        assertNull(WebDavUrl.parse("http://host:0/dav"))
        assertNull(WebDavUrl.parse("http://host:70000/dav"))
        assertNull(WebDavUrl.parse("http://host:abc/dav"))
    }

    @Test
    fun buildRoundTrip() {
        val url = WebDavUrl.build("192.168.250.106", 5244, secure = false, basePath = "/dav")
        assertEquals("http://192.168.250.106:5244/dav", url)
        val again = WebDavUrl.parse(url)!!
        assertEquals("192.168.250.106", again.host)
        assertEquals(5244, again.port)
        assertEquals("/dav", again.path)
        assertEquals(false, again.secure)
    }

    @Test
    fun ipv6BuildRoundTrip() {
        // 第 7 批 🟡4：parse 会剥离 IPv6 方括号存裸地址，build 必须还原——否则编辑-保存把 host 改坏
        val url = WebDavUrl.build("fe80::1", 5244, secure = false, basePath = "/dav")
        assertEquals("http://[fe80::1]:5244/dav", url)
        val again = WebDavUrl.parse(url)!!
        assertEquals("fe80::1", again.host)
        assertEquals(5244, again.port)
        assertEquals("/dav", again.path)
        assertEquals(false, again.secure)
    }

    @Test
    fun stripsQueryAndFragmentWhenNoPath() {
        // 第 7 批 🟡4：无路径 URL 的 ? / # 之前会并进 host
        val q = WebDavUrl.parse("http://host?x=1")!!
        assertEquals("host", q.host)
        assertEquals(80, q.port)
        assertEquals("/", q.path)

        val f = WebDavUrl.parse("http://host#frag")!!
        assertEquals("host", f.host)
        assertEquals("/", f.path)
    }

    @Test
    fun ipv6WithQueryIsAccepted() {
        val parts = WebDavUrl.parse("http://[::1]?x=1")!!
        assertEquals("::1", parts.host)
        assertEquals(80, parts.port)
    }
}
