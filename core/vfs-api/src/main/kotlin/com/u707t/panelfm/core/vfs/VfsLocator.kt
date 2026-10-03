package com.u707t.panelfm.core.vfs

/** 由一个 VfsUri 找回它所属的 VFS 实例（多窗格 / 多协议会话共享的关键）。 */
interface VfsLocator {
    fun find(uri: VfsUri): VirtualFileSystem?
}

/** URI 里的连接标识：`?c=<connectionId>`，用于在多连接同主机时精确定位会话。 */
object VfsUris {

    const val PARAM_CONNECTION = "c"

    fun withConnection(uri: VfsUri, connectionId: Long): VfsUri =
        uri.copy(query = upsertParam(uri.query, PARAM_CONNECTION, connectionId.toString()))

    fun connectionId(uri: VfsUri): Long? {
        val q = uri.query ?: return null
        return q.split('&')
            .firstOrNull { it.startsWith("$PARAM_CONNECTION=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
    }

    /** 去掉连接参数后的纯路径（协议实现用） */
    fun stripped(uri: VfsUri): VfsUri = VfsUri(uri.scheme, uri.authority, uri.path, null)

    fun key(scheme: String, authority: String): String = "$scheme://$authority"

    private fun upsertParam(query: String?, key: String, value: String): String {
        val parts = query?.split('&')?.filter { it.isNotBlank() && !it.startsWith("$key=") } ?: emptyList()
        return (parts + "$key=$value").joinToString("&")
    }
}
