package com.u707t.panelfm.core.vfs.ftp

import com.u707t.panelfm.core.model.ConnectionConfig

data class FtpConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val basePath: String,
    val tls: Boolean,
    val implicitTls: Boolean,
    val passive: Boolean,
    val trustSelfSigned: Boolean,
    val timeoutMs: Int = 20_000,
) {
    companion object {
        fun from(config: ConnectionConfig, secret: String?): FtpConfig = FtpConfig(
            host = config.host,
            port = if (config.port > 0) config.port else if (config.type.name == "FTPS") 21 else 21,
            user = config.user.ifEmpty { "anonymous" },
            password = secret,
            basePath = config.basePath.ifBlank { "/" },
            tls = config.type.name == "FTPS" || config.option("tls")?.toBoolean() == true,
            implicitTls = config.type.name == "FTPS" && config.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() == true,
            passive = config.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() != false,
            trustSelfSigned = config.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: true,
        )
    }
}
