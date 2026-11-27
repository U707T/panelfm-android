package com.u707t.panelfm.core.vfs

/** Optional filesystem-aware identity hook for VFSes with access to canonical paths or inode IDs. */
interface VfsFileIdentityResolver {
    /** Returns null when the VFS cannot resolve both URIs to local entities. */
    fun sameFileIdentity(left: VfsUri, right: VfsUri): Boolean?

    /** Canonical path-like key used for conservative directory-scoped transfer locks. */
    fun fileIdentityPath(uri: VfsUri): String?
}

/**
 * URI-level identity for destructive file operations.
 * Local URIs may name the same absolute path through different exposed volumes.
 * This is lexical identity only; VFS implementations may add inode/canonical checks.
 */
fun sameFileIdentity(left: VfsUri, right: VfsUri): Boolean {
    val leftPath = normalizedIdentityPath(left) ?: return false
    val rightPath = normalizedIdentityPath(right) ?: return false
    if (left.scheme == "local" && right.scheme == "local") return leftPath == rightPath
    return left.sameMount(right) && leftPath == rightPath
}

private fun normalizedIdentityPath(uri: VfsUri): String? {
    if (uri.scheme != "local") return uri.path.trimEnd('/').ifEmpty { "/" }
    val absolute = when {
        uri.authority == "root" -> uri.path
        uri.authority == "emulated" -> "/storage/emulated/0" + uri.path.trimEnd('/')
        uri.authority.startsWith("vol:") -> "/" + uri.authority.removePrefix("vol:").replace('_', '/') + uri.path.trimEnd('/')
        else -> return "@${uri.authority}" + uri.path.trimEnd('/').ifEmpty { "/" }
    }
    val segments = ArrayDeque<String>()
    absolute.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}
