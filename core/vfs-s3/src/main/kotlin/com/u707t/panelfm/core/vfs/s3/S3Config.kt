package com.u707t.panelfm.core.vfs.s3

import com.u707t.panelfm.core.model.ConnectionConfig

/**
 * S3 / 兼容对象存储配置（AWS S3、Cloudflare R2、腾讯 COS、阿里 OSS、MinIO、七牛…）。
 */
data class S3Config(
    val endpoint: String,          // http(s)://host[:port]
    /** UI 打开连接时使用的 URI authority（`host:port`），用于把「连接根」与「Bucket 名」区分开 */
    val uriAuthority: String,
    val accessKey: String,
    val secretKey: String,
    val region: String,
    val pathStyle: Boolean,
    /** 可选：CDN / 自定义下载域名（下载与播放走它，不签名） */
    val downloadDomain: String?,
    val bucket: String?,
    val timeoutMs: Long,
    /** 是否使用 HTTPS（endpoint 前缀已含，这里保留给 TLS 策略判断） */
    val secure: Boolean = endpoint.startsWith("https://"),
    /** 「信任自签证书」（自建 MinIO 等）；连接级选项优先，其次全局默认 */
    val trustSelfSigned: Boolean = false,
) {
    companion object {
        const val OPT_REGION = "region"
        const val OPT_PATH_STYLE = "pathStyle"
        const val OPT_DOWNLOAD_DOMAIN = "downloadDomain"
        const val OPT_BUCKET = "bucket"

        fun from(
            config: ConnectionConfig,
            secret: String?,
            /** 连接未显式设置时的兜底默认值（来自全局设置） */
            trustSelfSignedDefault: Boolean = false,
        ): S3Config {
            // secret 约定为 `accessKey:secretKey`（第一位是 AK）
            val parts = (secret ?: "").split(':', limit = 2)
            val ak = config.user.ifBlank { parts.getOrNull(0).orEmpty() }
            val sk = if (parts.size == 2) parts[1] else parts.getOrElse(0) { "" }
            val secure = config.option("secure")?.toBoolean() ?: (config.port == 443)
            val host = if (config.port > 0 && config.port != 80 && config.port != 443) "${config.host}:${config.port}" else config.host
            return S3Config(
                endpoint = (if (secure) "https://" else "http://") + host,
                uriAuthority = "${config.host}:${config.port}",
                accessKey = ak,
                secretKey = sk,
                region = config.option(OPT_REGION) ?: "us-east-1",
                pathStyle = config.option(OPT_PATH_STYLE)?.toBoolean() ?: true,
                downloadDomain = config.option(OPT_DOWNLOAD_DOMAIN)?.takeIf { it.isNotBlank() },
                bucket = config.option(OPT_BUCKET)?.takeIf { it.isNotBlank() },
                timeoutMs = 30_000L,
                secure = secure,
                trustSelfSigned = config.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean()
                    ?: trustSelfSignedDefault,
            )
        }
    }
}
