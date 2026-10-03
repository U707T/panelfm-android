package com.u707t.panelfm.core.vfs

/** 统一异常：UI 层据此给出「文案 + 可执行动作」。 */
sealed class VfsException(message: String, val code: Int? = null, cause: Throwable? = null) :
    Exception(message, cause) {

    class Auth(message: String, code: Int? = null) : VfsException(message, code)

    class NotFound(val uri: VfsUri) : VfsException("文件不存在：${uri.name.ifEmpty { uri.path }}")

    class Conflict(val uri: VfsUri) : VfsException("已存在同名项：${uri.name}")

    class Permission(message: String = "没有权限访问该位置") : VfsException(message)

    class Network(
        val kind: Kind,
        message: String,
        cause: Throwable? = null,
    ) : VfsException(message, cause = cause) {
        enum class Kind { TIMEOUT, DNS, REFUSED, UNREACHABLE, TLS, LOCAL_NETWORK_DENIED, DISCONNECTED }
    }

    class ProtocolError(message: String, cause: Throwable? = null) : VfsException(message, cause = cause)

    class Unsupported(message: String) : VfsException(message)

    class Cancelled(val uri: VfsUri? = null) : VfsException("已取消")

    class Quota(message: String) : VfsException(message)

    class Io(message: String, cause: Throwable? = null) : VfsException(message, cause = cause)

    class IllegalArgument(message: String) : VfsException(message)

    /** 面向用户的一句话文案（UI 直接展示） */
    val userMessage: String
        get() {
            val msg = message ?: ""
            return when (this) {
                is Auth -> "认证失败：请检查用户名 / 密码 / 密钥"
                is NotFound -> "文件不存在：${uri.name}"
                is Conflict -> "已存在同名项：${uri.name}"
                is Permission -> msg.ifEmpty { "没有权限" }
                is Network -> when (kind) {
                    Network.Kind.LOCAL_NETWORK_DENIED -> "系统未允许访问局域网（Android 17 需要单独授权）"
                    Network.Kind.TIMEOUT -> "连接超时：请检查网络或端口"
                    Network.Kind.DNS -> "域名解析失败"
                    Network.Kind.REFUSED -> "连接被拒绝：端口未开放？"
                    Network.Kind.UNREACHABLE -> "无法访问该地址"
                    Network.Kind.TLS -> "证书校验失败（可手动信任该证书）"
                    Network.Kind.DISCONNECTED -> "网络已断开"
                }
                is ProtocolError -> "协议错误：$msg"
                is Unsupported -> msg
                is Cancelled -> "已取消"
                is Quota -> msg
                is Io -> "读写失败：$msg"
                is IllegalArgument -> msg
            }
        }
}
