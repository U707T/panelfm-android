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
 * MT 的「搜索」对话框：
 * 文件名输入框 + ☐搜索子目录 + 高级搜索（按内容搜索 / 文件大小范围）。
 * 文件名语法与过滤一致：普通文本=包含，`!文本`=否定，`/正则`，`!/正则`。
 */
@Composable
fun MtSearchDialog(
    initialName: String,
    onSearch: (name: String, recursive: Boolean, content: String, minSize: Long, maxSize: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var recursive by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    var minSizeText by remember { mutableStateOf("") }
    var maxSizeText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件名") },
                    placeholder = { Text("支持 !否定 与 /正则") },
                    singleLine = true,
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
                    Text("搜索子目录（递归）", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "收起高级搜索 ⌃" else "高级搜索 ⌄")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("按内容搜索（可选）") },
                        placeholder = { Text("仅匹配 ≤2MB 的文本类文件") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                enabled = name.isNotBlank() || content.isNotBlank(),
                onClick = {
                    onSearch(
                        name.trim(),
                        recursive,
                        content.trim(),
                        parseSizeText(minSizeText),
                        parseSizeText(maxSizeText),
                    )
                    onDismiss()
                },
            ) { Text("搜索") }
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
