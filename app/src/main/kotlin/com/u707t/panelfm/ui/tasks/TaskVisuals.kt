package com.u707t.panelfm.ui.tasks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
import kotlin.math.roundToInt

/**
 * 传输任务的状态视觉（v2.3.0）。
 *
 * 任务页与内联任务条以前各写一套 `when (state)`：任务页有徽标配色，任务条只有文字，
 * 同一状态两处颜色/文案不一致。统一收到这里，两处都从这里取。
 */

/** 状态主色：进度条 / 状态点 / 百分比共用 */
@Composable
fun TaskState.accentColor(): Color = when (this) {
    is TaskState.Running -> MaterialTheme.colorScheme.primary
    is TaskState.Paused, is TaskState.Cancelling -> MaterialTheme.colorScheme.tertiary
    is TaskState.WaitingConflict, is TaskState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 状态短标签（任务页徽标 / 任务条行尾） */
fun TaskState.statusLabel(): String = when (this) {
    TaskState.Queued -> "排队中"
    is TaskState.Running -> "进行中"
    is TaskState.Paused -> "已暂停"
    is TaskState.WaitingConflict -> "等待冲突"
    is TaskState.Cancelling -> "正在取消…"
    is TaskState.Done -> "已完成"
    is TaskState.Failed -> "失败"
    is TaskState.Cancelled -> "已取消"
}

/**
 * 行尾的「一眼信息」：进行中 / 暂停 / 取消中显示百分比，其余显示状态词。
 *
 * - 总大小还没统计出来（`totalBytes == 0`）→「统计中…」，不显示一个假的 0%；
 * - 暂停 / 取消中沿用保留的最后一帧进度（[overallProgress]），不跳回 0%。
 */
fun TransferTaskSnapshot.trailingLabel(): String = when (val s = state) {
    is TaskState.Running -> if (s.totalBytes > 0) "${(overallProgress() * 100).roundToInt()}%" else "统计中…"
    is TaskState.Paused, is TaskState.Cancelling ->
        if (retainedProgress > 0f) "${(retainedProgress * 100).roundToInt()}%" else s.statusLabel()
    else -> state.statusLabel()
}
