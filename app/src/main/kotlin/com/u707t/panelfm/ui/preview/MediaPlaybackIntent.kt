package com.u707t.panelfm.ui.preview

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread intent gate shared by initial load and playlist switches. */
internal class MediaPlaybackIntent {
    private var latest = 0L
    fun next(): Long = ++latest
    fun isCurrent(token: Long): Boolean = token == latest

    suspend fun prepare(token: Long, preflight: suspend () -> String?, applyToPlayer: () -> String?): String? {
        if (!isCurrent(token)) return null
        val error = preflight()
        currentCoroutineContext().ensureActive()
        if (!isCurrent(token)) return null
        return error ?: applyToPlayer()
    }
}
