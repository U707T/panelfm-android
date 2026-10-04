package com.u707t.panelfm.core.model

/** 协议类型：全部免费开放，无 VIP 区分。 */
enum class ConnectionType(
    val scheme: String,
    val label: String,
    val defaultPort: Int,
    val needsHost: Boolean = true,
    val needsAuth: Boolean = true,
) {
    LOCAL("local", "本地存储", 0, needsHost = false, needsAuth = false),
    WEBDAV("dav", "WebDAV", 80),
    FTP("ftp", "FTP", 21),
    FTPS("ftps", "FTPS", 21),
    SFTP("sftp", "SFTP", 22),
    SMB("smb", "SMB", 445),
    S3("s3", "对象存储(S3)", 443),
    ;

    companion object {
        fun ofScheme(scheme: String): ConnectionType? = entries.firstOrNull { it.scheme == scheme }
    }
}

/** 连接配置：口令不在这里，只存 secretRef（Keystore 加密后的引用）。 */
data class ConnectionConfig(
    val id: Long = 0L,
    val type: ConnectionType,
    val name: String,
    val host: String = "",
    val port: Int = type.defaultPort,
    val user: String = "",
    val secretRef: String? = null,
    val basePath: String = "/",
    val group: String = "",
    val options: Map<String, String> = emptyMap(),
    val sortOrder: Int = 0,
    val lastUsedAt: Long = 0L,
) {
    val scheme: String get() = type.scheme

    /** 同一配置 + 同一口令 = 同一个 VFS 实例（会话复用） */
    val sessionKey: String
        get() = listOf(type.scheme, host, port.toString(), user, basePath, options.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }).joinToString("|")

    fun option(key: String): String? = options[key]

    /**
     * 打开连接时进入的初始路径（修复 WebDAV「/dav/dav」双前缀 bug）：
     *  - WebDAV：basePath 是服务挂载点，URI 以**虚拟根 "/"** 为界（请求时才拼回 basePath），
     *    可选的「初始路径」进入挂载点下的子目录；
     *  - 其余协议：basePath 即起始绝对路径，「初始路径」相对它追加。
     */
    val openPath: String
        get() {
            val initial = option(OPT_INITIAL_PATH).orEmpty().trim().trim('/')
            return if (type == ConnectionType.WEBDAV) {
                if (initial.isEmpty()) "/" else "/$initial"
            } else {
                val base = basePath.ifBlank { "/" }.trimEnd('/')
                if (initial.isEmpty()) base.ifEmpty { "/" } else "$base/$initial"
            }
        }

    companion object {
        const val OPT_TRUST_SELF_SIGNED = "trustSelfSigned"
        const val OPT_USER_AGENT = "userAgent"
        const val OPT_IMPLICIT_TLS = "implicitTls"
        const val OPT_PASSIVE = "passiveMode"
        const val OPT_REGION = "region"
        const val OPT_PATH_STYLE = "pathStyle"
        const val OPT_DOWNLOAD_DOMAIN = "downloadDomain"
        const val OPT_JUMP_HOST = "jumpHost"

        /** 打开连接后进入的子目录（相对根路径 / WebDAV 挂载点） */
        const val OPT_INITIAL_PATH = "initialPath"

        /** 不在侧边栏（≡ 抽屉）显示该地址 */
        const val OPT_HIDDEN_IN_DRAWER = "hiddenInDrawer"

        /** "false" = 该连接不加载缩略图（缺省加载） */
        const val OPT_LOAD_THUMBS = "loadThumbs"
        /** MT 的「编码」：FTP/SFTP 的文件名编码（默认 UTF-8，中文服务器常用 GBK/GB18030） */
        const val OPT_ENCODING = "encoding"
    }
}
