package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A bookmark's legacy ID is a fallback only when the URI has no identity of its own. */
internal fun storedPathLookupUri(uri: VfsUri, savedId: Long?): VfsUri {
    if (savedId == null || uri.scheme == "local" || uri.scheme == "archive") return uri
    return if (uri.connectionIdentityCount() != 0) uri else VfsUris.withConnection(uri, savedId)
}

internal fun VfsUri.connectionIdentityCount(): Int =
    query?.split('&').orEmpty().count { it.substringBefore('=') == VfsUris.PARAM_CONNECTION }


/** The shipped URI bug emitted this exact literal, never a usable endpoint or S3 bucket. */
internal fun storedPathWithConnection(uri: VfsUri, config: ConnectionConfig): VfsUri {
    val canonical = if (uri.authority == "\${config.host}:\${config.port}") {
        uri.copy(authority = "${config.host}:${config.port}")
    } else uri
    return VfsUris.withConnection(canonical, config.id)
}

internal fun <T> conflictWithToken(tasks: List<T>, token: Long, state: (T) -> TaskState): T? =
    tasks.firstOrNull { (state(it) as? TaskState.WaitingConflict)?.info?.token == token }

/** A move consumes only the sources of a successful task, and only from the clipboard it came from. */
internal class BrowserClipboard {
    data class Snapshot(val revision: Long, val sources: List<VfsUri>)

    private val mutableSources = MutableStateFlow<List<VfsUri>>(emptyList())
    val sources: StateFlow<List<VfsUri>> = mutableSources
    private var revision = 0L
    private val pendingMoves = mutableSetOf<Long>()

    @Synchronized
    fun set(sources: List<VfsUri>) {
        revision++
        mutableSources.value = sources.toList()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(revision, mutableSources.value)

    @Synchronized
    fun reserveMove(expectedRevision: Long): Boolean = revision == expectedRevision && pendingMoves.add(expectedRevision)

    @Synchronized
    fun releaseMove(expectedRevision: Long) { pendingMoves.remove(expectedRevision) }

    @Synchronized
    fun consumeMoved(expectedRevision: Long, moved: List<VfsUri>, state: TaskState) {
        if (revision != expectedRevision || state !is TaskState.Done ||
            state.ok == 0 || state.failed != 0 || state.failureTotal != 0 || state.skipped != 0
        ) return
        mutableSources.value = mutableSources.value - moved.toSet()
    }
}
