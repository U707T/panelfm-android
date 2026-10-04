package com.u707t.panelfm.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * MT 的「带历史记录的输入框」（`app:recordKey`）。
 *
 * MT 的自定义输入控件带 `recordKey` 属性，**自动记住历史值**，点输入框右侧的 ▾ 可下拉选择
 * （见附录 G.11.2，17 个键：`file_search_record` / `filter_record` / `rename_multi_pattern` …）。
 *
 * 这里把该行为抽成一个通用组件：
 *  - [history] 由调用方从 `PrefsStore.inputHistory[key]` 取（最近在前）
 *  - 调用方在「确定 / 执行」时写历史（`PrefsStore.addInputHistory(key, value)`）
 *  - 历史为空时 ▾ 仍显示（点了给「暂无历史」提示），与 MT 观感一致
 */
@Composable
fun HistoryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    history: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClearHistory: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            enabled = enabled,
            trailingIcon = {
                Row {
                    trailing?.invoke()
                    // MT：输入框右侧的 ▾ = 调出历史
                    Box(
                        Modifier
                            .clickable(enabled = enabled) { menu = true }
                            .padding(horizontal = 10.dp),
                    ) {
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (history.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("（暂无历史）", style = MaterialTheme.typography.labelSmall) },
                                onClick = { menu = false },
                                enabled = false,
                            )
                        } else {
                            history.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { onValueChange(h); menu = false },
                                )
                            }
                            if (onClearHistory != null) {
                                DropdownMenuItem(
                                    text = { Text("清空历史", style = MaterialTheme.typography.labelSmall) },
                                    onClick = { onClearHistory(); menu = false },
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 历史下拉按钮（复刻 MT 搜索框右上角的 🕘 图标位） */
@Composable
fun HistoryButton(history: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { menu = true }) {
            Text("🕘", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (history.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("（暂无历史）", style = MaterialTheme.typography.labelSmall) },
                    onClick = { menu = false },
                    enabled = false,
                )
            } else {
                history.forEach { h ->
                    DropdownMenuItem(
                        text = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onPick(h); menu = false },
                    )
                }
            }
        }
    }
}
