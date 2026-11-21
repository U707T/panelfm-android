package com.u707t.panelfm.ui.editor

/** Main-thread document revision and single-flight disk-operation guard. */
internal class EditorDocumentOperations {
    private var revision = 0L
    var inProgress = false
        private set

    fun changed() { revision++ }

    fun begin(): Long? {
        if (inProgress) return null
        inProgress = true
        return revision
    }

    fun isCurrent(snapshot: Long): Boolean = revision == snapshot

    fun end() { inProgress = false }
}
