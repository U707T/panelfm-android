package com.u707t.panelfm.service

import com.u707t.panelfm.core.transfer.TaskState

/** Paused tasks and dialogs waiting for a user do not consume the dataSync time budget. */
internal fun needsTransferForeground(state: TaskState): Boolean =
    state == TaskState.Queued || state is TaskState.Running || state is TaskState.Cancelling
