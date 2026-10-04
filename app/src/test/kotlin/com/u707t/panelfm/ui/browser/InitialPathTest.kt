package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「设为该网络存储的初始路径」（MT 0x7f1104ab）：当前路径 → 连接 options 的 initialPath。
 *
 * 语义与 `ConnectionConfig.openPath` 互为逆运算（openPath 是 initialPath → 打开路径）：
 *  - WebDAV：basePath 是挂载点，初始路径相对虚拟根；
 *  - 其余协议：初始路径相对 basePath。
 */
class InitialPathTest {

    private fun cfg(
        type: ConnectionType,
        basePath: String = "/",
        initial: String? = null,
    ) = ConnectionConfig(
        type = type,
        name = "t",
        basePath = basePath,
        options = if (initial == null) emptyMap() else mapOf(ConnectionConfig.OPT_INITIAL_PATH to initial),
    )

    // ---------------------------------------------------------------- WebDAV

    @Test
    fun `WebDAV 根目录 → 空串（清除初始路径）`() {
        assertEquals("", initialPathFor(cfg(ConnectionType.WEBDAV, basePath = "/dav"), "/"))
    }

    @Test
    fun `WebDAV 子目录 → 相对虚拟根`() {
        // Alist 挂载点 /dav：浏览 /photos 时初始路径 = photos（请求时拼回 /dav/photos）
        assertEquals("photos", initialPathFor(cfg(ConnectionType.WEBDAV, basePath = "/dav"), "/photos"))
        assertEquals("photos/2026", initialPathFor(cfg(ConnectionType.WEBDAV, basePath = "/dav"), "/photos/2026/"))
    }

    // ---------------------------------------------------------------- 其余协议（FTP/SFTP/SMB/S3）

    @Test
    fun `FTP basePath 之内的子目录 → 相对 basePath`() {
        assertEquals("pub", initialPathFor(cfg(ConnectionType.FTP, basePath = "/home/user"), "/home/user/pub"))
        assertEquals(
            "pub/deep",
            initialPathFor(cfg(ConnectionType.FTP, basePath = "/home/user"), "/home/user/pub/deep/"),
        )
    }

    @Test
    fun `FTP 恰好等于 basePath → 空串（回到连接根）`() {
        assertEquals("", initialPathFor(cfg(ConnectionType.FTP, basePath = "/home/user"), "/home/user"))
        assertEquals("", initialPathFor(cfg(ConnectionType.FTP, basePath = "/home/user/"), "/home/user/"))
    }

    @Test
    fun `FTP basePath 是根时 → 路径去前导斜杠`() {
        assertEquals("var/log", initialPathFor(cfg(ConnectionType.FTP, basePath = "/"), "/var/log"))
        assertEquals("", initialPathFor(cfg(ConnectionType.FTP, basePath = "/"), "/"))
    }

    @Test
    fun `路径不在连接根之内 → null（无法用相对路径表达）`() {
        assertNull(initialPathFor(cfg(ConnectionType.SFTP, basePath = "/home/user"), "/etc"))
        assertNull(initialPathFor(cfg(ConnectionType.FTP, basePath = "/data"), "/database"))  // 前缀像但不是子目录
        assertNull(initialPathFor(cfg(ConnectionType.SMB, basePath = "/share/sub"), "/share"))
    }

    @Test
    fun `S3 的 authority 是 bucket、路径从根开始 → 直接相对`() {
        assertEquals("2026/a.jpg", initialPathFor(cfg(ConnectionType.S3, basePath = "/"), "/2026/a.jpg"))
    }

    // ---------------------------------------------------------------- 与 openPath 互为逆运算

    @Test
    fun `写入的初始路径能被 openPath 还原（WebDAV 与 FTP 各一例）`() {
        val dav = cfg(ConnectionType.WEBDAV, basePath = "/dav")
        val davInitial = initialPathFor(dav, "/photos/2026")!!
        assertEquals(
            "/photos/2026",
            dav.copy(options = mapOf(ConnectionConfig.OPT_INITIAL_PATH to davInitial)).openPath,
        )

        val ftp = cfg(ConnectionType.FTP, basePath = "/home/user")
        val ftpInitial = initialPathFor(ftp, "/home/user/pub")!!
        assertEquals(
            "/home/user/pub",
            ftp.copy(options = mapOf(ConnectionConfig.OPT_INITIAL_PATH to ftpInitial)).openPath,
        )
    }
}
