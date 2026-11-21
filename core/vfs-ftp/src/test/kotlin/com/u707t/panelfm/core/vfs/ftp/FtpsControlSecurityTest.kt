package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.VfsEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPSClient
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FtpsControlSecurityTest {
    /** Models an FTPS server which accepts CCC, exposing the old pre-auth downgrade. */
    private class RecordingFtps : FTPSClient(false) {
        val commands = mutableListOf<String>()
        var controlEncrypted = true
        var credentialsEncrypted = false
        var configuredTrust: TrustManager? = null
        override fun setTrustManager(trustManager: TrustManager) {
            configuredTrust = trustManager
            super.setTrustManager(trustManager)
        }
        override fun connect(hostname: String, port: Int) { commands += "CONNECT" }
        override fun getReplyCode() = 220
        override fun execPBSZ(pbsz: Long) { commands += "PBSZ" }
        override fun execPROT(prot: String) { commands += "PROT:$prot" }
        override fun execCCC(): Int { commands += "CCC"; controlEncrypted = false; return 200 }
        override fun login(username: String, password: String): Boolean {
            commands += "LOGIN"
            credentialsEncrypted = controlEncrypted
            return true
        }
        override fun setFileType(fileType: Int) = true
        override fun logout() = true
        override fun disconnect() = Unit
    }

    @Test fun `FTPS 保持控制通道加密并启用证书主机名验证`() = runBlocking {
        for (selfSigned in listOf(false, true)) {
            val client = RecordingFtps()
            val env = VfsEnv(AppDirs("unused", "unused"), PanelDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default))
            val config = ConnectionConfig(type = ConnectionType.FTPS, name = "secure", host = "nas.example", user = "u",
                options = mapOf(ConnectionConfig.OPT_TRUST_SELF_SIGNED to selfSigned.toString()))
            val vfs = FtpVfs(FtpConfig.from(config, "password"), env, clientFactory = { client })
            try {
                vfs.connect()
                assertTrue("credentials must stay inside TLS", client.credentialsEncrypted)
                assertFalse(client.commands.contains("CCC"))
                assertTrue("certificate hostname checking must remain enabled even for explicit self-signed trust", client.isEndpointCheckingEnabled)
                val trust = client.configuredTrust as X509TrustManager
                if (selfSigned) {
                    assertSame(com.u707t.panelfm.core.vfs.TlsTrust.trustManager, trust)
                } else {
                    assertNotSame(com.u707t.panelfm.core.vfs.TlsTrust.trustManager, trust)
                    assertTrue("system manager must reject an empty/untrusted chain",
                        runCatching { trust.checkServerTrusted(emptyArray(), "RSA") }.isFailure)
                }
                assertTrue(client.commands.contains("PROT:P"))
            } finally { vfs.close() }
        }
    }
}
