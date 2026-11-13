package com.u707t.panelfm.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.VerifyMode
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.currentNameOrRetained

/**
 * 明细行的一条：[label] 固定宽度，[value] 左缘对齐；[isError] = 用错误色（失败原因）。
 *
 * 做成纯数据 + 纯函数（[taskDetailRows]），是因为这段 `when (state)` 是**唯一**决定
 * 「哪个状态显示哪些行」的地方 —— 对话框与任务页都调它。以前这段逻辑埋在 Composable 里，
 * JVM 单测碰不到，改坏了只能靠真机发现（项目里没有 androidTest，沙盒也跑不了 Robolectric）。
 */
data class TaskDetailRow(val label: String, val value: String, val isError: Boolean = false)

/**
 * MT 进度块（`0x7f0c0093` / `0x7f0c00b8`）的键值明细：`名称 / 从 / 到 / 进度 / 速度`。
 *
 * 传输对话框（`TransferTaskDialog`）与任务页卡片（`TasksScreen`）展示的是同一份信息 ——
 * 只有这一份实现，谁要用谁调。空值行直接省略，不留「—」占位。
 */
fun taskDetailRows(snapshot: TransferTaskSnapshot): List<TaskDetailRow> {
    val rows = mutableListOf<TaskDetailRow>()
    val state = snapshot.state
    val name = snapshot.currentNameOrRetained()
    if (name.isNotBlank()) rows += TaskDetailRow("名称", name)
    if (snapshot.fromLabel.isNotBlank()) rows += TaskDetailRow("从", snapshot.fromLabel)
    if (snapshot.toLabel.isNotBlank()) rows += TaskDetailRow("到", snapshot.toLabel)
    when (state) {
        is TaskState.Running -> {
            rows += TaskDetailRow("进度", Fmt.transferred(state.doneBytes, state.totalBytes).ifBlank { "统计中…" })
            rows += TaskDetailRow(
                "速度",
                listOfNotNull(
                    Fmt.speed(state.speedBps).takeIf { it.isNotBlank() },
                    if (state.etaSeconds > 0) "剩 ${Fmt.eta(state.etaSeconds)}" else null,
                    if (state.total > 1) "${state.index}/${state.total} 项" else null,
                ).joinToString(" · ").ifBlank { "—" },
            )
        }
        is TaskState.Done -> {
            rows += TaskDetailRow("结果", snapshot.subtitle)
            // 失败明细：最多列 3 条，其余只给总数（任务页可以「重试失败项」）
            state.failures.take(3).forEach { f ->
                rows += TaskDetailRow("失败", "${f.source.name}：${f.reason}", isError = true)
            }
            if (state.failureTotal > 3) {
                rows += TaskDetailRow("", "另有 ${state.failureTotal - 3} 项失败", isError = true)
            }
        }
        is TaskState.Failed -> rows += TaskDetailRow("失败", state.message, isError = true)
        is TaskState.Paused -> {
            // 暂停时把最后一帧的字节数留在「进度」行：否则卡片只剩「已暂停」，
            // 用户看不出停在哪（v2.2.x 的副标题里还有统计，重排成键值行后容易丢）。
            if (snapshot.retainedStats.isNotBlank()) rows += TaskDetailRow("进度", snapshot.retainedStats)
            rows += TaskDetailRow("状态", "已暂停 · 断点已保留，点「继续」接着传")
        }
        is TaskState.Cancelling -> {
            if (snapshot.retainedStats.isNotBlank()) rows += TaskDetailRow("进度", snapshot.retainedStats)
            rows += TaskDetailRow("状态", "正在取消操作…")
        }
        is TaskState.WaitingConflict -> rows += TaskDetailRow("状态", "等待选择冲突处理方式…")
        TaskState.Queued -> rows += TaskDetailRow("状态", "排队中，等待空闲传输位")
        is TaskState.Cancelled -> rows += TaskDetailRow("状态", snapshot.subtitle)
    }
    // 校验档位：不校验时不出这一行，避免每张卡都多一条「未校验」噪音
    when (snapshot.verify) {
        VerifyMode.NONE -> {}
        VerifyMode.SIZE -> rows += TaskDetailRow("校验", "仅大小")
        VerifyMode.HASH -> rows += TaskDetailRow("校验", "SHA-256")
    }
    return rows
}

/** 渲染 [taskDetailRows]；标签列固定 40dp，值左缘成一条竖线（MT 的键值排法）。 */
@Composable
fun TaskDetailBlock(snapshot: TransferTaskSnapshot, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        taskDetailRows(snapshot).forEach { row ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    row.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}
