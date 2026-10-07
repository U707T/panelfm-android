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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.u707t.panelfm.core.model.defaultPolicy
import com.u707t.panelfm.core.model.explanationText
import com.u707t.panelfm.core.ui.DialogIcon
import com.u707t.panelfm.core.ui.DialogIconMode
import com.u707t.panelfm.core.ui.HistoryButton
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
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

/**
 * 同名冲突（复刻 MT 0x7f0c0098「文件已存在」）。
 *
 * MT 用**三个单选**而不是三个按钮：
 *   复制并替换 / 跳过该文件 / 复制但保留两个文件（移动场景换成「移动并…」）
 * 另有 ☐ 为后续冲突执行相同操作（MT 0x7f1100a5）。
 */
@Composable
fun ConflictDialog(
    info: ConflictInfo,
    dialogIconMode: Int = 0,
    onDecision: (ConflictPolicy, Boolean) -> Unit,
) {
    // 按「冲突对」重置：两个冲突在一帧内先后到达（对话框没来得及离开组合）时，不应沿用上一个冲突的选择。
    var applyAll by remember(info.sourceName, info.destName) { mutableStateOf(false) }
    // 默认预选项由模型决定：只有「文件 → 文件夹」（替换 = 递归删除整个文件夹）默认「跳过」。
    var policy by remember(info.sourceName, info.destName) { mutableStateOf(info.defaultPolicy()) }
    val verb = if (info.isMove) "移动" else "复制"

    AlertDialog(
        onDismissRequest = { onDecision(ConflictPolicy.SKIP, applyAll) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // MT 的「对话框图标」在冲突框也出现（深浅自适应）；图标换成 MT 的真实矢量
                DialogIcon(MtIcon.ERROR, DialogIconMode.of(dialogIconMode), size = 32.dp)
                Text("文件已存在", modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column {
                Text("源：${info.sourceName}")
                Text("目标：${info.destName}" + if (info.destSize >= 0) "（${Fmt.size(info.destSize)}）" else "")
                Text(
                    info.explanationText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                // 「文件 → 文件夹」的替换有整目录删除后果，选项标签直接写明（与解说文案同源）。
                val overwriteLabel =
                    if (!info.sourceIsDirectory && info.isDirectory) "${verb}并替换（将删除文件夹）" else "${verb}并替换"
                listOf(
                    ConflictPolicy.OVERWRITE to overwriteLabel,
                    ConflictPolicy.SKIP to "跳过该文件",
                    ConflictPolicy.KEEP_BOTH to "${verb}但保留两个文件",
                ).forEach { (p, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { policy = p }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = policy == p, onClick = { policy = p })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("为后续冲突执行相同操作", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDecision(policy, applyAll) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onDecision(ConflictPolicy.SKIP, applyAll) }) { Text("取消") } },
    )
}

/** 输入框（新建 / 重命名 / 路径 / 过滤 / 权限）；[history] 非空时提供 MT 的 `recordKey` 历史下拉 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "名称",
    hint: String? = null,
    history: List<String> = emptyList(),
    /**
     * 是否允许提交**空文本**。
     *
     * 过滤 / 搜索这类「留空 = 清除条件」的输入必须打开它 ——
     * 旧实现一律 `if (text.isNotBlank())` 才提交，导致过滤设上以后**无法取消**
     * （提示写着「留空清除」，实际点了确定没有任何反应）。
     */
    allowEmpty: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 自动聚焦 + 自动弹键盘 + 回车提交（MT 行为）。
    // 旧实现是裸输入框：每个对话框都要再点一下才能打字、打完还得移手指去点「确定」——
    // 而重命名 / 新建 / 跳转恰恰是文件管理器最高频的操作。
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 打开即全选（重命名时直接输入就能覆盖原名，不用先删）
    var field by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initial,
                selection = androidx.compose.ui.text.TextRange(0, initial.length),
            )
        )
    }
    val text = field.text
    val submit = {
        if (allowEmpty || text.isNotBlank()) {
            onConfirm(if (allowEmpty) text.trim() else text.trim())
            onDismiss()
        }
    }
    LaunchedEffect(Unit) {
        // 等对话框窗口完成首帧布局再请求焦点，否则偶发不生效
        kotlinx.coroutines.delay(60)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    HistoryButton(history) {
                        field = androidx.compose.ui.text.input.TextFieldValue(it)
                    }
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { submit() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
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
            TextButton(enabled = allowEmpty || text.isNotBlank(), onClick = submit) { Text("确定") }
        },
        dismissButton = {
            Row {
                // 「清除」只在允许空提交时出现（过滤 / 搜索：一键清空条件）
                if (allowEmpty && initial.isNotBlank()) {
                    TextButton(onClick = { onConfirm(""); onDismiss() }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
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
    val settings by container.settings.collectAsState()
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // MT「对话框图标」设置（0x7f1102f3/2f4/2f5/2f6）：深色 / 浅色 / 无背景
                DialogIcon(MtIcon.INFO, DialogIconMode.of(settings.dialogIconMode))
                Text("属性", modifier = Modifier.padding(start = 10.dp))
            }
        },
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
    /** MT 的真实矢量图标（原先用 emoji/文字符号，观感与字宽都漂移） */
    val icon: MtIcon,
    /** 带 ● ：长按可触发「单窗口操作」 */
    val singleWindow: Boolean = false,
    val enabled: Boolean = true,
    /**
     * 分组：MT 的菜单分「通用动作」与「按文件类型出现的动作」两类，
     * 后者在面板里单独起一行小标题（见 [MtActionSheet]）。
     */
    val section: String? = null,
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
                    MtVectorIcon(
                        icon = MtIcon.CLOSE,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .padding(4.dp),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 6.dp))
                // 两列网格（MT 截图2 布局）；带 section 的项前插一行小标题
                //（按文件类型的二级菜单：压缩包的解压、APK 的安装等）
                var lastSection: String? = null
                actions.chunked(2).forEach { row ->
                    val section = row.firstOrNull()?.section
                    if (section != null && section != lastSection) {
                        Text(
                            section,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    lastSection = section
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
        MtVectorIcon(
            icon = action.icon,
            size = 22.dp,
            tint = if (action.enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            // 校验值 / 路径类结果给「复制」入口（此前只能手选，部分内容无法选中）
            if (message.isNotBlank()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(message))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制") }
            }
        },
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

// ---------------------------------------------------------------------------
// 压缩包口令输入（第 5 批 🔴1：加密包读侧接线 —— 打开 / 解压 / 完整性测试前的输入框）
// ---------------------------------------------------------------------------

@Composable
fun ArchivePasswordDialog(
    archiveName: String,
    wrong: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val submit = { if (field.isNotEmpty()) onConfirm(field) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入压缩包口令") },
        text = {
            Column {
                Text(
                    "「$archiveName」已加密，请输入口令。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (wrong) {
                    Text(
                        "口令不正确或数据已损坏，请重试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { submit() },
                    ),
                    trailingIcon = {
                        Box(Modifier.clickable { show = !show }.padding(horizontal = 10.dp)) {
                            MtVectorIcon(
                                icon = if (show) MtIcon.EYE_OFF else MtIcon.EYE,
                                size = 20.dp,
                                tint = if (show) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = field.isNotEmpty()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 压缩对话框（复刻 MT 0x7f0c0080「创建压缩文件」）
//
// MT 布局：文件名 / 格式（Spinner）/ 压缩级别（Spinner，取自 0x7f030020 数组
// 「仅存储·极速压缩·快速压缩·标准压缩·最大压缩·极限压缩·APK模式」）/ 密码（不加密请留空，👁）
// （MT 的「同时加密文件名」未复刻：commons-compress 1.27.1 无法加密 7z 文件名，见第 5 批 🟡4）
// ---------------------------------------------------------------------------

@Composable
fun MtCompressDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (
        toOther: Boolean,
        format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format,
        fileName: String?,
        level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level,
        password: String?,
    ) -> Unit,
) {
    var format by remember { mutableStateOf(com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP) }
    var level by remember { mutableStateOf(com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL) }
    var fileName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var toOther by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    // F16：打开即聚焦「文件名」（留空自动命名，但想命名可直接打字）
    val fileNameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { fileNameFocus.requestFocus() }
        keyboard?.show()
    }

    val supportsPassword = format.supportsPassword
    val levelApplies = format != com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.TAR &&
        format != com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.TAR_GZ &&
        format != com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.TAR_BZ2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建压缩文件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 保存位置（MT 的「压缩到另一窗口路径」）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保存到：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { toOther = false }) {
                        Text("当前目录", color = if (!toOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { toOther = true }) {
                        Text("另一窗口", color = if (toOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("文件名", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text("留空则按选中项自动命名") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fileNameFocus),
                )
                // 格式
                Text("格式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Box {
                    TextButton(onClick = { formatMenu = true }) { Text(format.label + "  ▾") }
                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(if (f == format) "☑ ${f.label}" else "☐ ${f.label}") },
                                onClick = {
                                    format = f
                                    formatMenu = false
                                    // 切到不支持加密的格式时，连密码一起清掉（否则确定后必报「不支持加密」，且用户看不到密码字段）
                                    if (!f.supportsPassword) {
                                        password = ""
                                    }
                                },
                            )
                        }
                    }
                }
                // 压缩级别
                if (levelApplies) {
                    Text("压缩级别", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Box {
                        TextButton(onClick = { levelMenu = true }) { Text(level.label + "  ▾") }
                        DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.entries.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text(if (l == level) "☑ ${l.label}" else "☐ ${l.label}") },
                                    onClick = { level = l; levelMenu = false },
                                )
                            }
                        }
                    }
                }
                // 密码
                Text("密码（不加密请留空）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = supportsPassword,
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Box(Modifier.clickable { showPassword = !showPassword }.padding(horizontal = 10.dp)) {
                        MtVectorIcon(
                                icon = if (showPassword) MtIcon.EYE_OFF else MtIcon.EYE,
                                size = 20.dp,
                                tint = if (showPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!supportsPassword) {
                    Text(
                        "只有 ZIP / 7z 支持加密；tar 系列无加密能力。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 说明行（第 5 批 🟡4：旧的「同时加密文件名」开关从未生效 —— commons-compress
                // 1.27.1 既不写 7z 头加密、ZIP 也无法隐藏文件名；删除开关，改为如实说明。）
                Text(
                    when {
                        password.isNotEmpty() && format == com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.SEVEN_Z ->
                            "7z 带口令：内容加密；文件名不加密（当前实现限制，无法关闭）。"
                        password.isNotEmpty() && format == com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP ->
                            "ZIP 传统加密无法隐藏文件名，将仅加密内容。"
                        else -> "共 $itemCount 项"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    toOther,
                    format,
                    fileName.trim().takeIf { it.isNotEmpty() },
                    if (levelApplies) level else com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
                    password.takeIf { it.isNotEmpty() },
                )
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 解压对话框（复刻 MT 0x7f0c00ce「解压」）
//
// MT 布局：○ 解压到单独的文件夹 / ○ 解压到当前目录 / ● 解压到文件夹…（默认）+ 路径输入框
// + ☐ 基于另一窗口路径。
// ---------------------------------------------------------------------------

/** 解压目标选项（MT 的三个单选） */
enum class ExtractTarget { OWN_FOLDER, HERE, PICK_FOLDER }

@Composable
fun MtExtractDialog(
    archiveName: String,
    currentDirPath: String,
    otherPanePath: String?,
    onDismiss: () -> Unit,
    onConfirm: (target: ExtractTarget, customPath: String?, useOtherPane: Boolean) -> Unit,
) {
    var target by remember { mutableStateOf(ExtractTarget.PICK_FOLDER) }
    var customPath by remember { mutableStateOf(currentDirPath) }
    // F10：旧实现只写不读（死控件）；现在通过 onConfirm 输出，由调用方决定解压目标
    var useOtherPane by remember { mutableStateOf(false) }
    // F16：打开即聚焦路径框（高频输入不再需要先点一下）
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(target) {
        if (target == ExtractTarget.PICK_FOLDER) {
            kotlinx.coroutines.delay(60)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解压") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    ExtractTarget.OWN_FOLDER to "解压到单独的文件夹（$archiveName）",
                    ExtractTarget.HERE to "解压到当前目录",
                    ExtractTarget.PICK_FOLDER to "解压到文件夹…",
                ).forEach { (t, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { target = t }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == t, onClick = { target = t })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (target == ExtractTarget.PICK_FOLDER) {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("目标路径") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    Text(
                        "· 路径与当前目录相同 → 进入「选择当前目录」模式；不同 → 直接解压到该路径\n" +
                            "· 相对路径会补全为绝对路径（以 / 开头）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (otherPanePath != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val checked = !useOtherPane
                                useOtherPane = checked
                                // 勾选 = 预填另一窗口路径；取消勾选且未被改过 → 还原默认
                                customPath = when {
                                    checked -> otherPanePath
                                    customPath == otherPanePath -> currentDirPath
                                    else -> customPath
                                }
                            }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = useOtherPane,
                            onCheckedChange = { checked ->
                                useOtherPane = checked
                                customPath = when {
                                    checked -> otherPanePath
                                    customPath == otherPanePath -> currentDirPath
                                    else -> customPath
                                }
                            },
                        )
                        Text("基于另一窗口路径（$otherPanePath）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    target,
                    if (target == ExtractTarget.PICK_FOLDER) customPath.trim().takeIf { it.isNotEmpty() } else null,
                    useOtherPane,
                )
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 权限对话框（复刻 MT 0x7f0c0096「权限」）
//
// MT 布局：读/写/执行 × 所有者/用户组/其它 九宫格 + `---- 特殊权限 ----`
// + 设置UID / 设置GID / 粘滞 + ☑ 同时应用到所有子文件 / ☑ 同时应用到所有子文件夹。
// 旧实现只有一个八进制输入框（用户必须自己算位），这里换成勾选式。
// ---------------------------------------------------------------------------

@Composable
fun MtPermissionDialog(
    fileName: String,
    isDirectory: Boolean,
    initialMode: Int?,
    canRecurse: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (mode: Int, recurseFiles: Boolean, recurseDirs: Boolean) -> Unit,
) {
    // 默认 644（文件）/ 755（目录）——与 MT 的常见默认一致
    var mode by remember { mutableStateOf(initialMode ?: if (isDirectory) 0b111_101_101 else 0b110_100_100) }
    var recurseFiles by remember { mutableStateOf(false) }
    var recurseDirs by remember { mutableStateOf(false) }

    fun bit(mask: Int) = mode and mask != 0
    fun setBit(mask: Int, on: Boolean) {
        mode = if (on) mode or mask else mode and mask.inv()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 表头
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("", modifier = Modifier.width(56.dp))
                    listOf("读", "写", "执行").forEach {
                        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    }
                }
                // 三行：所有者 / 用户组 / 其它
                listOf(
                    Triple("所有者", 0b100_000_000, 0b010_000_000),
                    Triple("用户组", 0b000_100_000, 0b000_010_000),
                    Triple("其它", 0b000_000_100, 0b000_000_010),
                ).forEach { (label, readMask, writeMask) ->
                    val execMask = readMask shr 2
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Checkbox(checked = bit(readMask), onCheckedChange = { setBit(readMask, it) }, modifier = Modifier.weight(1f))
                        Checkbox(checked = bit(writeMask), onCheckedChange = { setBit(writeMask, it) }, modifier = Modifier.weight(1f))
                        Checkbox(checked = bit(execMask), onCheckedChange = { setBit(execMask, it) }, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    "---- 特殊权限 ----",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b100_000_000_000), onCheckedChange = { setBit(0b100_000_000_000, it) })
                    Text("设置UID (SUID)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b010_000_000_000), onCheckedChange = { setBit(0b010_000_000_000, it) })
                    Text("设置GID (SGID)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b001_000_000_000), onCheckedChange = { setBit(0b001_000_000_000, it) })
                    Text("粘滞 (Sticky)", style = MaterialTheme.typography.bodySmall)
                }
                if (canRecurse) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recurseFiles, onCheckedChange = { recurseFiles = it })
                        Text("同时应用到所有子文件", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recurseDirs, onCheckedChange = { recurseDirs = it })
                        Text("同时应用到所有子文件夹", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "当前：" + Fmt.modeLong(mode, isDirectory, false) + "(" + Integer.toOctalString(mode and 0xFFF) + ")",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode, recurseFiles, recurseDirs) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
