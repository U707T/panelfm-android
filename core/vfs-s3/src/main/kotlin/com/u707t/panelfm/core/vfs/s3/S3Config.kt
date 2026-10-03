package com.u707t.panelfm.core.vfs.s3

import com.u707t.panelfm.core.model.ConnectionConfig

/**
 * S3 / 兼容对象存储配置（AWS S3、Cloudflare R2、腾讯 COS、阿里 OSS、MinIO、七牛…）。
 */
data class S3Config(
    val endpoint: String,          // http(s)://host[:port]
    val accessKey: String,
    val secretKey: String,
    val region: String,
    val pathStyle: Boolean,
    /** 可选：CDN / 自定义下载域名（下载与播放走它，不签名） */
    val downloadDomain: String?,
    val bucket: String?,
    val timeoutMs: Long,
) {
    companion object {
        const val OPT_REGION = "region"
        const val OPT_PATH_STYLE = "pathStyle"
        const val OPT_DOWNLOAD_DOMAIN = "downloadDomain"
        const val OPT_BUCKET = "bucket"

        fun from(config: ConnectionConfig, secret: String?): S3Config {
            // secret 约定为 `accessKey:secretKey`（第一位是 AK）
            val parts = (secret ?: "").split(':', limit = 2)
            val ak = config.user.ifBlank { parts.getOrNull(0).orEmpty() }
            val sk = if (parts.size == 2) parts[1] else parts.getOrElse(0) { "" }
            val secure = config.option("secure")?.toBoolean() ?: (config.port == 443)
            val host = if (config.port > 0 && config.port != 80 && config.port != 443) "${config.host}:${config.port}" else config.host
            return S3Config(
                endpoint = (if (secure) "https://" else "http://") + host,
                accessKey = ak,
                secretKey = sk,
                region = config.option(OPT_REGION) ?: "us-east-1",
                pathStyle = config.option(OPT_PATH_STYLE)?.toBoolean() ?: true,
                downloadDomain = config.option(OPT_DOWNLOAD_DOMAIN)?.takeIf { it.isNotBlank() },
                bucket = config.option(OPT_BUCKET)?.takeIf { it.isNotBlank() },
                timeoutMs = 30_000L,
            )
        }
    }
}
