package com.u707t.panelfm.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.transfer.isFinished
import com.u707t.panelfm.core.transfer.itemProgress
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.transfer.sortedForDisplay
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.ui.browser.ThinProgressBar
import kotlin.math.roundToInt

/**
 * 传输任务页（v1.5.0 重构：按 MT 进度块逻辑现代化）：
 *  - 卡片列表：状态徽标 + 标题 + 进度（当前文件 / 总进度）+ 统计（速率 / 剩余 / 用时）；
 *  - 操作按状态给：排队 / 进行中 = 暂停；已暂停 = 继续；进行中、排队、暂停、等待冲突 = 取消；
 *    失败与已结束 = 移除；
 *  - 已完成 / 已取消的任务在保留期（引擎默认 8 秒）后自动收走，不再常驻；
 *  - 「清空」只清已结束的任务；失败刻意保留到用户处理（错误必须留痕）。
 */
@Composable
fun TasksScreen(container: AppContainer, onBack: () -> Unit) {
    val tasks by container.engine.snapshots.collectAsState(initial = emptyList())
    val ordered = tasks.sortedForDisplay()
    val activeCount = ordered.count { it.state.isActive }
    val finishedCount = ordered.count { it.state.isFinished }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "传输任务",
            subtitle = when {
                ordered.isEmpty() -> "暂无任务"
                activeCount > 0 -> "${ordered.size} 个任务 · $activeCount 个进行中"
                else -> "${ordered.size} 个任务 · 已全部结束"
            },
            onBack = onBack,
        ) {
            TextButton(
                onClick = { container.engine.pauseAll() },
                enabled = ordered.any { it.state is TaskState.Running || it.state == TaskState.Queued },
            ) { Text("暂停") }
            TextButton(
                onClick = { container.engine.resumeAll() },
                enabled = ordered.any { it.state is TaskState.Paused },
            ) { Text("继续") }
            TextButton(
                onClick = { container.engine.clearFinished() },
                enabled = finishedCount > 0,
            ) { Text("清空") }
        }

        if (ordered.isEmpty()) {
            Text(
                "暂无任务。\n在双列页选中文件 → 复制 / 移动到对面窗格。\n完成的传输会自动消失，失败的会留下等待处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(ordered, key = { it.id }) { snapshot ->
                    TaskCard(
                        snapshot = snapshot,
                        onPause = { container.engine.findTask(snapshot.id)?.pause() },
                        onResume = { container.engine.findTask(snapshot.id)?.resume() },
                        onCancel = { container.engine.findTask(snapshot.id)?.cancel() },
                        onRemove = { container.engine.findTask(snapshot.id)?.let { container.engine.remove(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    snapshot: TransferTaskSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = snapshot.state
    val overall = snapshot.overallProgress()
    val item = snapshot.itemProgress()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MtSpec.CornerMedium))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 标题行：状态徽标 + 标题 +（进行中）总百分比
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateBadge(state)
            Spacer(Modifier.width(8.dp))
            Text(
                snapshot.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state is TaskState.Running) {
                Text(
                    "${(overall * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 进度：多文件进行中 = 当前文件（细条）+「总进度」；单文件 = 一条；已完成 = 满条
        when {
            state is TaskState.Running && state.total > 1 && item >= 0f -> {
                ThinProgressBar(item, height = 3.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                Text(
                    "总进度 ${(overall * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThinProgressBar(overall, height = 4.dp)
            }
            state is TaskState.Running -> ThinProgressBar(overall, height = 4.dp)
            state is TaskState.Done -> ThinProgressBar(1f, height = 4.dp)
            else -> {}
        }

        // 细节行
        when (state) {
            is TaskState.Running -> {
                Text(
                    state.currentName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    snapshot.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TaskState.Done -> Text(
                snapshot.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is TaskState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            is TaskState.WaitingConflict -> DetailText("等待选择冲突处理方式…")
            is TaskState.Cancelling -> DetailText("正在取消操作…")
            is TaskState.Paused -> DetailText("已暂停 · 点「继续」接着传（断点已保留）")
            is TaskState.Cancelled -> DetailText("已取消 · 未传完的文件可在下次复制时断点续传")
            TaskState.Queued -> DetailText("排队中，等待空闲传输位")
        }

        // 操作行
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state) {
                is TaskState.Running, TaskState.Queued -> TextButton(onClick = onPause) { Text("暂停") }
                is TaskState.Paused -> TextButton(onClick = onResume) { Text("继续") }
                else -> {}
            }
            when (state) {
                is TaskState.Failed, is TaskState.Done, is TaskState.Cancelled ->
                    TextButton(onClick = onRemove) { Text("移除") }
                is TaskState.Cancelling -> {} // 已在收尾，不再提供操作
                else -> TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

@Composable
private fun DetailText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 状态徽标：文案与配色按状态分档（进行中 = 主色，失败 = 错误色，等待冲突 = 警示）。 */
@Composable
private fun StateBadge(state: TaskState) {
    val label: String
    val color: Color
    when (state) {
        TaskState.Queued -> {
            label = "排队中"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        is TaskState.Running -> {
            label = "进行中"
            color = MaterialTheme.colorScheme.primary
        }
        is TaskState.Paused -> {
            label = "已暂停"
            color = MaterialTheme.colorScheme.tertiary
        }
        is TaskState.WaitingConflict -> {
            label = "等待冲突"
            color = MaterialTheme.colorScheme.error
        }
        is TaskState.Cancelling -> {
            label = "正在取消…"
            color = MaterialTheme.colorScheme.tertiary
        }
        is TaskState.Done -> {
            label = "已完成"
            color = MaterialTheme.colorScheme.primary
        }
        is TaskState.Failed -> {
            label = "失败"
            color = MaterialTheme.colorScheme.error
        }
        is TaskState.Cancelled -> {
            label = "已取消"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
