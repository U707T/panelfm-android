package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri

/** 移动二次确认（需求：移动必须确认，且写明跨协议中转语义） */
@Composable
fun MoveConfirmDialog(pending: PendingMove, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("移动到对面窗格？") },
        text = {
            Column {
                Text("共 ${pending.count} 项" + if (pending.bytes > 0) "，约 ${Fmt.size(pending.bytes)}" else "")
                Text("从：${pending.fromLabel}", style = MaterialTheme.typography.bodySmall)
                Text("到：${pending.toLabel}", style = MaterialTheme.typography.bodySmall)
                if (pending.crossVfs) {
                    Text(
                        "跨存储移动 = 先复制再删除源文件；中断时可能出现副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移动") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/** 同名冲突（需求：覆盖 / 跳过 / 保留两者 / 全部应用） */
@Composable
fun ConflictDialog(info: ConflictInfo, onDecision: (ConflictPolicy, Boolean) -> Unit) {
    var applyAll by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDecision(ConflictPolicy.SKIP, applyAll) },
        title = { Text("已存在同名项") },
        text = {
            Column {
                Text("源：${info.sourceName}")
                Text("目标：${info.destName}" + if (info.destSize >= 0) "（${Fmt.size(info.destSize)}）" else "")
                Text(
                    if (info.isDirectory) "目标是一个文件夹，覆盖将递归合并/替换。" else "覆盖会替换目标文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("全部应用（本次任务后续冲突同样处理）", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(ConflictPolicy.OVERWRITE, applyAll) }) { Text("覆盖") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecision(ConflictPolicy.SKIP, applyAll) }) { Text("跳过") }
                TextButton(onClick = { onDecision(ConflictPolicy.KEEP_BOTH, applyAll) }) { Text("保留两者") }
            }
        },
    )
}

/** 输入框（新建 / 重命名 / 路径 / 过滤 / 权限） */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "名称",
    hint: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String = "确定", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 属性面板 */
@Composable
fun PropertiesDialog(item: FileMetadata, space: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name.ifEmpty { "属性" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("路径", item.uri.displayPath)
                InfoRow("协议", item.uri.scheme)
                InfoRow("类型", if (item.isDirectory) "文件夹" else (item.mimeType ?: "未知"))
                if (!item.isDirectory) InfoRow("大小", Fmt.size(item.size))
                if (item.lastModified > 0) InfoRow("修改时间", Fmt.fullTime(item.lastModified))
                item.permissions?.let { InfoRow("权限", "${Fmt.mode(it)} (${Integer.toOctalString(it)})") }
                item.owner?.let { InfoRow("属主", it) }
                item.etag?.let { InfoRow("ETag", it) }
                item.symlinkTarget?.let { InfoRow("链接指向", it) }
                space?.let { InfoRow("可用空间", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ---------------------------------------------------------------------------
// MT 的动作菜单（截图复刻）：顶部提示条（带 ● 说明 + ✕）+ 两列网格 + 带 ● 的项支持长按
// ---------------------------------------------------------------------------

data class MtAction(
    val id: String,
    val label: String,
    val glyph: String,
    /** 带 ● ：长按可触发「单窗口操作」 */
    val singleWindow: Boolean = false,
    val enabled: Boolean = true,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MtActionSheet(
    actions: List<MtAction>,
    onAction: (String) -> Unit,
    onLongAction: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 子菜单（如「工具」）可传标题；主动作菜单按 MT 截图只显示提示条 */
    title: String? = null,
) {
    val showTip = actions.any { it.singleWindow }
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.material3.AlertDialogDefaults.shape,
            color = androidx.compose.material3.AlertDialogDefaults.containerColor,
            tonalElevation = androidx.compose.material3.AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                // 顶部：MT 的提示条（带 ● 的菜单表示可以长按触发单窗口操作）+ ✕
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showTip) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            "带 ● 的菜单表示可以长按触发单窗口操作",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp),
                        )
                    } else {
                        Text(
                            title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .padding(4.dp),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 6.dp))
                // 两列网格（MT 截图2 布局）
                actions.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { action ->
                            MtActionCell(action, Modifier.weight(1f), onAction, onLongAction)
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MtActionCell(
    action: MtAction,
    modifier: Modifier,
    onAction: (String) -> Unit,
    onLongAction: (String) -> Unit,
) {
    Row(
        modifier
            .padding(vertical = 6.dp)
            .combinedClickable(
                enabled = action.enabled,
                onClick = { onAction(action.id) },
                onLongClick = { if (action.singleWindow) onLongAction(action.id) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            action.glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 10.dp),
        )
        if (action.singleWindow) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 只读信息弹窗（校验值 / 工具结果） */
@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
