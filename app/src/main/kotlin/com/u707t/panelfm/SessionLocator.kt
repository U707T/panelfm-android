package com.u707t.panelfm

import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUris
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem

/** 由 VfsUri 找回所属 VFS 会话：本地直取；网络按 ?c= 连接号或 scheme://authority 匹配。 */
class SessionLocator(private val container: AppContainer) : VfsLocator {

    override fun find(uri: VfsUri): VirtualFileSystem? {
        if (uri.scheme == "local") return container.localVfs

        container.connectionOf(VfsUris.connectionId(uri))?.let { config ->
            container.registry.peek(config)?.let { return it }
        }
        container.connectionByAuthority(uri.scheme, uri.authority)?.let { config ->
            container.registry.peek(config)?.let { return it }
        }
        return container.mountedOf("${uri.scheme}://${uri.authority}")
    }
}
