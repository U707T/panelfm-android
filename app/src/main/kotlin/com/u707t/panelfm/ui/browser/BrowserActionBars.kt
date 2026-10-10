package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Spacer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtBottomIconButton
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.PaneEdgeShadow
import com.u707t.panelfm.core.ui.VDividerPx
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
 * 任务条行 —— 只显示进行中 / 失败的任务（完成即从任务条消失，引擎稍后把列表项一起收走）。
 * 结构照 MT 的进度块简化：`操作 · 当前文件/状态` + 百分比 + 操作按钮，下面统计行与总进度。
 */
@Composable
internal fun TaskRow(snapshot: TransferTaskSnapshot, controller: BrowserController, onOpenTasks: () -> Unit) {
    val state = snapshot.state
    val opLabel = if (snapshot.op == TransferOp.COPY) "复制" else "移动"
    val progress = snapshot.overallProgress()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(opLabel)
                    when (state) {
                        is TaskState.Running -> append(" · ${state.currentName.ifBlank { "…" }}")
                        is TaskState.Cancelling -> append(" · 正在取消…")
                        is TaskState.Paused -> append(" · 已暂停")
                        is TaskState.WaitingConflict -> append(" · 等待冲突处理")
                        is TaskState.Failed -> append(" · 失败")
                        TaskState.Queued -> append(" · 排队中")
                        else -> {}
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state is TaskState.Running) {
                Text(
                    "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (state) {
                is TaskState.Running, TaskState.Queued ->
                    TextButton(onClick = { controller.pauseTask(snapshot.id) }) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                TaskState.Paused ->
                    TextButton(onClick = { controller.resumeTask(snapshot.id) }) { Text("继续", style = MaterialTheme.typography.labelSmall) }
                else -> {}
            }
            when (state) {
                is TaskState.Failed, is TaskState.Done, TaskState.Cancelled ->
                    TextButton(onClick = { controller.removeTask(snapshot.id) }) { Text("移除", style = MaterialTheme.typography.labelSmall) }
                TaskState.Cancelling -> {} // 已在收尾，不再提供操作
                else ->
                    TextButton(onClick = { controller.cancelTask(snapshot.id) }) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = onOpenTasks) { Text("详情", style = MaterialTheme.typography.labelSmall) }
        }
        if (state is TaskState.Running) ThinProgressBar(progress)
        when (state) {
            is TaskState.Running -> Text(
                snapshot.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is TaskState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> {}
        }
    }
}
