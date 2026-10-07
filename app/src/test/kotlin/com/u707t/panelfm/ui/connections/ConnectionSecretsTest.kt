package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.s3.S3Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 口令编解码往返（第 7 批 🔴1 的回归锁定）：
 * 「回填 → 保存」必须恒等，且读取端对历史数据（S3 的 `AK:SK` 旧拼接）保持兼容。
 */
class ConnectionSecretsTest {

    // ---------------------------------------------------------------- S3

    @Test
    fun `S3 保存为纯 SK（不再拼接 AK 前缀）`() {
        assertEquals("SK123", ConnectionSecrets.build(ConnectionType.S3, "SK123", "", ""))
    }

    @Test
    fun `S3 空口令为 null`() {
        assertNull(ConnectionSecrets.build(ConnectionType.S3, "", "", ""))
    }

    @Test
    fun `S3 回填剥离旧 AK 前缀`() {
        assertEquals("SK123", ConnectionSecrets.passwordForEdit(ConnectionType.S3, "AKIAEXAMPLE:SK123"))
    }

    @Test
    fun `S3 回填裸 SK 原样`() {
        assertEquals("SK123", ConnectionSecrets.passwordForEdit(ConnectionType.S3, "SK123"))
    }

    @Test
    fun `S3 多段冒号按第一段切（与读取端 split limit=2 对称）`() {
        assertEquals("B:C", ConnectionSecrets.s3SecretKeyOf("A:B:C"))
    }

    @Test
    fun `S3 回填保存往返恒等（旧格式升级后不再层层加前缀）`() {
        // 旧数据 "AK:SK" → 回填得 SK → 保存得纯 SK；再回填再保存仍恒等
        val firstEdit = ConnectionSecrets.passwordForEdit(ConnectionType.S3, "AKIAEXAMPLE:SK123")
        val stored = ConnectionSecrets.build(ConnectionType.S3, firstEdit, "", "")
        assertEquals("SK123", stored)
        val secondEdit = ConnectionSecrets.passwordForEdit(ConnectionType.S3, stored)
        assertEquals("SK123", secondEdit)
        assertEquals(stored, ConnectionSecrets.build(ConnectionType.S3, secondEdit, "", ""))
    }

    @Test
    fun `S3Config 读取端兼容新旧两种存储形态（锁定修复不破坏历史数据）`() {
        val config = ConnectionConfig(type = ConnectionType.S3, name = "demo", host = "s3.example.com", user = "AKIAEXAMPLE")
        assertEquals("SK123", S3Config.from(config, "AKIAEXAMPLE:SK123").secretKey)
        assertEquals("SK123", S3Config.from(config, "SK123").secretKey)
        assertEquals("AKIAEXAMPLE", S3Config.from(config, "SK123").accessKey)
    }

    // ---------------------------------------------------------------- SFTP

    @Test
    fun `SFTP 三口令 JSON 往返恒等`() {
        val stored = ConnectionSecrets.build(ConnectionType.SFTP, "pw", "pass1", "jumppw")!!
        assertEquals("pw", ConnectionSecrets.passwordForEdit(ConnectionType.SFTP, stored))
        assertEquals(stored, ConnectionSecrets.build(ConnectionType.SFTP, "pw", "pass1", "jumppw"))
    }

    @Test
    fun `SFTP 全空为 null`() {
        assertNull(ConnectionSecrets.build(ConnectionType.SFTP, "", "", ""))
    }

    @Test
    fun `SFTP 裸密码兼容回填（手填历史数据）`() {
        assertEquals("pw", ConnectionSecrets.passwordForEdit(ConnectionType.SFTP, "pw"))
    }

    // ------------------------------------------------- 裸密码协议（FTP / FTPS / WebDAV / SMB）

    @Test
    fun `裸密码协议原样往返`() {
        for (t in listOf(ConnectionType.FTP, ConnectionType.FTPS, ConnectionType.WEBDAV, ConnectionType.SMB)) {
            assertEquals("pw", ConnectionSecrets.build(t, "pw", "", ""))
            assertEquals("pw", ConnectionSecrets.passwordForEdit(t, "pw"))
            assertNull(ConnectionSecrets.build(t, "", "", ""))
        }
    }
}
