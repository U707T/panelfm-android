package com.u707t.panelfm.core.vfs

import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 「信任自签证书」的**唯一**实现。
 *
 * 为什么收敛到一处：原先 WebDAV（`DavHttp.InsecureTls`）与 FTP/FTPS（`FtpVfs.InsecureTrustManager`）
 * 各自维护一份完全相同的 trust-all 实现，而 S3 / SMB 干脆没有 ——
 * 结果是同一个「信任自签证书」开关在不同协议上行为不一致（有的生效、有的静默无效）。
 *
 * ⚠️ 这是**全局放开校验**的实现：只应在用户对该连接显式开启「信任自签证书」时使用，
 * 绝不能成为默认值。要收紧成「按连接固定指纹」需要另一套设计（见审计报告 §8）。
 */
object TlsTrust {

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        }.socketFactory
    }

    /** 该连接是否开启了「信任自签证书」 */
    fun isEnabled(config: com.u707t.panelfm.core.model.ConnectionConfig): Boolean =
        config.option(com.u707t.panelfm.core.model.ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: false
}
