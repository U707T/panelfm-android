package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FTP 配置默认值（第 6 批 🔵14：隐式 TLS 的默认端口是 990）。 */
class FtpConfigTest {

    private fun config(
        type: ConnectionType,
        port: Int,
        options: Map<String, String> = emptyMap(),
    ) = ConnectionConfig(type = type, name = "t", host = "example.com", port = port, options = options)

    @Test
    fun `FTP 未填端口时默认 21`() {
        assertEquals(21, FtpConfig.from(config(ConnectionType.FTP, 0), null).port)
    }

    @Test
    fun `显式 FTPS 未填端口时默认 21`() {
        assertEquals(21, FtpConfig.from(config(ConnectionType.FTPS, 0), null).port)
    }

    @Test
    fun `隐式 FTPS 未填端口时默认 990`() {
        val cfg = FtpConfig.from(
            config(ConnectionType.FTPS, 0, mapOf(ConnectionConfig.OPT_IMPLICIT_TLS to "true")),
            null,
        )
        assertEquals(990, cfg.port)
        assertTrue(cfg.implicitTls)
        assertTrue(cfg.tls)
    }

    @Test
    fun `显式填写的端口优先`() {
        assertEquals(2121, FtpConfig.from(config(ConnectionType.FTP, 2121), null).port)
    }
}
