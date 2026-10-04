package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.model.VerifyMode
import com.u707t.panelfm.core.vfs.VfsUri

/** 一次跨窗格操作请求：源（可多个）+ 目标目录（另一窗格当前目录，实时取值）。 */
data class TransferRequest(
    val sources: List<VfsUri>,
    val destDir: VfsUri,
    val op: TransferOp,
    val conflict: ConflictPolicy = ConflictPolicy.ASK,
    val verify: VerifyMode = VerifyMode.NONE,
    /** 无选中项时表示「整个目录」 */
    val wholeDirectory: Boolean = false,
    /** MT「保留文件时间」：复制/解压/下载完成后把源 mtime 写回目标（默认开，设置可关） */
    val preserveModifiedTime: Boolean = true,
)

enum class FastPath { NONE, SERVER_MOVE, SERVER_COPY }

data class PlanItem(
    val source: VfsUri,
    val dest: VfsUri,
    val isDirectory: Boolean,
    val size: Long,
    val depth: Int,
    /** 源文件修改时间（MT「保留文件时间」用；未知 -1） */
    val lastModified: Long = -1L,
)

data class OperationPlan(
    val items: List<PlanItem>,
    val fastPath: FastPath,
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
)

sealed interface TaskState {
    data object Queued : TaskState

    data class Running(
        val index: Int,
        val total: Int,
        val currentName: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val etaSeconds: Long,
        val resumed: Boolean = false,
    ) : TaskState

    data object Paused : TaskState

    data class WaitingConflict(val info: ConflictInfo) : TaskState

    data class Done(
        val ok: Int,
        val skipped: Int,
        val failed: Int,
        val bytes: Long,
        val elapsedMs: Long,
        val note: String? = null,
    ) : TaskState

    data class Failed(val message: String) : TaskState

    data object Cancelled : TaskState
}

data class FailedItem(val source: VfsUri, val reason: String)

/** UI 用的任务快照 */
data class TransferTaskSnapshot(
    val id: String,
    val title: String,
    val subtitle: String,
    val state: TaskState,
    val op: TransferOp,
)

fun TransferRequest.describe(): String {
    val opText = if (op == TransferOp.COPY) "复制" else "移动"
    val what = if (wholeDirectory) "当前目录" else "${sources.size} 项"
    return "$opText $what"
}
