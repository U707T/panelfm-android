package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionType

/**
 * 连接表单的纯逻辑（可单测）：协议切换时字段怎么迁移、SMB 的两处共享名怎么校验。
 *
 * 抽出来的原因：`ConnectionEditScreen` 是一个 700+ 行的单 Composable，这些规则原本埋在
 * UI 事件回调里，只能靠手点验证。规则本身是纯函数，放在这里就能用回归锁住。
 */

/**
 * 协议切换后的「根路径」。
 *
 * - 切到 S3：强制 `/`。S3 不显示根路径输入框，残留的 `/dav` 会变成对象 key 前缀，
 *   列表显示为空且用户无处修改。
 * - 从 WebDAV 切走，且当前值是 URL 自动带出来的（[davDerivedPath]）：重置 `/`。
 *   用户手改过的根路径不动。
 */
internal fun basePathOnTypeChange(
    from: ConnectionType,
    to: ConnectionType,
    current: String,
    davDerivedPath: String?,
): String = when {
    to == ConnectionType.S3 -> "/"
    from == ConnectionType.WEBDAV && davDerivedPath != null && current == davDerivedPath -> "/"
    else -> current
}

/** 落库的根路径：S3 永不带前缀；其余协议空值收敛为 `/`。 */
internal fun configBasePath(type: ConnectionType, basePath: String): String =
    if (type == ConnectionType.S3) "/" else basePath.trim().ifBlank { "/" }

/**
 * SMB 的「共享名」与「起始路径」必须一致：起始路径要么是共享根 `/`，要么以 `/<共享名>` 开头。
 * 返回 null = 没问题；否则是给用户看的错误文案。
 *
 * 底层 `SmbVfs.splitSmbPath` 以路径首段为共享名——两处填不一致时不会报错，只会连到错误的共享。
 */
internal fun smbPathProblem(basePath: String, share: String): String? {
    val s = share.trim().trim('/')
    if (s.isEmpty()) return null
    val p = "/" + basePath.trim().trim('/')
    val path = if (p == "/") "/" else p
    if (path == "/") return null // 共享根：由共享名决定
    val prefix = "/${s.lowercase()}"
    val lower = path.lowercase()
    return if (lower == prefix || lower.startsWith("$prefix/")) null
    else "起始路径必须以 /$s 开头（共享名），或留空表示共享根"
}
