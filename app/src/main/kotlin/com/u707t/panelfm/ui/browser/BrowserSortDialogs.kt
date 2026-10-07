package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.u707t.panelfm.core.ui.safeAreaPadding
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
// 从 DualPaneScreen 拆出（重审 🔴1b）：排序对话框 / 排序管理（供 BrowserDialogHost 调用）。
// ================================================================================================





private fun sortLabel(by: SortBy): String = when (by) {
    SortBy.NAME -> "按名称"
    SortBy.SIZE -> "按大小"
    SortBy.TIME -> "按日期"
    SortBy.TYPE -> "按类型"
}

// --------------------------------------------------------------------------- MT 排序对话框

/** 复刻 MT「排序方式 - 窗口名」：2×2 单选项 + 仅应用于此文件夹 / 逆向排序 + 管理/取消/确定 */
@Composable
internal fun MtSortDialog(
    paneLabel: String,
    initial: SortSpec,
    folderRuleExists: Boolean,
    onManage: () -> Unit,
    onConfirm: (SortSpec, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var by by remember { mutableStateOf(initial.by) }
    var reverse by remember { mutableStateOf(!initial.ascending) }
    var folderOnly by remember { mutableStateOf(folderRuleExists) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式 - $paneLabel") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    SortChoice("按名称", SortBy.NAME, by) { by = it }
                    SortChoice("按大小", SortBy.SIZE, by) { by = it }
                }
                Row(Modifier.fillMaxWidth()) {
                    SortChoice("按日期", SortBy.TIME, by) { by = it }
                    SortChoice("按类型", SortBy.TYPE, by) { by = it }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { folderOnly = !folderOnly }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = folderOnly, onCheckedChange = { folderOnly = it })
                    Text("仅应用于此文件夹", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { reverse = !reverse }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = reverse, onCheckedChange = { reverse = it })
                    Text("逆向排序", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { TextButton(onClick = onManage) { Text("管理") } },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    onConfirm(initial.copy(by = by, ascending = !reverse), folderOnly)
                }) { Text("确定") }
            }
        },
    )
}

@Composable
private fun RowScope.SortChoice(label: String, value: SortBy, current: SortBy, onPick: (SortBy) -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .clickableNoRipple { onPick(value) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == value, onClick = { onPick(value) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** MT「排序 - 管理」：查看/清除「仅应用于此文件夹」记住的规则 */
@Composable
internal fun MtSortManageDialog(
    rules: Map<String, String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rules.isEmpty()) {
                    Text("还没有「仅应用于此文件夹」的排序记录。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("已记住 ${rules.size} 个文件夹的排序：", style = MaterialTheme.typography.bodySmall)
                    rules.entries.take(12).forEach { (key, value) ->
                        val path = runCatching { VfsUri.parse(key).displayPath }.getOrDefault(key)
                        val desc = decodeSortSpec(value)?.let { s ->
                            sortLabel(s.by) + if (s.ascending) "·升序" else "·降序"
                        } ?: value
                        Text(
                            "• $path — $desc",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (rules.size > 12) Text("…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear, enabled = rules.isNotEmpty()) { Text("清除全部") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
