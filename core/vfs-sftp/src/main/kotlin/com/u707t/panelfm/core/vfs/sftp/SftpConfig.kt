package com.u707t.panelfm.core.vfs.sftp

import com.u707t.panelfm.core.model.ConnectionConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** SFTP 认证方式 */
enum class SftpAuth { PASSWORD, KEY }

/**
 * SFTP 连接参数。
 *
 * 口令存储在 :core:data 的 Keystore 口令箱里，SFTP 需要三个口令（登录密码 / 私钥口令 / 跳板机密码），
 * 因此约定：secret 为 JSON 字符串（[SftpSecrets]）；若为普通字符串则视为登录密码（兼容手填）。
 */
@Serializable
data class SftpSecrets(
    val password: String? = null,
    @SerialName("keyPassphrase") val keyPassphrase: String? = null,
    @SerialName("jumpPassword") val jumpPassword: String? = null,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String?): SftpSecrets {
            if (raw.isNullOrEmpty()) return SftpSecrets()
            return if (raw.trimStart().startsWith("{")) {
                runCatching { json.decodeFromString(serializer(), raw) }.getOrElse { SftpSecrets(password = raw) }
            } else {
                SftpSecrets(password = raw)
            }
        }
    }
}

data class JumpHostConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
)

data class SftpConfig(
    val host: String,
    val port: Int,
    val user: String,
    val auth: SftpAuth,
    val password: String?,
    /** OpenSSH / PEM 私钥文件路径（应用私有目录内） */
    val keyPath: String?,
    val keyPassphrase: String?,
    val jumpHost: JumpHostConfig?,
    val timeoutMs: Long,
) {
    val displayName: String get() = "$host:$port"

    companion object {
        const val OPT_AUTH = "sftpAuth"
        const val OPT_KEY_PATH = "sftpKeyPath"
        const val OPT_JUMP_HOST = "jumpHost"
        const val OPT_JUMP_PORT = "jumpPort"
        const val OPT_JUMP_USER = "jumpUser"

        fun from(config: ConnectionConfig, secret: String?, defaultTimeoutMs: Long = 20_000L): SftpConfig {
            val secrets = SftpSecrets.parse(secret)
            val jumpHost = config.option(OPT_JUMP_HOST)?.takeIf { it.isNotBlank() }?.let { host ->
                JumpHostConfig(
                    host = host,
                    port = config.option(OPT_JUMP_PORT)?.toIntOrNull() ?: 22,
                    user = config.option(OPT_JUMP_USER).orEmpty(),
                    password = secrets.jumpPassword,
                )
            }
            return SftpConfig(
                host = config.host,
                port = if (config.port > 0) config.port else 22,
                user = config.user,
                auth = runCatching {
                    SftpAuth.valueOf(config.option(OPT_AUTH) ?: SftpAuth.PASSWORD.name)
                }.getOrDefault(SftpAuth.PASSWORD),
                password = secrets.password,
                keyPath = config.option(OPT_KEY_PATH),
                keyPassphrase = secrets.keyPassphrase,
                jumpHost = jumpHost,
                timeoutMs = defaultTimeoutMs,
            )
        }
    }
}
