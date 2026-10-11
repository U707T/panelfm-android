package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor

/** 「语法」对话框：自动识别 + 全部可选语言（单选观感）。 */
@Composable
internal fun EditorLanguageDialog(
    selectedScope: String?,
    autoScope: String?,
    manual: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语法") },
        text = {
            ChoiceList {
                // 自动识别（按扩展名）—— 当前文件后缀对应的语言
                EditorChoiceRow(
                    label = "自动识别" + (EditorLanguages.labelOf(autoScope)?.let { "（$it）" } ?: "（纯文本）"),
                    selected = !manual,
                ) { onPick(null) }
                EditorLanguages.selectable.forEach { (scope, label) ->
                    EditorChoiceRow(
                        label = label,
                        selected = manual && selectedScope == scope.ifEmpty { null },
                    ) { onPick(scope) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 「指定编码重新加载 / 指定保存的编码 / 配色风格 / 字体大小」共用的一行式选项对话框
 * （单选观感，可滚动）。[options] = 值 to 显示名。
 */
@Composable
internal fun EditorOptionDialog(
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                ChoiceList {
                    options.forEach { (value, label) ->
                        EditorChoiceRow(label = label, selected = value == selected) { onPick(value) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 「转到指定行」对话框。 */
@Composable
internal fun EditorGotoLineDialog(
    text: String,
    onTextChange: (String) -> Unit,
    lineTotal: Int,
    editor: CodeEditor?,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转到指定行") },
        text = {
            Column {
                EditorField(text, onTextChange, "行号", Modifier.fillMaxWidth())
                Text(
                    "共 $lineTotal 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = text.trim().toIntOrNull()
                if (target == null || target < 1 || editor == null) {
                    onStatus("请输入有效的行号")
                } else if (target > editor.lineCount) {
                    onStatus("行号超出范围（共 ${editor.lineCount} 行）")
                } else {
                    editor.setSelection(target - 1, 0, true)
                    onStatus("已定位到第 $target 行")
                }
                onDismiss()
            }) { Text("转到") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 「有未保存修改」对话框（返回拦截）。 */
@Composable
internal fun EditorDiscardDialog(
    fileName: String,
    onSaveAndLeave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有未保存的修改") },
        text = { Text("「$fileName」已修改但未保存，直接返回会丢失这些修改。") },
        confirmButton = { TextButton(onClick = onSaveAndLeave) { Text("保存并返回") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("放弃修改") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 通用两按钮确认框（重新加载会丢修改 / 覆盖外部已修改的文件…）。
 * 文案由调用方给全，这里不猜。
 */
@Composable
internal fun EditorConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 可滚动的选择列表（语言 / 编码 / 字号共用；行数多时不把对话框撑破）。 */
@Composable
private fun ChoiceList(content: @Composable () -> Unit) {
    Column(
        Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/** 对话框里的一行（单选观感）。 */
@Composable
private fun EditorChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "◉  $label" else "○  $label",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
