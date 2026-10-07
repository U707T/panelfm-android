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
    /** MT「编码」：控制连接编码（文件名）。默认 UTF-8；中文 FTP 服务器常需 GBK/GB18030 */
    val encoding: String,
    val timeoutMs: Int = 20_000,
) {
    companion object {
        fun from(
            config: ConnectionConfig,
            secret: String?,
            /** 连接未显式设置时的兜底默认值（来自全局设置） */
            trustSelfSignedDefault: Boolean = false,
        ): FtpConfig {
            val isFtps = config.type.name == "FTPS"
            val implicitTls = isFtps && config.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() == true
            return FtpConfig(
                host = config.host,
                // 隐式 TLS 的标准端口是 990（旧实现两个分支都写 21，隐式模式默认端口实际不可用）
                port = if (config.port > 0) config.port else if (implicitTls) 990 else 21,
                user = config.user.ifEmpty { "anonymous" },
                password = secret,
                basePath = config.basePath.ifBlank { "/" },
                tls = isFtps || config.option("tls")?.toBoolean() == true,
                implicitTls = implicitTls,
                passive = config.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() != false,
                trustSelfSigned = config.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: trustSelfSignedDefault,
                encoding = config.option(ConnectionConfig.OPT_ENCODING)?.takeIf { it.isNotBlank() } ?: "UTF-8",
            )
        }
    }
}
