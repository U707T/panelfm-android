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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata

/**
 * MT 的「搜索」对话框（截图复刻）：
 *  - 标题右侧 🕘 = 搜索历史；输入框右侧 ▾ 也可调出历史
 *  - 搜索类型下拉：文件名包含的文本 / 文件名匹配正则 / 文件内容包含的文本
 *  - ☐搜索子目录（递归）+ ☐高级搜索（文件大小范围）
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
    var historyMenu by remember { mutableStateOf(false) }
    var fieldHistoryMenu by remember { mutableStateOf(false) }

    @Composable
    fun HistoryMenu(expanded: Boolean, close: () -> Unit) {
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            if (history.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("（暂无搜索历史）", style = MaterialTheme.typography.labelSmall) },
                    onClick = close,
                    enabled = false,
                )
            } else {
                history.forEach { q ->
                    DropdownMenuItem(
                        text = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { query = q; close() },
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("搜索", modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { historyMenu = true }) {
                        Text("🕘", style = MaterialTheme.typography.titleMedium)
                    }
                    HistoryMenu(historyMenu) { historyMenu = false }
                }
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
                    trailingIcon = {
                        Box {
                            Box(Modifier.clickable { fieldHistoryMenu = true }.padding(horizontal = 10.dp)) {
                                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HistoryMenu(fieldHistoryMenu) { fieldHistoryMenu = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                onClick = {
                    onSearch(
                        query.trim(),
                        field,
                        recursive,
                        parseSizeText(minSizeText),
                        parseSizeText(maxSizeText),
                    )
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 搜索结果列表（递归/高级搜索）：点击结果 = 跳转到所在目录并选中（MT 语义） */
@Composable
fun MtSearchResultsDialog(
    results: List<FileMetadata>,
    searching: Boolean,
    onPick: (FileMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (searching) "搜索中…" else "搜索结果（${results.size}）") },
        text = {
            Box(Modifier.height(360.dp)) {
                when {
                    searching -> LoadingState("正在递归搜索（最多显示 300 条）…")
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
                                        (item.uri.parent?.displayPath ?: "/") + "  ·  " + Fmt.size(item.size),
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
