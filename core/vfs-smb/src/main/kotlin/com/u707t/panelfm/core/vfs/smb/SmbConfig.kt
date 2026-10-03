package com.u707t.panelfm.core.vfs.smb

import com.u707t.panelfm.core.model.ConnectionConfig

data class SmbConfig(
    val host: String,
    val port: Int,
    val domain: String,
    val user: String,
    val password: String?,
    /** 可选的默认共享（空 = 列出所有共享） */
    val defaultShare: String?,
    val timeoutMs: Long,
) {
    companion object {
        const val OPT_DOMAIN = "smbDomain"
        const val OPT_SHARE = "smbShare"

        fun from(config: ConnectionConfig, secret: String?, defaultTimeoutMs: Long = 20_000L) = SmbConfig(
            host = config.host,
            port = if (config.port > 0) config.port else 445,
            domain = config.option(OPT_DOMAIN).orEmpty(),
            user = config.user,
            password = secret,
            defaultShare = config.option(OPT_SHARE)?.takeIf { it.isNotBlank() },
            timeoutMs = defaultTimeoutMs,
        )
    }
}
