package com.u707t.panelfm.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 远程管理（内置 HTTP 服务）路径解析的回归测试。
 *
 * 这段逻辑是**安全关键**（越界访问的防线），此前没有任何单测 ——
 * 代码复审时补上：`+` 解码语义 + 穿越拒绝。
 */
class RemoteHttpPathTest {

    // ---------------------------------------------------------------- 解码

    @Test
    fun `加号是路径字符，不是空格`() {
        assertEquals("/a+b.txt", decodeRemotePath("/a+b.txt"))
        assertEquals("/a+b.txt", decodeRemotePath("/a%2Bb.txt"))
        // %20 才是空格
        assertEquals("/a b.txt", decodeRemotePath("/a%20b.txt"))
    }

    @Test
    fun `中文与保留字符可解码`() {
        assertEquals("/中文/目录", decodeRemotePath("/%E4%B8%AD%E6%96%87/%E7%9B%AE%E5%BD%95"))
        assertEquals("/a(b)[c]", decodeRemotePath("/a(b)%5Bc%5D"))
    }

    @Test
    fun `坏编码回退原文（不抛异常）`() {
        assertEquals("/%zz", decodeRemotePath("/%zz"))
    }

    // ---------------------------------------------------------------- 规整 / 穿越拒绝

    @Test
    fun `拒绝向上穿越`() {
        assertNull(normalizeRemotePath("/../etc/passwd"))
        assertNull(normalizeRemotePath("/a/../../b"))
        assertNull(normalizeRemotePath(".."))
        assertNull(normalizeRemotePath("/a/b/../../../"))
    }

    @Test
    fun `编码后的穿越同样被拦截（先解码再规整）`() {
        assertNull(normalizeRemotePath(decodeRemotePath("/%2e%2e/secret")))
        assertNull(normalizeRemotePath(decodeRemotePath("/a/%2E%2E/%2E%2E/b")))
    }

    @Test
    fun `重复斜杠与点段被规整`() {
        assertEquals("/a/b", normalizeRemotePath("//a///./b/"))
        assertEquals("/", normalizeRemotePath("/"))
        assertEquals("/a", normalizeRemotePath("a"))
        assertEquals("/a/b", normalizeRemotePath("/a/./b/"))
    }

    @Test
    fun `根内上升可回退（a 上、b 上都不越界）`() {
        assertEquals("/b", normalizeRemotePath("/a/../b"))
        assertEquals("/", normalizeRemotePath("/a/.."))
    }
}
