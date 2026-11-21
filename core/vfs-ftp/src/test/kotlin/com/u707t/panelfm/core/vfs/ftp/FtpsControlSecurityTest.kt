package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.vfs.TlsTrust
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.X509TrustManager

class FtpsControlSecurityTest {
    private val strictSystemTrust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw java.security.cert.CertificateException("empty chain")
        }
        override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
    }

    @Test fun `自签开关只切换 trust manager 且始终启用主机名校验`() {
        assertTrue(FTPS_ENDPOINT_CHECKING_ENABLED)
        assertSame(TlsTrust.trustManager, ftpsTrustManager(true) { strictSystemTrust })
        val system = ftpsTrustManager(false) { strictSystemTrust }
        assertFalse(system === TlsTrust.trustManager)
        assertTrue(runCatching { system.checkServerTrusted(emptyArray(), "RSA") }.isFailure)
    }
}
