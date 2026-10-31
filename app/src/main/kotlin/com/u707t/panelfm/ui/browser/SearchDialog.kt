package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.FileSearch
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.HistoryButton
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.ClaudeMenuItem

/**
 * MT 的「搜索」对话框（截图复刻）：
 *  - 标题右侧 🕘 = 搜索历史；输入框右侧 ▾ 也可调出历史
 *  - 搜索类型下拉：文件名包含的文本 / 文件名匹配正则 / 文件内容包含的文本
 *  - ☐搜索子目录（递归）+ ☐高级搜索（文件大小范围）
 *  - **本轮补 MT 的条数上限反馈**（`0x7f110430` / `0x7f110620` / `0x7f110686` / `0x7f110628`）
 */
enum class SearchField(val label: String) {
    NAME("文件名包含的文本"),
    REGEX("文件名匹配正则"),
    CONTENT("文件内容包含的文本"),
}

@Composable
fun MtSearchDialog(
    initialQuery: String,
    history: List<String>,
    onSearch: (query: String, field: SearchField, recursive: Boolean, minSize: Long, maxSize: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf(SearchField.NAME) }
    var query by remember { mutableStateOf(initialQuery) }
    var recursive by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var minSizeText by remember { mutableStateOf("") }
    var maxSizeText by remember { mutableStateOf("") }
    var fieldMenu by remember { mutableStateOf(false) }
    // F16：打开即聚焦输入框（搜索是最高频入口之一；U3 只修了 TextInputDialog 一族）
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // 大小条件非法时不能静默当成「不限制」：禁掉确定并在输入框下说明（旧实现 parseSizeText 返回 -1）
    val minBytes = parseSizeText(minSizeText)
    val maxBytes = parseSizeText(maxSizeText)
    val minSizeBad = advanced && minSizeText.isNotBlank() && minBytes == null
    val maxSizeBad = advanced && maxSizeText.isNotBlank() && maxBytes == null
    // 上下界都可解析时再校验区间：min > max 是「永远搜不到」，不能当成一次正常搜索
    val sizeRangeBad = advanced && minBytes != null && maxBytes != null &&
        minBytes >= 0 && maxBytes >= 0 && minBytes > maxBytes
    val sizeBad = minSizeBad || maxSizeBad || sizeRangeBad
    val submit = {
        if (!sizeBad) {
            onSearch(
                query.trim(),
                field,
                recursive,
                minBytes ?: -1L,
                maxBytes ?: -1L,
            )
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("搜索", modifier = Modifier.weight(1f))
                HistoryButton(history) { query = it }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 搜索类型下拉（MT：「文件名包含的文本 ▾」）
                Box {
                    TextButton(onClick = { fieldMenu = true }) {
                        Text(field.label + "  ▾", color = MaterialTheme.colorScheme.onSurface)
                    }
                    ClaudeDropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                        SearchField.entries.forEach { f ->
                            ClaudeMenuItem(
                                label = f.label,
                                checked = f == field,
                            ) { field = f; fieldMenu = false }
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = {
                        Text(
                            when (field) {
                                SearchField.NAME -> "文件名"
                                SearchField.REGEX -> "正则表达式"
                                SearchField.CONTENT -> "文件内容"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { if (query.isNotBlank()) submit() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { recursive = !recursive }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                    Text("搜索子目录", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { advanced = !advanced }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = advanced, onCheckedChange = { advanced = it })
                    Text("高级搜索", style = MaterialTheme.typography.bodyMedium)
                }
                if (advanced) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = minSizeText,
                            onValueChange = { minSizeText = it },
                            label = { Text("最小大小") },
                            placeholder = { Text("如 512K") },
                            singleLine = true,
                            isError = minSizeBad || sizeRangeBad,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.padding(horizontal = 4.dp))
                        OutlinedTextField(
                            value = maxSizeText,
                            onValueChange = { maxSizeText = it },
                            label = { Text("最大大小") },
                            placeholder = { Text("如 10M") },
                            singleLine = true,
                            isError = maxSizeBad || sizeRangeBad,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        when {
                            sizeRangeBad -> "最小值不能大于最大值"
                            sizeBad -> "大小格式不正确：示例 512K / 1.5MB / 2G；留空表示不限制"
                            else -> "大小支持 K / M / G（可写 512K / 1.5MB）；留空表示不限制"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sizeBad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = query.isNotBlank() && !sizeBad,
                onClick = { submit() },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 搜索结果列表（MT 语义）：
 *  - 标题「搜索结果(%d)」（`0x7f11061f`），搜索中显示实时条数
 *  - **「停止搜索」**（`0x7f110686`）：搜索中可中断
 *  - **「二次搜索 / 在当前结果中搜索」**（`0x7f110628` / `0x7f110619`）：在结果里再筛
 *  - 结果过多被停止时提示「搜索结果数量过多，已停止搜索」（`0x7f110620`）
 *  - 结果行显示**相对搜索起点**的路径（就在起点时显示「当前目录」）
 */
@Composable
fun MtSearchResultsDialog(
    results: List<FileMetadata>,
    searching: Boolean,
    stopped: Boolean = false,
    /** 子目录列举失败数（网络 / 权限）；0 = 全部目录都读到了 */
    failedDirs: Int = 0,
    /** 内容读取失败的文件数；与「未命中」区分显示 */
    contentFailures: Int = 0,
    /** 本次搜索的起点路径（显示相对路径用）；null = 显示完整父路径 */
    rootPath: String? = null,
    onStop: () -> Unit = {},
    onRefine: () -> Unit = {},
    onClear: () -> Unit = {},
    onPick: (FileMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (searching) "搜索中…（已找到 ${results.size}）" else "搜索结果(${results.size})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        // MT 0x7f110686「停止搜索」
                        TextButton(onClick = onStop) { Text("停止搜索") }
                    }
                    if (results.isNotEmpty()) {
                        // MT 0x7f110628「二次搜索」/ 0x7f110619「在当前结果中搜索」
                        TextButton(onClick = onRefine) { Text("在当前结果中搜索") }
                        TextButton(onClick = onClear) { Text("清除搜索") }
                    }
                }
                if (stopped && !searching) {
                    // MT 0x7f110620「搜索结果数量过多，已停止搜索」
                    // 用户主动点「停止」时不该说成「数量过多」：只有达到 300 条才用这条文案
                    Text(
                        if (results.size >= 300) "搜索结果数量过多，已停止搜索（显示前 ${results.size} 条）"
                        else "搜索已停止（显示前 ${results.size} 条）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 失败必须可见：旧实现把 list / 内容读取的错误全吞成「空目录 / 未命中」
                if (!searching && (failedDirs > 0 || contentFailures > 0)) {
                    Text(
                        buildString {
                            if (failedDirs > 0) append("$failedDirs 个目录无法读取（网络或权限）")
                            if (contentFailures > 0) {
                                if (isNotEmpty()) append("；")
                                append("$contentFailures 个文件内容读取失败")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Box(Modifier.height(320.dp)) {
                    when {
                        searching && results.isEmpty() -> LoadingState("正在递归搜索…")
                        results.isEmpty() -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("没有匹配的项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> LazyColumn {
                            items(results, key = { it.uri.toString() }) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(item) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FileIcon(name = item.name, isDirectory = item.isDirectory, size = 34.dp)
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            run {
                                                val parent = item.uri.parent?.displayPath ?: "/"
                                                val shown = rootPath?.let { FileSearch.relativeParent(it, parent) } ?: parent
                                                "$shown  ·  ${Fmt.size(item.size)}"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 解析「512K / 10M / 1.5GB / 4096」→ 字节；空串 = -1（不限制）；格式错误 = null。
 *
 * 旧实现把非法输入（10KB、-5）也返回 -1，与「不限制」无法区分：用户以为加了条件，
 * 实际搜的是全部文件。现在由对话框把 null 显示成错误并禁用「确定」。
 */
internal fun parseSizeText(input: String): Long? {
    val raw = input.trim()
    if (raw.isEmpty()) return -1L
    // 允许 K / KB / M / MB / G / GB（B 剥掉即可；单独一个 B 仍算非法）
    val t = raw.uppercase().removeSuffix("B")
    if (t.isEmpty()) return null
    val (num, mul) = when {
        t.endsWith("G") -> t.dropLast(1) to (1024L * 1024 * 1024)
        t.endsWith("M") -> t.dropLast(1) to (1024L * 1024)
        t.endsWith("K") -> t.dropLast(1) to 1024L
        else -> t to 1L
    }
    val value = num.trim().toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0) return null
    return (value * mul).toLong().coerceAtLeast(0L)
}
