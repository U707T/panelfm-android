package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.HistoryButton

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

/**
 * 「标签 + 值」一行 —— 全仓对话框**唯一**实现（属性 / APK 信息 / APK 详情 / 各类结果框共用）。
 *
 * 以前有三份副本（这里一份、APK 信息整页一份、APK 信息卡片一份），字号与列宽各写各的，
 * 同一种信息在不同对话框里长得不一样。要改版式只改这里：
 *
 * @param labelWidth 标签列宽（短标签 72dp 够用；APK 卡片用 84dp 才不挤）
 * @param labelStyle / valueStyle 字号档位（卡片 13sp 紧凑档，结果框 bodySmall）
 * @param maxLines 值最多几行（[Int.MAX_VALUE] = 不限；卡片给 1 行 + 省略号，整行可点复制）
 * @param verticalPadding 行间距
 * @param modifier 行级修饰符（卡片用它挂「点击复制」）
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    labelWidth: Dp = 72.dp,
    labelStyle: TextStyle = MaterialTheme.typography.bodySmall,
    valueStyle: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE,
    verticalPadding: Dp = 3.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            value,
            style = valueStyle,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}
