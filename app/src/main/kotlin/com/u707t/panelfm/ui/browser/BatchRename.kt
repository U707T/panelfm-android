package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.HistoryButton
import com.u707t.panelfm.core.vfs.FileMetadata
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 批量重命名（MT 表达式语法）：
 *  - `{P}` 文件名前缀（abc.txt → abc）  · `{S}` 后缀（abc.txt → .txt）
 *  - `{T}` 文件修改时间（yyyyMMdd_HHmmss）
 *  - `{N}` 从 N 递增的序号（如 {1}）  · `{zN}` 补零序号（{z8} → 08、09、10）
 * 默认表达式 `{P}{S}` = 原文件名不变。
 */
object BatchRename {

    private val timeFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * MT 0x7f0c0099 的完整语义：先按表达式改名，再做「查找内容 → 替换内容」。
     * 「不使用替换功能请将查找内容留空」（MT 原文）。
     */
    fun newName(
        expression: String,
        item: FileMetadata,
        index: Int,
        find: String = "",
        replace: String = "",
        useRegex: Boolean = false,
    ): String {
        val base = newNameByExpression(expression, item, index)
        if (find.isEmpty()) return base
        return runCatching {
            if (useRegex) Regex(find).replace(base, replace) else base.replace(find, replace)
        }.getOrDefault(base)
    }

    private fun newNameByExpression(expression: String, item: FileMetadata, index: Int): String {
        val dot = item.name.lastIndexOf('.')
        val prefix = if (dot > 0) item.name.substring(0, dot) else item.name
        val suffix = if (dot > 0) item.name.substring(dot) else ""
        val time = if (item.lastModified > 0) {
            Instant.ofEpochMilli(item.lastModified).atZone(ZoneId.systemDefault()).format(timeFmt)
        } else {
            Instant.now().atZone(ZoneId.systemDefault()).format(timeFmt)
        }
        var result = expression.ifBlank { "{P}{S}" }
            .replace("{P}", prefix)
            .replace("{S}", suffix)
            .replace("{T}", time)

        // {N} / {zN}
        // MT 文档（0x7f1105bc）：「{zN}：与 {N} 类似，区别是会进行补 0 对齐，
        // 例如 {z8} 重命名会得到 08、09、10、11…」——即补零宽度**至少 2 位**，
        // 不是「起始数字的位数」（旧实现 {z8} 会得到 8，与 MT 不符）。
        val regex = Regex("""\{(z?)(\d+)}""")
        result = regex.replace(result) { m ->
            val zeroPad = m.groupValues[1] == "z"
            val digits = m.groupValues[2]
            val start = digits.toIntOrNull() ?: 0
            val value = start + index
            if (zeroPad) value.toString().padStart(maxOf(2, digits.length), '0') else value.toString()
        }
        return result
    }

    fun preview(
        items: List<FileMetadata>,
        expression: String,
        limit: Int = 8,
        find: String = "",
        replace: String = "",
        useRegex: Boolean = false,
    ): List<Pair<String, String>> = items.take(limit).mapIndexed { i, item ->
        item.name to newName(expression, item, i, find, replace, useRegex)
    }

    fun hasConflict(
        items: List<FileMetadata>,
        expression: String,
        find: String = "",
        replace: String = "",
        useRegex: Boolean = false,
    ): Boolean {
        val names = items.mapIndexed { i, item -> newName(expression, item, i, find, replace, useRegex) }
        return names.size != names.distinct().size
    }
}

@Composable
fun BatchRenameDialog(
    items: List<FileMetadata>,
    patternHistory: List<String> = emptyList(),
    findHistory: List<String> = emptyList(),
    replaceHistory: List<String> = emptyList(),
    onConfirm: (expression: String, find: String, replace: String, useRegex: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var expression by remember { mutableStateOf("{P}{S}") }
    // MT 0x7f0c0099：表达式 + 查找内容 / 替换内容 双列 + ☐ 使用正则表达式查找替换
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    // F16：打开即聚焦「命名表达式」
    val expressionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { expressionFocus.requestFocus() }
        keyboard?.show()
    }
    val regexError = useRegex && find.isNotEmpty() &&
        runCatching { Regex(find) }.isFailure

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名（${items.size} 项）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // MT `app:recordKey`：表达式 / 查找 / 替换 三处都带历史下拉
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = expression,
                        onValueChange = { expression = it },
                        label = { Text("命名表达式") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(expressionFocus),
                    )
                    if (patternHistory.isNotEmpty()) HistoryButton(patternHistory) { expression = it }
                }
                Text(
                    "{P} 文件名 · {S} 后缀 · {T} 修改时间 · {N} 从 N 递增 · {zN} 补零序号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                // 查找 / 替换（MT：双列并排）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = find,
                        onValueChange = { find = it },
                        label = { Text("查找内容") },
                        singleLine = true,
                        isError = regexError,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = replace,
                        onValueChange = { replace = it },
                        label = { Text("替换内容") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (findHistory.isNotEmpty()) HistoryButton(findHistory) { find = it }
                    if (replaceHistory.isNotEmpty()) HistoryButton(replaceHistory) { replace = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRegex, onCheckedChange = { useRegex = it })
                    Text("使用正则表达式查找替换", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (find.isEmpty()) "不使用替换功能请将查找内容留空" else "先按表达式改名，再执行查找替换",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (regexError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 实时预览
                BatchRename.preview(items, expression, find = find, replace = replace, useRegex = useRegex).forEach { (old, new) ->
                    Text(
                        "$old  →  $new",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                if (items.size > 8) {
                    Text("… 共 ${items.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (BatchRename.hasConflict(items, expression, find, replace, useRegex)) {
                    Text(
                        "注意：表达式会产生重名，执行时同名的会被跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !regexError,
                onClick = { onConfirm(expression, find, replace, useRegex); onDismiss() },
            ) { Text("执行") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
