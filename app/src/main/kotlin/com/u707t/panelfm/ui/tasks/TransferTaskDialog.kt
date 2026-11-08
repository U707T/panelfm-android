package com.u707t.panelfm.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.itemProgress
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.ThinProgressBar

/**
 * 传输进度**模态对话框**（v2.3.2，按要求做成 MT 那种「带进度条的对话框」）。
 *
 * 与 v2.3.0 的内联任务条相比，这里的取舍是「一次只说一件事」：
 *  - **只显示一个任务**（[focus]，通常是在传的那个），标题行说明「另有 N 个任务」；
 *  - 明细沿用 MT 进度块的键值结构（[TaskDetailBlock]），进度条贴底；
 *  - 底部三档动作：暂停 / 继续 → 取消 / 移除 → 后台运行（关闭对话框）。
 *
 * 为什么用 [Dialog] 而不是 `AlertDialog`：MT 的进度块是**自定义 View**（`0x7f0c00b8`），
 * 不是 AlertDialog 的三段式；`AlertDialog` 会强行给标题 / 文本 / 按钮三段各自的内边距与
 * 最小高度，进度条永远贴不到卡片底边。
 *
 * **不会自动消失**：`dismissOnBackPress` 打开、点外部关闭 —— 但都走 [onDismiss]，
 * 语义是「转到后台运行」，不是取消任务（取消要显式点按钮）。
 */
@Composable
fun TransferTaskDialog(
    focus: TransferTaskSnapshot,
    otherCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val state = focus.state
    val accent = state.accentColor()
    val overall = focus.overallProgress()
    val item = focus.itemProgress()
    val showItemBar = state is TaskState.Running && state.total > 1 && item >= 0f
    // 没有保留帧（排队中就被暂停）时不画空槽：一条 0% 的条读起来像「传输被重置」。
    val showOverallBar = when (state) {
        is TaskState.Running, is TaskState.Done -> true
        is TaskState.Paused, is TaskState.Cancelling -> focus.retainedProgress >= 0f
        else -> false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .semantics { paneTitle = "传输任务" },
        ) {
            // ---------------- 标题行：状态徽标 + 任务名 + 一眼进度
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskStatusBadge(state)
                Spacer(Modifier.width(8.dp))
                Text(
                    focus.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    focus.trailingLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    maxLines = 1,
                )
            }

            // ---------------- 键值明细（与任务页同一份组件）
            TaskDetailBlock(
                snapshot = focus,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ---------------- 进度条贴底：批量 = 当前文件（细）+ 总进度（粗）
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                if (showItemBar) {
                    ThinProgressBar(
                        progress = item,
                        color = accent.copy(alpha = 0.55f),
                        height = 2.dp,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    )
                    Spacer(Modifier.size(3.dp))
                }
                if (showOverallBar) {
                    ThinProgressBar(
                        progress = overall,
                        color = accent,
                        height = 4.dp,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                }
            }

            // ---------------- 动作行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is TaskState.Running, TaskState.Queued ->
                        TextButton(onClick = onPause) { Text("暂停") }
                    is TaskState.Paused -> TextButton(onClick = onResume) { Text("继续") }
                    else -> {}
                }
                when (state) {
                    is TaskState.Failed, is TaskState.Done, is TaskState.Cancelled ->
                        TextButton(onClick = onRemove) { Text("移除") }
                    is TaskState.Cancelling -> Unit // 已在收尾，不再提供操作
                    else -> TextButton(onClick = onCancel) { Text("取消") }
                }
                Spacer(Modifier.weight(1f))
                // 「另有 N 个」= 去任务页看全部（对话框一次只显示一个，多重任务要有个出口）；
                // 「后台运行」= 关掉对话框。任务照旧跑，回来靠底栏胶囊 / 通知栏 / 侧拉栏。
                // 两个动作不能合成一个按钮：一个是「换地方看」，一个是「不看了」，语义相反。
                if (otherCount > 0) {
                    TextButton(onClick = onOpenTasks) {
                        Text("另有 $otherCount 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("后台运行", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 关闭对话框之后、底部保留的**一行极简重开入口**（悬浮胶囊）。
 *
 * 尺寸刻意压到 32dp 高、单行、只带一个进度点：它是「回去看进度」的快捷方式，
 * 不该重新变成 v2.3.0 那种两行卡片 + 图标按钮的常驻任务条。
 */
@Composable
fun TransferCapsule(
    snapshot: TransferTaskSnapshot,
    otherCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = snapshot.state.accentColor()
    val progress = snapshot.overallProgress()
    val showBar = when (snapshot.state) {
        is TaskState.Running, is TaskState.Done -> true
        is TaskState.Paused, is TaskState.Cancelling -> snapshot.retainedProgress >= 0f
        else -> false
    }
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(percent = 50)).background(accent))
        Spacer(Modifier.width(7.dp))
        Text(
            buildString {
                append(snapshot.state.statusLabel())
                if (otherCount > 0) append(" · 共 ${otherCount + 1} 个")
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            "  ${snapshot.trailingLabel()}",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        if (showBar) {
            Spacer(Modifier.width(8.dp))
            ThinProgressBar(
                progress = progress,
                modifier = Modifier.width(36.dp),
                color = accent,
                height = 3.dp,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** 状态徽标：文案与配色按状态分档（进行中 = 主色，失败 = 错误色，等待冲突 = 警示）。 */
@Composable
fun TaskStatusBadge(state: TaskState, modifier: Modifier = Modifier) {
    val color = state.accentColor()
    Text(
        state.statusLabel(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 模态框里的「当前任务」怎么挑：优先选需要用户盯着的那个，其次才是**正在传**的。
 *
 * 直接用列表第一个是不行的：`sortedForDisplay()` 把「等待冲突」排在「进行中」之前，
 * 于是两侧都在传时，对话框会一直显示那个卡在冲突上的任务 —— 进度条不动，像卡死。
 * 这里刻意与展示排序解耦：**先进行中，再等冲突，再暂停 / 取消中，最后排队**。
 */
fun TransferTaskSnapshot.dialogRank(): Int = when (state) {
    is TaskState.Running -> 0
    is TaskState.WaitingConflict -> 1
    is TaskState.Paused, is TaskState.Cancelling -> 2
    is TaskState.Failed -> 3
    is TaskState.Queued -> 4
    else -> 5
}

/** 对话框要展示的任务（null = 没有可展示的）。失败任务也要能被叫出来：错误必须留痕。 */
fun List<TransferTaskSnapshot>.dialogFocus(): TransferTaskSnapshot? =
    withIndex()
        .minWithOrNull(compareBy({ it.value.dialogRank() }, { it.index }))
        ?.value
