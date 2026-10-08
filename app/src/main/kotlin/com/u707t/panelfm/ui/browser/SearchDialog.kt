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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val submit = {
        onSearch(
            query.trim(),
            field,
            recursive,
            parseSizeText(minSizeText),
            parseSizeText(maxSizeText),
        )
        onDismiss()
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
                    DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                        SearchField.entries.forEach { f ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        f.label,
                                        color = if (f == field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = { field = f; fieldMenu = false },
                            )
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
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.padding(horizontal = 4.dp))
                        OutlinedTextField(
                            value = maxSizeText,
                            onValueChange = { maxSizeText = it },
                            label = { Text("最大大小") },
                            placeholder = { Text("如 10M") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "大小支持 K / M / G 后缀；留空表示不限制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = query.isNotBlank(),
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
                    Text(
                        "搜索结果数量过多，已停止搜索（显示前 ${results.size} 条）",
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

/** 解析「512K / 10M / 1G / 4096」→ 字节；-1 = 不限制 */
internal fun parseSizeText(input: String): Long {
    val t = input.trim().uppercase()
    if (t.isEmpty()) return -1
    val (num, mul) = when {
        t.endsWith("G") -> t.dropLast(1) to (1024L * 1024 * 1024)
        t.endsWith("M") -> t.dropLast(1) to (1024L * 1024)
        t.endsWith("K") -> t.dropLast(1) to 1024L
        else -> t to 1L
    }
    return num.toDoubleOrNull()?.let { (it * mul).toLong() } ?: -1
}
