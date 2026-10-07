package com.u707t.panelfm.core.vfs.smb

import com.u707t.panelfm.core.model.ConnectionConfig

data class SmbConfig(
    val host: String,
    val port: Int,
    val domain: String,
    val user: String,
    val password: String?,
    /** 共享名（如 public / media）。为空时根目录给出「请填写共享名」引导；smbj 0.13 无共享枚举 API，不自动列出共享 */
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
