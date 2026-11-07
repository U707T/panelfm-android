package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.currentNameOrRetained
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.ui.tasks.accentColor
import com.u707t.panelfm.ui.tasks.trailingLabel

// ================================================================================================
// 从 DualPaneScreen 拆出（重审 🔴1b）：顶栏动作条项 / 底栏文字按钮 / 任务条行。
// ================================================================================================

/**
 * MT 顶栏动作条（复刻 `0x7f0c0034` 的 `09022F` HorizontalScrollView）：
 * 前三项硬编码为 **复制 / 移动 / 删除**（MT 原文），其余为 PanelFM 的扩展动作。
 * 源在左窗格 → `复制 ->`；源在右窗格 → `<- 复制`（箭头始终指向目标窗口）。
 */
@Composable
internal fun TopActionItems(
    focused: PaneState,
    focusSide: PaneSide,
    controller: BrowserController,
    onDelete: (List<FileMetadata>) -> Unit,
    onRename: (FileMetadata) -> Unit,
    onCompress: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit,
    onProperties: (FileMetadata) -> Unit,
    onShare: (List<FileMetadata>) -> Unit,
    /** 按类型的二级动作（压缩包解压 / APK 安装 / APK 信息 / 内置查看） */
    onTypedAction: (String, FileMetadata) -> Unit = { _, _ -> },
) {
    val picked = focused.selectedItems
    val twoFiles = picked.size == 2 && picked.none { it.isDirectory }
    val anyDirectory = picked.any { it.isDirectory }
    val inArchive = focused.uri.scheme == "archive"
    // 单选时的类型化动作（与长按菜单同一套判定）
    val typedSingle = picked.singleOrNull()?.takeIf { !it.isDirectory }
    val typedIds = typedSingle?.let {
        TypeActions.typedActionIds(
            extension = it.extension,
            isDirectory = false,
            inArchive = inArchive,
            apkInstallable = it.uri.scheme == "local",
        )
    }.orEmpty()

    // MT 0x7f0c0034：前三项 = 复制 / 移动 / 删除（图标 22dp + 文字 14sp + 左右 padding 15dp）
    MtActionButton(MtIcon.COPY, crossPaneLabel("复制", focusSide)) { controller.copyToOther(focusSide) }
    MtActionButton(MtIcon.CUT, crossPaneLabel("移动", focusSide)) { controller.moveToOther(focusSide) }
    MtActionButton(MtIcon.DELETE, "删除", enabled = picked.isNotEmpty()) {
        if (picked.isNotEmpty()) {
            if (inArchive) controller.deleteInsideArchive(focusSide, picked) else onDelete(picked)
        }
    }
    MtActionButton(MtIcon.EDIT, "重命名", enabled = picked.isNotEmpty()) {
        picked.firstOrNull()?.let(onRename)
    }
    MtActionButton(MtIcon.ARCHIVE, "压缩", enabled = picked.isNotEmpty() && !inArchive) {
        if (picked.isNotEmpty()) onCompress()
    }
    // ---- 按类型的动作（MT：选中的是压缩包就给「解压」，是 APK 就给「安装 / APK 信息」）
    if (typedSingle != null && typedIds.isNotEmpty()) {
        if (typedIds.contains(TypeActions.ACTION_EXTRACT_HERE)) {
            MtActionButton(MtIcon.ARCHIVE, "解压") { onTypedAction(TypeActions.ACTION_EXTRACT_HERE, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_INSTALL)) {
            MtActionButton(MtIcon.GET_APP, "安装") { onTypedAction(TypeActions.ACTION_INSTALL, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_APK_INFO)) {
            MtActionButton(MtIcon.ANDROID, "APK 信息") { onTypedAction(TypeActions.ACTION_APK_INFO, typedSingle) }
        }
        if (typedIds.contains(TypeActions.ACTION_OPEN_INTERNAL)) {
            MtActionButton(MtIcon.EYE, "查看") { onTypedAction(TypeActions.ACTION_OPEN_INTERNAL, typedSingle) }
        }
        // 文本 / 代码：唯一入口就是编辑器（v2.0.17 起没有单独的文本查看器）
        if (typedIds.contains(TypeActions.ACTION_EDIT_TEXT)) {
            MtActionButton(MtIcon.EDIT, "编辑") { onTypedAction(TypeActions.ACTION_EDIT_TEXT, typedSingle) }
        }
    }
    MtActionButton(MtIcon.COMPARE, "文件对比", enabled = twoFiles) { controller.startFileDiff(focusSide) }
    // MT 0x7f0c0025「选择当前目录」：复制 / 移动的目标改成「浏览后确认」
    MtActionButton(MtIcon.FOLDER, "复制到…", enabled = !inArchive, onClick = onCopyTo)
    MtActionButton(MtIcon.FOLDER, "移动到…", enabled = !inArchive, onClick = onMoveTo)
    MtActionButton(MtIcon.PASTE, "复制到剪贴板") { controller.copySelectionToClipboard(focusSide) }
    MtActionButton(MtIcon.BOOKMARK, "添加书签") { controller.addBookmark(focusSide) }
    MtActionButton(MtIcon.INFO, "属性", enabled = picked.size == 1) {
        picked.firstOrNull()?.let(onProperties)
    }
    MtActionButton(MtIcon.SHARE, "分享", enabled = !anyDirectory && !inArchive && picked.isNotEmpty()) {
        onShare(picked)
    }
}



/** 底栏文字按钮（多选工具栏用） */
@Composable
internal fun BottomTextCommand(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val tapAction = onClick
    val longAction = onLongClick
    // v2.0.14：长按触发补震动（MT 的长按反馈）；长按超时由根部 ViewConfiguration 统一为 400ms
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (longAction != null) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    longAction()
                                },
                                onTap = { tapAction() },
                            )
                        }
                        .semantics {
                            role = Role.Button
                            onClick { tapAction(); true }
                            onLongClick { longAction(); true }
                        }
                } else Modifier.clickableNoRipple(onClick = tapAction),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
/**
 * 内联任务条（v2.3.0 重做）—— 按 MT 传输进度块（`0x7f0c00b8`）的信息顺序压成两行：
 *
 * ```
 * ● 复制 · 兄弟三人行.mp4              30%   ⏸  ✕
 *   282.82 M / 949.47 M · 49.81 M/s · 剩 0:13
 *   ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 * ```
 *
 * 旧实现把「复制 · 文件」「30%」「暂停」「取消」「详情」五个元素平铺在同一行：右侧三个
 * 同色同重的文字按钮与左侧文件名挤在一起，进度条又夹在两行字中间，整块读起来像三行
 * 散字叠着，和下面的底栏也分不开。现在：
 *  - 文字按钮换图标（暂停 / 继续、取消 / 移除），「详情」由整块点击承担；
 *  - 进度条移到卡片底边（MT 的进度块同样是贴底）；
 *  - 卡片化 + 状态点，与任务页同一套视觉。
 *
 * 只显示进行中 / 失败的任务（完成即消失，见 [forBrowserBar]）。
 */
@Composable
internal fun TaskRow(snapshot: TransferTaskSnapshot, controller: BrowserController, onOpenTasks: () -> Unit) {
    val state = snapshot.state
    val opLabel = if (snapshot.op == TransferOp.COPY) "复制" else "移动"
    val progress = snapshot.overallProgress()
    val accent = state.accentColor()
    val name = snapshot.currentNameOrRetained()
    // 第二行：进行中给统计，失败给原因，等待冲突给提示；暂停 / 排队不再重复右侧状态词。
    val detail = when (state) {
        is TaskState.Running -> snapshot.subtitle
        is TaskState.Failed -> state.message
        is TaskState.WaitingConflict -> "等待选择冲突处理方式…"
        else -> ""
    }
    val showBar = state is TaskState.Running || state is TaskState.Paused ||
        state is TaskState.Cancelling || state is TaskState.Done

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickableNoRipple { onOpenTasks() },
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(7.dp))
            Text(
                if (name.isBlank()) snapshot.title else "$opLabel · $name",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                snapshot.trailingLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            TaskRowActions(snapshot, controller)
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (state is TaskState.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 23.dp, end = 10.dp),
            )
        }
        if (showBar) {
            ThinProgressBar(
                progress = progress,
                modifier = Modifier.padding(start = 23.dp, end = 10.dp, top = 5.dp, bottom = 6.dp),
                color = accent,
                height = 2.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        } else {
            Spacer(Modifier.size(6.dp))
        }
    }
}

/** 任务行右侧的图标操作（暂停/继续 + 取消/移除）；正在取消时不给操作。 */
@Composable
private fun TaskRowActions(snapshot: TransferTaskSnapshot, controller: BrowserController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (snapshot.state) {
            is TaskState.Running, TaskState.Queued -> MtIconButton(
                icon = MtIcon.PAUSE,
                contentDescription = "暂停",
                buttonSize = 28.dp,
                iconSize = 14.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { controller.pauseTask(snapshot.id) },
            )
            is TaskState.Paused -> MtIconButton(
                icon = MtIcon.PLAY,
                contentDescription = "继续",
                buttonSize = 28.dp,
                iconSize = 14.dp,
                tint = MaterialTheme.colorScheme.primary,
                onClick = { controller.resumeTask(snapshot.id) },
            )
            else -> {}
        }
        when (snapshot.state) {
            is TaskState.Failed, is TaskState.Done, is TaskState.Cancelled -> MtIconButton(
                icon = MtIcon.CLOSE,
                contentDescription = "移除",
                buttonSize = 28.dp,
                iconSize = 14.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { controller.removeTask(snapshot.id) },
            )
            is TaskState.Cancelling -> {} // 已在收尾，不再提供操作
            else -> MtIconButton(
                icon = MtIcon.CLOSE,
                contentDescription = "取消",
                buttonSize = 28.dp,
                iconSize = 14.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { controller.cancelTask(snapshot.id) },
            )
        }
    }
}
