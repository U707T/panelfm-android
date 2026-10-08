package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 局域网扫描端口 → 编辑器预选协议（第 7 批 🔵12：扫 445/21 却默认开 SFTP 表单）。 */
class LanScanPortHintTest {

    @Test
    fun `常见端口映射到对应协议`() {
        assertEquals(ConnectionType.WEBDAV, connectionTypeForScanPort(5244))
        assertEquals(ConnectionType.SFTP, connectionTypeForScanPort(22))
        assertEquals(ConnectionType.FTP, connectionTypeForScanPort(21))
        assertEquals(ConnectionType.SMB, connectionTypeForScanPort(445))
        assertEquals(ConnectionType.WEBDAV, connectionTypeForScanPort(80))
        assertEquals(ConnectionType.WEBDAV, connectionTypeForScanPort(443))
    }

    @Test
    fun `未知端口不猜测`() {
        assertNull(connectionTypeForScanPort(12345))
        assertNull(connectionTypeForScanPort(9000))
    }

    @Test
    fun `快捷端口列表 5244 在最前`() {
        // 顺序 / 默认端口都被实机截图验证过：5244 置顶（OpenList / Alist 的 WebDAV）
        assertEquals(5244, lanScanQuickPorts.first())
        assertTrue(lanScanQuickPorts.contains(22))
        assertTrue(lanScanQuickPorts.contains(21))
        assertTrue(lanScanQuickPorts.contains(445))
        assertTrue(lanScanQuickPorts.contains(80))
        assertTrue(lanScanQuickPorts.contains(443))
    }
}
