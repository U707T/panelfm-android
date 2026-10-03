package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * 属性面板（复刻 MT）：名称 / 目录 / 类型 / 大小（含字节数）/ 修改时间 / 权限（drwxrws---(2770)）/
 * 所有者 / 用户组 / 文件数 / 文件夹数；「更多」展开位置、ETag、链接指向、可用空间与「复制路径」。
 * 打开时异步补全最新元数据；文件夹自动统计子项。
 */
@Composable
fun PropertiesDialog(container: com.u707t.panelfm.AppContainer, item: FileMetadata, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var meta by remember(item.uri) { mutableStateOf(item) }
    var spaceText by remember(item.uri) { mutableStateOf<String?>(null) }
    var files by remember(item.uri) { mutableStateOf(-1) }
    var dirs by remember(item.uri) { mutableStateOf(-1) }
    var bytes by remember(item.uri) { mutableStateOf(-1L) }
    var more by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val vfs = container.locator.find(item.uri)
        val fresh = withContext(Dispatchers.IO) { runCatching { vfs?.stat(item.uri) }.getOrNull() }
        fresh?.let { meta = it }
        val isDir = fresh?.isDirectory ?: item.isDirectory
        val space = withContext(Dispatchers.IO) { runCatching { vfs?.space(item.uri) }.getOrNull() }
        spaceText = space?.let { "${Fmt.transferred(it.total - it.free, it.total)} / ${Fmt.size(it.total)}" }
        if (isDir) {
            val (f, d, b) = folderSummary(vfs, item.uri)
            files = f
            dirs = d
            bytes = b
        }
    }

    val conn = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(meta.uri))
        ?: container.connectionByAuthority(meta.uri.scheme, meta.uri.authority)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("属性") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("名称", meta.name.ifEmpty { "/" })
                InfoRow(
                    "目录",
                    (meta.uri.parent?.displayPath ?: "/").let { if (it.endsWith("/")) it else "$it/" },
                )
                InfoRow("类型", if (meta.isDirectory) "文件夹" else (meta.mimeType ?: "未知类型"))
                if (meta.isDirectory) {
                    InfoRow("大小", if (bytes >= 0) "${Fmt.size(bytes)} (${bytes})" else "统计中…")
                    InfoRow("文件数", if (files >= 0) files.toString() else "统计中…")
                    InfoRow("文件夹数", if (dirs >= 0) dirs.toString() else "统计中…")
                } else {
                    InfoRow("大小", if (meta.size >= 0) "${Fmt.size(meta.size)} (${meta.size})" else "—")
                }
                InfoRow("修改时间", if (meta.lastModified > 0) Fmt.fullTime(meta.lastModified) else "—")
                InfoRow(
                    "权限",
                    meta.permissions?.let {
                        "${Fmt.modeLong(it, meta.isDirectory, meta.isSymlink)}(${Integer.toOctalString(it and 0xFFF)})"
                    } ?: "—",
                )
                InfoRow("所有者", meta.owner ?: "—")
                InfoRow("用户组", meta.group ?: "—")
                if (more) {
                    InfoRow(
                        "位置",
                        buildString {
                            append(meta.uri.scheme)
                            conn?.name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            if (meta.uri.authority.isNotBlank()) append(" · ").append(meta.uri.authority)
                        },
                    )
                    meta.etag?.let { InfoRow("ETag", it) }
                    meta.symlinkTarget?.let { InfoRow("链接指向", it) }
                    InfoRow("可用空间", spaceText ?: "—")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(meta.uri.toString()))
                        }) { Text("复制路径") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { more = !more }) { Text(if (more) "收起" else "更多") }
        },
    )
}

/** 递归统计文件夹：文件数 / 文件夹数 / 总字节（最多 5 万项，防超大目录卡界面） */
private suspend fun folderSummary(
    vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem?,
    uri: VfsUri,
): Triple<Int, Int, Long> = withContext(Dispatchers.IO) {
    if (vfs == null) return@withContext Triple(0, 0, 0L)
    var files = 0
    var dirs = 0
    var bytes = 0L
    var seen = 0
    val queue = ArrayDeque<VfsUri>().apply { add(uri) }
    while (queue.isNotEmpty() && seen < 50_000) {
        val dir = queue.removeFirst()
        val children = runCatching { vfs.list(dir) }.getOrDefault(emptyList())
        for (child in children) {
            seen++
            if (child.isDirectory) {
                dirs++
                queue.add(child.uri)
            } else {
                files++
                bytes += child.size.coerceAtLeast(0)
            }
        }
    }
    Triple(files, dirs, bytes)
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
