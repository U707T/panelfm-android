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

/**
 * 内联任务条的第二行文案（纯函数，可单测）。
 *
 * - 进行中：统计行；失败：错误原因；等待冲突：提示；
 * - **暂停 / 取消中：状态词 + 保留的最后一帧统计**。v2.3.0 把「暂停 / 取消」从文字按钮换成
 *   图标后，行尾只剩百分比 —— 暂停的任务看起来仍在跑，正在取消的任务完全没有提示。
 *   状态词必须留在这一行（MT 的进度块也是「已暂停」+ 字节数并排）。
 * - 排队中：留空 —— 行尾已经写着「排队中」，重复一遍只是噪音。
 */
fun TransferTaskSnapshot.barDetail(): String = when (val s = state) {
    is TaskState.Running -> subtitle
    is TaskState.Failed -> s.message
    is TaskState.WaitingConflict -> "等待选择冲突处理方式…"
    is TaskState.Paused -> joinDetail("已暂停", retainedStats)
    is TaskState.Cancelling -> joinDetail("正在取消操作…", retainedStats)
    else -> ""
}

private fun joinDetail(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString(" · ")
