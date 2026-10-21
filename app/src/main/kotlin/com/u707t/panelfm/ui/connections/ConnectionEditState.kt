package com.u707t.panelfm.ui.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.s3.S3Config
import com.u707t.panelfm.core.vfs.sftp.SftpAuth
import com.u707t.panelfm.core.vfs.sftp.SftpConfig
import com.u707t.panelfm.core.vfs.sftp.SftpSecrets
import com.u707t.panelfm.core.vfs.smb.SmbConfig

/**
 * 连接编辑表单的全部可编辑状态（从 `ConnectionEditScreen` 的 700+ 行单 Composable 里抽出来）。
 *
 * 为什么要有这个类：
 *  - 之前 30 多个 `var x by remember` 与 28 处 `type == …` 分支混在同一个函数里，
 *    「字段属于哪个协议」「切协议时哪些值要迁移」只能靠通读整段代码回答；
 *  - 表单规则（根路径归属、SMB 共享名一致性、配置/口令构造）是**纯数据变换**，
 *    放进这里就能按协议分文件渲染，也能直接单测。
 *
 * 只放表单状态与构造逻辑；busy / status / 私钥选择器 / 保存测试流程仍留在界面层。
 */
internal class ConnectionEditState(
    val existing: ConnectionConfig?,
    rawSecret: String?,
    prefillHost: String?,
    prefillPort: Int?,
    initialType: ConnectionType?,
) {

    private val storedSecrets = SftpSecrets.parse(rawSecret)
    private val existingDav = existing?.takeIf { it.type == ConnectionType.WEBDAV }

    // ---------------- 基本字段
    var type by mutableStateOf(existing?.type ?: initialType ?: ConnectionType.SFTP)
    var name by mutableStateOf(existing?.name ?: "")
    var group by mutableStateOf(existing?.group ?: "")
    var host by mutableStateOf(existing?.host ?: prefillHost ?: "")
    var port by mutableStateOf((existing?.port ?: prefillPort ?: type.defaultPort).toString())
    var user by mutableStateOf(existing?.user ?: "")
    var password by mutableStateOf(ConnectionSecrets.passwordForEdit(existing?.type, rawSecret))
    var showPassword by mutableStateOf(false)
    var showKeyPassphrase by mutableStateOf(false)
    var showJumpPassword by mutableStateOf(false)

    /** 根路径（协议专属；切协议时按 [basePathOnTypeChange] 迁移） */
    var basePath by mutableStateOf(existing?.basePath ?: "/")

    /** WebDAV URL 自动带出的根路径：只有「没被手改过」才允许在切走时重置 */
    var davDerivedBasePath by mutableStateOf(
        existing?.takeIf { it.type == ConnectionType.WEBDAV }?.basePath
    )

    // 与 DavConfig / S3Config 的同款 fallback 对齐（键缺失按端口推：443 = HTTPS）
    var secure by mutableStateOf(existing?.option("secure")?.toBoolean() ?: (port.toIntOrNull() == 443))
    var trustSelfSigned by mutableStateOf(
        existing?.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: false
    )
    var implicitTls by mutableStateOf(
        existing?.option(ConnectionConfig.OPT_IMPLICIT_TLS)?.toBoolean() ?: false
    )
    var passive by mutableStateOf(existing?.option(ConnectionConfig.OPT_PASSIVE)?.toBoolean() ?: true)
    var encoding by mutableStateOf(
        existing?.option(ConnectionConfig.OPT_ENCODING).orEmpty().ifBlank { "UTF-8" }
    )
    var userAgent by mutableStateOf(existing?.option(ConnectionConfig.OPT_USER_AGENT).orEmpty())
    var initialPath by mutableStateOf(existing?.option(ConnectionConfig.OPT_INITIAL_PATH).orEmpty())
    var hiddenInDrawer by mutableStateOf(
        existing?.option(ConnectionConfig.OPT_HIDDEN_IN_DRAWER) == "true"
    )
    var loadThumbs by mutableStateOf(existing?.option(ConnectionConfig.OPT_LOAD_THUMBS) != "false")

    // ---------------- WebDAV URL
    var url by mutableStateOf(
        when {
            existingDav != null ->
                WebDavUrl.build(existingDav.host, existingDav.port, secure, existingDav.basePath)
            initialType == ConnectionType.WEBDAV && !prefillHost.isNullOrBlank() ->
                WebDavUrl.build(prefillHost, prefillPort ?: 80, false, "/")
            else -> ""
        }
    )

    // ---------------- SFTP
    var sftpAuth by mutableStateOf(
        runCatching { SftpAuth.valueOf(existing?.option(SftpConfig.OPT_AUTH) ?: SftpAuth.PASSWORD.name) }
            .getOrDefault(SftpAuth.PASSWORD)
    )
    var keyPath by mutableStateOf(existing?.option(SftpConfig.OPT_KEY_PATH) ?: "")
    var keyPassphrase by mutableStateOf(storedSecrets.keyPassphrase ?: "")
    var jumpEnabled by mutableStateOf(!existing?.option(SftpConfig.OPT_JUMP_HOST).isNullOrBlank())
    var jumpHost by mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_HOST).orEmpty())
    var jumpPort by mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_PORT) ?: "22")
    var jumpUser by mutableStateOf(existing?.option(SftpConfig.OPT_JUMP_USER).orEmpty())
    var jumpPassword by mutableStateOf(storedSecrets.jumpPassword ?: "")

    // ---------------- SMB
    var smbDomain by mutableStateOf(existing?.option(SmbConfig.OPT_DOMAIN).orEmpty())
    var smbShare by mutableStateOf(existing?.option(SmbConfig.OPT_SHARE).orEmpty())

    // ---------------- S3
    var s3Region by mutableStateOf(existing?.option(S3Config.OPT_REGION) ?: "us-east-1")
    var s3PathStyle by mutableStateOf(
        existing?.option(S3Config.OPT_PATH_STYLE)?.toBoolean() ?: true
    )
    var s3Domain by mutableStateOf(existing?.option(S3Config.OPT_DOWNLOAD_DOMAIN).orEmpty())
    var s3Bucket by mutableStateOf(existing?.option(S3Config.OPT_BUCKET).orEmpty())

    /** SMB 起始路径与共享名冲突时的错误文案（null = 没问题）。 */
    val smbProblem: String?
        get() = if (type == ConnectionType.SMB) smbPathProblem(basePath, smbShare) else null

    fun ready(busy: Boolean): Boolean =
        (if (type == ConnectionType.WEBDAV) WebDavUrl.parse(url) != null else host.isNotBlank()) &&
            smbProblem == null && !busy

    /** 切到 WebDAV 时，用当前字段回填 URL（LAN 扫描预填场景）。 */
    fun syncUrlFromFields() {
        if (type == ConnectionType.WEBDAV && url.isBlank() && host.isNotBlank()) {
            url = WebDavUrl.build(host.trim(), port.toIntOrNull() ?: 80, secure, basePath)
        }
    }

    /**
     * 协议切换：根路径跟着协议走。
     *  - 切到 S3 强制 `/`（根路径输入框在 S3 下是隐藏的，残留值会变成对象前缀）；
     *  - 从 WebDAV 切走且路径是 URL 带出来的 → 重置 `/`；手改过的不动。
     */
    fun selectType(target: ConnectionType) {
        if (target == type) return
        val from = type
        type = target
        basePath = basePathOnTypeChange(from, target, basePath, davDerivedBasePath)
        if (from == ConnectionType.WEBDAV || target == ConnectionType.S3) davDerivedBasePath = null
        if (target == ConnectionType.WEBDAV) {
            syncUrlFromFields()
            davDerivedBasePath = basePath
        }
    }

    /** 保存 / 测试前把 WebDAV URL 解析回各字段；false = URL 格式不正确。 */
    fun applyUrlIfWebDav(): Boolean {
        if (type != ConnectionType.WEBDAV) return true
        val parsed = WebDavUrl.parse(url) ?: return false
        host = parsed.host
        port = parsed.port.toString()
        secure = parsed.secure
        basePath = parsed.path
        return true
    }

    fun buildOptions(): Map<String, String> = buildMap {
        // `secure` 键只有 WebDAV / S3 消费，**无条件写布尔值**：只在 true 时写会让 443 端口的
        // 「关闭 HTTPS / 显式 http://」被 Config 的 port == 443 fallback 覆盖
        if (type == ConnectionType.WEBDAV || type == ConnectionType.S3) put("secure", secure.toString())
        if (trustSelfSigned) put(ConnectionConfig.OPT_TRUST_SELF_SIGNED, "true")
        if (implicitTls) put(ConnectionConfig.OPT_IMPLICIT_TLS, "true")
        if (!passive) put(ConnectionConfig.OPT_PASSIVE, "false")
        if (userAgent.isNotBlank()) put(ConnectionConfig.OPT_USER_AGENT, userAgent.trim())
        if (initialPath.isNotBlank()) put(ConnectionConfig.OPT_INITIAL_PATH, initialPath.trim())
        if (hiddenInDrawer) put(ConnectionConfig.OPT_HIDDEN_IN_DRAWER, "true")
        if (!loadThumbs) put(ConnectionConfig.OPT_LOAD_THUMBS, "false")
        // SFTP 固定 UTF-8（协议层），没有编码消费方——写侧只对 FTP / FTPS 生效，与 UI 显示范围一致
        if ((type == ConnectionType.FTP || type == ConnectionType.FTPS) &&
            encoding.isNotBlank() && encoding != "UTF-8"
        ) {
            put(ConnectionConfig.OPT_ENCODING, encoding.trim())
        }
        if (type == ConnectionType.SMB) {
            if (smbDomain.isNotBlank()) put(SmbConfig.OPT_DOMAIN, smbDomain.trim())
            if (smbShare.isNotBlank()) put(SmbConfig.OPT_SHARE, smbShare.trim())
        }
        if (type == ConnectionType.S3) {
            put(S3Config.OPT_REGION, s3Region.trim().ifBlank { "us-east-1" })
            put(S3Config.OPT_PATH_STYLE, s3PathStyle.toString())
            if (s3Domain.isNotBlank()) put(S3Config.OPT_DOWNLOAD_DOMAIN, s3Domain.trim())
            if (s3Bucket.isNotBlank()) put(S3Config.OPT_BUCKET, s3Bucket.trim())
        }
        if (type == ConnectionType.SFTP) {
            put(SftpConfig.OPT_AUTH, sftpAuth.name)
            if (sftpAuth == SftpAuth.KEY && keyPath.isNotBlank()) put(SftpConfig.OPT_KEY_PATH, keyPath)
            if (jumpEnabled && jumpHost.isNotBlank()) {
                put(SftpConfig.OPT_JUMP_HOST, jumpHost.trim())
                put(SftpConfig.OPT_JUMP_PORT, jumpPort.trim().ifBlank { "22" })
                put(SftpConfig.OPT_JUMP_USER, jumpUser.trim())
            }
        }
    }

    fun buildSecret(): String? = ConnectionSecrets.build(type, password, keyPassphrase, jumpPassword)

    fun buildConfig(id: Long = existing?.id ?: 0L): ConnectionConfig = ConnectionConfig(
        id = id,
        type = type,
        name = name.ifBlank { host },
        host = host.trim(),
        port = port.toIntOrNull() ?: type.defaultPort,
        user = user.trim(),
        basePath = configBasePath(type, basePath),
        group = group.trim(),
        options = buildOptions(),
    )
}
