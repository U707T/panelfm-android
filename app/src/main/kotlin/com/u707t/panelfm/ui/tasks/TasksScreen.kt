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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
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
import com.u707t.panelfm.core.ui.ThinProgressBar

/**
 * 传输任务页（v2.3.0：明细按 MT 进度块的键值结构重排）：
 *
 * ```
 * [进行中]  复制 1 项                        23%
 * 名称    ez122814.mp4
 * 从      Vedios
 * 到      一键另存
 * 进度    221.18 M / 950.58 M
 * 速度    66.05 M/s · 剩 0:11
 * ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 * 暂停   取消
 * ```
 *
 * 旧卡片把「当前文件名 / 统计行」写成两行自由文本、进度条夹在标题与文件名之间，
 * 与 MT 的「名称: / 从: / 到: / 剩余项: / 速度:」逐行键值对不上。现在标签固定 40dp
 * 对齐，值左缘成一条竖线；进度条统一贴到卡片底边（MT `0x7f0c00b8` 也是贴底）。
 *
 * 行为不变：完成 / 取消在保留期（引擎默认 8 秒）后自动收走；失败刻意保留到用户处理。
 */
@Composable
fun TasksScreen(container: AppContainer, onBack: () -> Unit) {
    val tasks by container.engine.snapshots.collectAsState(initial = emptyList())
    val ordered = tasks.sortedForDisplay()
    val activeCount = ordered.count { it.state.isActive }
    // 「进行中」只数真正在传的：isActive 还含排队 / 暂停 / 等待冲突 / 正在取消，
    // 全算进去会让「暂停全部」之后标题仍写着「2 个进行中」。
    val runningCount = ordered.count { it.state is TaskState.Running }
    val finishedCount = ordered.count { it.state.isFinished }
    val failedCount = ordered.count { it.state is TaskState.Failed }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "传输任务",
            subtitle = when {
                ordered.isEmpty() -> "暂无任务"
                runningCount > 0 -> "${ordered.size} 个任务 · $runningCount 个进行中"
                activeCount > 0 -> "${ordered.size} 个任务 · 已暂停 / 等待中"
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
                onClick = {
                    // 第 9 批 🔵5：列表里有失败任务时先确认——「失败必须留痕」是引擎侧的设计
                    //（失败不自动收走），一键抹掉与它矛盾；无误删风险时才直接清。
                    if (failedCount > 0) confirmClear = true else container.engine.clearFinished()
                },
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空任务列表？") },
            text = { Text("其中 $failedCount 个失败任务也会被一并移除，错误信息不再保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    container.engine.clearFinished()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
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
    val accent = state.accentColor()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MtSpec.CornerMedium))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 标题行：状态徽标 + 任务名 + 一眼进度
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaskStatusBadge(state)
            Spacer(Modifier.width(8.dp))
            Text(
                snapshot.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                snapshot.trailingLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }

        // MT 进度块的键值明细（与传输对话框共用同一份组件，避免两处各写一套）
        TaskDetailBlock(snapshot, Modifier.padding(top = 2.dp))

        // 进度条贴底：批量传输 = 当前文件（细）+ 总进度（粗），单文件只留一条
        if (state is TaskState.Running && state.total > 1 && item >= 0f) {
            ThinProgressBar(
                progress = item,
                modifier = Modifier.padding(top = 4.dp),
                color = accent.copy(alpha = 0.55f),
                height = 2.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            )
        }
        if (state is TaskState.Running || state is TaskState.Done ||
            ((state is TaskState.Paused || state is TaskState.Cancelling) && snapshot.retainedProgress >= 0f)
        ) {
            ThinProgressBar(
                progress = overall,
                modifier = Modifier.padding(top = if (state is TaskState.Running && item >= 0f) 2.dp else 4.dp),
                color = accent,
                height = 4.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
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
