package com.u707t.panelfm.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 打开连接时的初始路径语义（修复 WebDAV 把 basePath 又当路径导致的「/dav/dav」双前缀 404）。 */
class ConnectionOpenPathTest {

    @Test
    fun webDavUsesVirtualRoot() {
        val cfg = ConnectionConfig(
            type = ConnectionType.WEBDAV, name = "dav",
            host = "192.168.250.106", port = 5244, basePath = "/dav",
        )
        assertEquals("/", cfg.openPath)
    }

    @Test
    fun webDavInitialPathIsRelativeToMount() {
        val cfg = ConnectionConfig(
            type = ConnectionType.WEBDAV, name = "dav",
            host = "h", port = 80, basePath = "/dav",
            options = mapOf(ConnectionConfig.OPT_INITIAL_PATH to "media/music"),
        )
        assertEquals("/media/music", cfg.openPath)
    }

    @Test
    fun otherProtocolsUseBasePath() {
        val cfg = ConnectionConfig(
            type = ConnectionType.SFTP, name = "nas",
            host = "h", port = 22, basePath = "/home/user",
        )
        assertEquals("/home/user", cfg.openPath)
    }

    @Test
    fun otherProtocolsInitialPathAppendsToBasePath() {
        val cfg = ConnectionConfig(
            type = ConnectionType.FTP, name = "ftp",
            host = "h", port = 21, basePath = "/",
            options = mapOf(ConnectionConfig.OPT_INITIAL_PATH to "pub"),
        )
        assertEquals("/pub", cfg.openPath)
    }

    @Test
    fun defaultBasePathIsRoot() {
        val cfg = ConnectionConfig(type = ConnectionType.SFTP, name = "nas", host = "h", port = 22)
        assertEquals("/", cfg.openPath)
    }
}
