package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 协议切换 / 根路径 / SMB 共享名的一致性的回归锁（v2.0.24）。 */
class ConnectionFormLogicTest {

    @Test
    fun `切到 S3 强制清空根路径`() {
        assertEquals("/", basePathOnTypeChange(ConnectionType.WEBDAV, ConnectionType.S3, "/dav", "/dav"))
        assertEquals("/", basePathOnTypeChange(ConnectionType.FTP, ConnectionType.S3, "/home/user", null))
        // 落库同样强制 "/"：S3 的 basePath 是对象前缀，不能带隐藏值
        assertEquals("/", configBasePath(ConnectionType.S3, "/dav"))
        assertEquals("/", configBasePath(ConnectionType.S3, ""))
    }

    @Test
    fun `WebDAV 自动带出的根路径在切走后重置`() {
        assertEquals(
            "/",
            basePathOnTypeChange(ConnectionType.WEBDAV, ConnectionType.FTP, "/dav", "/dav"),
        )
    }

    @Test
    fun `用户手改过的根路径不跟着协议切换重置`() {
        assertEquals(
            "/home/data",
            basePathOnTypeChange(ConnectionType.WEBDAV, ConnectionType.FTP, "/home/data", "/dav"),
        )
        assertEquals(
            "/media",
            basePathOnTypeChange(ConnectionType.FTP, ConnectionType.SMB, "/media", null),
        )
    }

    @Test
    fun `非 S3 的空根路径收敛为斜杠`() {
        assertEquals("/", configBasePath(ConnectionType.FTP, "  "))
        assertEquals("/home", configBasePath(ConnectionType.FTP, " /home "))
    }

    @Test
    fun `SMB 起始路径可以是共享根或共享名下的子目录`() {
        assertNull(smbPathProblem("/", "public"))
        assertNull(smbPathProblem("/public", "public"))
        assertNull(smbPathProblem("/public/docs", "public"))
        // 大小写不敏感（SMB 共享名本身不区分大小写）
        assertNull(smbPathProblem("/Public/Docs", "public"))
        // 共享名为空时不校验：起始路径自己表达
        assertNull(smbPathProblem("/anything", ""))
    }

    @Test
    fun `SMB 起始路径与共享名冲突时给出可执行文案`() {
        val problem = smbPathProblem("/media/docs", "public")
        assertEquals("起始路径必须以 /public 开头（共享名），或留空表示共享根", problem)
        // 只是前缀相同但不在同一段（/public2 不是 /public 的子目录）
        assertEquals(true, smbPathProblem("/public2", "public") != null)
    }
}
