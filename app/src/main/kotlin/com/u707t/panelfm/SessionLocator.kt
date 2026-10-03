package com.u707t.panelfm

import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUris
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem

/** 由 VfsUri 找回所属 VFS 会话：本地直取；网络按 ?c= 连接号或 scheme://authority 匹配。 */
class SessionLocator(private val container: AppContainer) : VfsLocator {

    override fun find(uri: VfsUri): VirtualFileSystem? {
        if (uri.scheme == "local") return container.localVfs
        if (uri.scheme == "archive") {
            // archive://zip/<encoded host>!/inner → 找回已挂载的压缩包 VFS
            val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(uri.path) ?: return null
            val host = VfsUri.decodeHost(encoded)
            return container.archiveOf(host)
        }

        container.connectionOf(VfsUris.connectionId(uri))?.let { config ->
            container.registry.peek(config)?.let { return it }
        }
        container.connectionByAuthority(uri.scheme, uri.authority)?.let { config ->
            container.registry.peek(config)?.let { return it }
        }
        return container.mountedOf("${uri.scheme}://${uri.authority}")
    }
}
