package com.u707t.panelfm

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris

/** An explicit connection identity must never fall back to another account. */
internal fun connectionConfigForUri(configs: List<ConnectionConfig>, uri: VfsUri): ConnectionConfig? {
    val identities = uri.query?.split('&').orEmpty().filter { it.substringBefore('=') == VfsUris.PARAM_CONNECTION }
    if (identities.isNotEmpty()) {
        if (identities.size != 1) return null
        val id = VfsUris.connectionId(uri) ?: return null
        return configs.singleOrNull { it.id == id && it.scheme == uri.scheme }
    }
    val sameScheme = configs.filter { it.scheme == uri.scheme }
    val byAuthority = sameScheme.filter { "${it.host}:${it.port}" == uri.authority }
    if (byAuthority.isNotEmpty()) return byAuthority.singleOrNull()
    if (uri.scheme != "s3") return null
    val byBucket = sameScheme.filter { it.option("bucket") == uri.authority }
    return if (byBucket.isNotEmpty()) byBucket.singleOrNull() else sameScheme.singleOrNull()
}

internal fun connectionUri(config: ConnectionConfig, path: String = config.openPath): VfsUri =
    VfsUris.withConnection(VfsUri.of(config.scheme, "${config.host}:${config.port}", path), config.id)

internal fun heldConnectionKey(config: ConnectionConfig): String = "${config.id}|${config.sessionKey}"
