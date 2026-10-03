package com.u707t.panelfm.core.vfs

/** 会话状态（UI 顶部状态点、任务失败原因都用它）。 */
sealed interface VfsState {
    data object Idle : VfsState
    data object Connecting : VfsState
    data object Ready : VfsState
    data class Error(val message: String) : VfsState
}
