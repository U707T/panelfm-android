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
        /** 当前文件已传字节（0 = 无信息，UI 隐藏「当前文件」进度条） */
        val itemDoneBytes: Long = 0,
        /** 当前文件总字节（0 = 无信息） */
        val itemTotalBytes: Long = 0,
    ) : TaskState

    data object Paused : TaskState

    data class WaitingConflict(val info: ConflictInfo) : TaskState

    /** 已请求取消、正在等传输循环退出（MT：「正在取消操作…」）；循环收尾后转 [Cancelled]。 */
    data object Cancelling : TaskState

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

/**
 * 任务状态分类 —— 任务条 / 任务页 / 前台服务 / 计数统一用这一组判定，避免各处各写一套 when
 * （旧实现里同一个「是否完成」的判断在 4 个文件里抄了 4 遍，新增状态必漏）。
 *
 * - [isActive]：还在进行、需要显示进度或等待用户操作（含排队 / 暂停 / 等待冲突 / 正在取消）；
 * - [isFinished]：已走到终点（不管成功与否）；
 * - [dismissesAutomatically]：无需用户处理，引擎在保留期后自动收走；**失败不算**（错误必须留痕）。
 */
val TaskState.isActive: Boolean
    get() = this is TaskState.Queued || this is TaskState.Running ||
        this is TaskState.Paused || this is TaskState.WaitingConflict || this is TaskState.Cancelling

val TaskState.isFinished: Boolean
    get() = this is TaskState.Done || this is TaskState.Cancelled || this is TaskState.Failed

val TaskState.dismissesAutomatically: Boolean
    get() = this is TaskState.Done || this is TaskState.Cancelled

/** 总体进度 0..1（未知总大小时为 0；「已完成」视为 1） */
fun TransferTaskSnapshot.overallProgress(): Float = when (val s = state) {
    is TaskState.Running -> if (s.totalBytes > 0) (s.doneBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else 0f
    is TaskState.Done -> 1f
    else -> 0f
}

/** 当前文件进度 0..1；返回 -1 表示无信息（UI 应隐藏这一层进度条） */
fun TransferTaskSnapshot.itemProgress(): Float = when (val s = state) {
    is TaskState.Running ->
        if (s.itemTotalBytes > 0) (s.itemDoneBytes.toFloat() / s.itemTotalBytes).coerceIn(0f, 1f) else -1f
    else -> -1f
}

/**
 * 任务展示排序：需要用户关注的在前，轻结束的在后 ——
 * 等待冲突 → 正在取消 → 进行中 → 已暂停 → 排队中 → 失败 → 已完成 → 已取消。
 * 同级别保持入队先后（稳定排序），保证列表不因状态变化乱跳。
 */
fun List<TransferTaskSnapshot>.sortedForDisplay(): List<TransferTaskSnapshot> =
    withIndex()
        .sortedWith(compareBy({ it.value.state.displayRank() }, { it.index }))
        .map { it.value }

private fun TaskState.displayRank(): Int = when (this) {
    is TaskState.WaitingConflict -> 0
    is TaskState.Cancelling -> 1
    is TaskState.Running -> 2
    is TaskState.Paused -> 3
    is TaskState.Queued -> 4
    is TaskState.Failed -> 5
    is TaskState.Done -> 6
    is TaskState.Cancelled -> 7
}

/**
 * 浏览器任务条要展示的任务：**全部进行中 + 失败**（失败必须留痕）。
 * 已完成 / 已取消不在任务条停留 —— 这是「任务条不消失」的直接修法；
 * 任务页仍会短暂展示它们的完成结果（引擎保留期过后自动收走）。
 */
fun List<TransferTaskSnapshot>.forBrowserBar(): List<TransferTaskSnapshot> =
    sortedForDisplay().filter { it.state.isActive || it.state is TaskState.Failed }

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
