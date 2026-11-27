package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.ensureActive

/** 所有创建格式统一拒绝链接；不把用户选取边界外的内容悄悄打进归档。 */
internal class ArchiveTraversal {
    private val directories = HashSet<VfsUri>()
    private var entries = 0
    suspend fun check(meta: com.u707t.panelfm.core.vfs.FileMetadata, depth: Int) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (meta.isSymlink) throw VfsException.Unsupported("暂不支持压缩符号链接：${meta.name}")
        if (depth > 64) throw VfsException.ProtocolError("压缩目录层级过深（> 64）")
        if (++entries > 200_000) throw VfsException.ProtocolError("压缩条目超过 200000，请分批操作")
        if (meta.isDirectory && !directories.add(meta.uri)) {
            throw VfsException.ProtocolError("压缩目录重复或形成环：${meta.name}")
        }
    }
}

