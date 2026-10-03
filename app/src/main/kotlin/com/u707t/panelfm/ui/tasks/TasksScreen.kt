package com.u707t.panelfm.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.ui.browser.ThinProgressBar

/** 任务页：进度 / 速率 / 剩余时间 / 暂停 / 继续 / 取消 / 清空。 */
@Composable
fun TasksScreen(container: AppContainer, onBack: () -> Unit) {
    val tasks by container.engine.tasks.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("传输任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { container.engine.pauseAll() }) { Text("全部暂停") }
            TextButton(onClick = { container.engine.resumeAll() }) { Text("全部继续") }
            TextButton(onClick = { container.engine.clearFinished() }) { Text("清空已完成") }
        }

        if (tasks.isEmpty()) {
            Text(
                "暂无任务。在双列页选中文件 → 底部 ⇄ → 复制/移动到对面窗格。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                tasks.forEach { task ->
                    val state = task.state.value
                    val progress = when (state) {
                        is TaskState.Running -> if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
                        is TaskState.Done -> 1f
                        else -> 0f
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "${task.title}  (${task.id.take(6)})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        val line = when (state) {
                            is TaskState.Running ->
                                "${state.index}/${state.total} · ${state.currentName} · ${Fmt.transferred(state.doneBytes, state.totalBytes)} · " +
                                    "${Fmt.speed(state.speedBps)} · 剩 ${Fmt.eta(state.etaSeconds)}"
                            is TaskState.Done ->
                                "完成：成功 ${state.ok} / 跳过 ${state.skipped} / 失败 ${state.failed} · 用时 ${Fmt.duration(state.elapsedMs)}" +
                                    (state.note?.let { " · $it" } ?: "")
                            is TaskState.Failed -> "失败：${state.message}"
                            is TaskState.Cancelled -> "已取消"
                            is TaskState.Paused -> "已暂停"
                            is TaskState.WaitingConflict -> "等待冲突处理"
                            TaskState.Queued -> "排队中"
                        }
                        Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinProgressBar(progress, Modifier.padding(vertical = 5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (state) {
                                is TaskState.Running, TaskState.Queued ->
                                    TextButton(onClick = { task.pause() }) { Text("暂停") }
                                TaskState.Paused -> TextButton(onClick = { task.resume() }) { Text("继续") }
                                else -> {}
                            }
                            if (state !is TaskState.Done && state !is TaskState.Cancelled) {
                                TextButton(onClick = { task.cancel() }) { Text("取消") }
                            }
                            TextButton(onClick = { container.engine.remove(task) }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
}
