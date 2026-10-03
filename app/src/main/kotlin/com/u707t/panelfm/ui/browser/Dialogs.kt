package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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

/** 新建 / 重命名 输入框 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "名称",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()); onDismiss() },
            ) { Text("确定") }
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

/** 单个条目的操作菜单（长按/⋮ 触发） */
@Composable
fun RowActionsDialog(
    item: FileMetadata,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onCopyToOther: () -> Unit,
    onMoveToOther: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onOpenWithSystem: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!item.isDirectory) {
                    TextButton(onClick = { onDismiss(); onOpen() }) { Text("预览 / 打开") }
                    TextButton(onClick = { onDismiss(); onOpenWithSystem() }) { Text("用其他应用打开") }
                }
                TextButton(onClick = { onDismiss(); onRename() }) { Text("重命名") }
                TextButton(onClick = { onDismiss(); onCopyToOther() }) { Text("复制到对面窗格") }
                TextButton(onClick = { onDismiss(); onMoveToOther() }) { Text("移动到对面窗格") }
                TextButton(onClick = { onDismiss(); onDelete() }) { Text("删除") }
                TextButton(onClick = { onDismiss(); onProperties() }) { Text("属性") }
                item.uri.let { uri: VfsUri ->
                    TextButton(onClick = { onDismiss() }) { Text("路径：${uri.displayPath}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
