package com.u707t.panelfm.core.vfs

/** 一个文件/目录的快照元数据（UI 不缓存它，每次列表给定快照）。 */
data class FileMetadata(
    val uri: VfsUri,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    val size: Long = -1L,
    val lastModified: Long = -1L,
    val mimeType: String? = null,
    val permissions: Int? = null,
    val owner: String? = null,
    val group: String? = null,
    val etag: String? = null,
) {
    val isHidden: Boolean get() = name.startsWith(".")

    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val displaySize: String
        get() = when {
            isDirectory -> ""
            size < 0 -> ""
            else -> com.u707t.panelfm.core.common.Fmt.size(size)
        }

    companion object {
        fun dir(uri: VfsUri, name: String, lastModified: Long = -1L, permissions: Int? = null): FileMetadata =
            FileMetadata(uri = uri, name = name, isDirectory = true, lastModified = lastModified, permissions = permissions)
    }
}
