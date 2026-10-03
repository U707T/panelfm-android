package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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

    fun newName(expression: String, item: FileMetadata, index: Int): String {
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
        val regex = Regex("""\{(z?)(\d+)}""")
        result = regex.replace(result) { m ->
            val zeroPad = m.groupValues[1] == "z"
            val start = m.groupValues[2].toIntOrNull() ?: 0
            val value = start + index
            if (zeroPad) value.toString().padStart(m.groupValues[2].length, '0') else value.toString()
        }
        return result
    }

    fun preview(items: List<FileMetadata>, expression: String, limit: Int = 8): List<Pair<String, String>> =
        items.take(limit).mapIndexed { i, item -> item.name to newName(expression, item, i) }

    fun hasConflict(items: List<FileMetadata>, expression: String): Boolean {
        val names = items.mapIndexed { i, item -> newName(expression, item, i) }
        return names.size != names.distinct().size
    }
}

@Composable
fun BatchRenameDialog(
    items: List<FileMetadata>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expression by remember { mutableStateOf("{P}{S}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名（${items.size} 项）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it },
                    label = { Text("表达式") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "{P} 文件名 · {S} 后缀 · {T} 修改时间 · {N} 从 N 递增 · {zN} 补零序号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                BatchRename.preview(items, expression).forEach { (old, new) ->
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
                if (BatchRename.hasConflict(items, expression)) {
                    Text(
                        "注意：表达式会产生重名，执行时同名的会被跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(expression); onDismiss() }) { Text("执行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
