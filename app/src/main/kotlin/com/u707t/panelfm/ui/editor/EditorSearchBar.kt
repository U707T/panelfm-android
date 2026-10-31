package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.ClaudeDropdownMenu
import com.u707t.panelfm.core.ui.ClaudeMenuItem

/** 查找条上的三个选项（对应 sora searcher 的「正则 / 大小写 / 全词」）。 */
internal data class EditorSearchOptionsState(
    val regex: Boolean,
    val matchCase: Boolean,
    val wholeWord: Boolean,
)

/** 打开查找条时把焦点落在哪一行（NP：菜单里「搜索」一项；本项目分「查找」与「查找 / 替换」两项）。 */
internal enum class EditorSearchFocus { FIND, REPLACE }

/** MT 风格底部查找条：输入区在上，操作区固定为「上个 / 下个 / 替换 / 全部 / ⋮」。 */
@Composable
internal fun EditorSearchBar(
    findText: String,
    onFindTextChange: (String) -> Unit,
    replaceText: String,
    onReplaceTextChange: (String) -> Unit,
    findHistory: List<String>,
    replaceHistory: List<String>,
    options: EditorSearchOptionsState,
    optionsMenuExpanded: Boolean,
    onOptionsMenuExpandedChange: (Boolean) -> Unit,
    onToggleRegex: (Boolean) -> Unit,
    onToggleMatchCase: (Boolean) -> Unit,
    onToggleWholeWord: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    focusTarget: EditorSearchFocus = EditorSearchFocus.FIND,
) {
    val findFocusRequester = remember { FocusRequester() }
    val replaceFocusRequester = remember { FocusRequester() }
    LaunchedEffect(focusTarget) {
        runCatching {
            when (focusTarget) {
                EditorSearchFocus.FIND -> findFocusRequester.requestFocus()
                EditorSearchFocus.REPLACE -> replaceFocusRequester.requestFocus()
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("查找", modifier = Modifier.padding(end = 5.dp))
            EditorField(
                value = findText,
                onChange = onFindTextChange,
                hint = "",
                history = findHistory,
                historyEnabled = true,
                enabled = true,
                focusRequester = findFocusRequester,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("替换", modifier = Modifier.padding(end = 5.dp))
            EditorField(
                value = replaceText,
                onChange = onReplaceTextChange,
                hint = "",
                history = replaceHistory,
                historyEnabled = true,
                enabled = true,
                focusRequester = replaceFocusRequester,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchActionButton("上个", enabled = findText.isNotEmpty(), onClick = onPrevious)
            SearchActionButton("下个", enabled = findText.isNotEmpty(), onClick = onNext)
            SearchActionButton("替换", enabled = findText.isNotEmpty(), onClick = onReplace)
            SearchActionButton("全部", enabled = findText.isNotEmpty(), onClick = onReplaceAll)
            SearchOptionsMenu(
                expanded = optionsMenuExpanded,
                options = options,
                onExpandedChange = onOptionsMenuExpandedChange,
                onToggleRegex = onToggleRegex,
                onToggleMatchCase = onToggleMatchCase,
                onToggleWholeWord = onToggleWholeWord,
            )
        }
    }
}

@Composable
private fun RowScope.SearchActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    ) {
        Text(label)
    }
}

/** 正则 / 大小写 / 全词收进右侧 ⋮ 菜单（对应 MT 查找条的 ⋮ 位；菜单内容 MT 侧静态不可判定，
 *  此处为「不占常驻行」的等价收纳）。 */
@Composable
private fun SearchOptionsMenu(
    expanded: Boolean,
    options: EditorSearchOptionsState,
    onExpandedChange: (Boolean) -> Unit,
    onToggleRegex: (Boolean) -> Unit,
    onToggleMatchCase: (Boolean) -> Unit,
    onToggleWholeWord: (Boolean) -> Unit,
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text("⋮", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        ClaudeDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SearchOptionItem("正则表达式", options.regex) {
                onToggleRegex(!options.regex)
                onExpandedChange(false)
            }
            SearchOptionItem("区分大小写", options.matchCase) {
                onToggleMatchCase(!options.matchCase)
                onExpandedChange(false)
            }
            SearchOptionItem("全词匹配", options.wholeWord) {
                onToggleWholeWord(!options.wholeWord)
                onExpandedChange(false)
            }
        }
    }
}

@Composable
private fun SearchOptionItem(label: String, checked: Boolean, onClick: () -> Unit) {
    ClaudeMenuItem(
        label = label,
        checked = checked,
        onClick = onClick,
    )
}

/** 小号输入框；查找条使用 MT 风格的右侧 ▾ 历史下拉，而不是额外占一列的按钮。 */
@Composable
internal fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    history: List<String> = emptyList(),
    historyEnabled: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    var historyMenu by remember { mutableStateOf(false) }
    Row(
        modifier.background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            },
            modifier = Modifier
                .weight(1f)
                .padding(6.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
        if (historyEnabled) {
            Box {
                TextButton(enabled = enabled, onClick = { historyMenu = true }) {
                    Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ClaudeDropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                    if (history.isEmpty()) {
                        ClaudeMenuItem(
                            label = "（暂无历史）",
                            enabled = false,
                        ) { historyMenu = false }
                    } else {
                        history.forEach { item ->
                            ClaudeMenuItem(
                                label = item,
                            ) { onChange(item); historyMenu = false }
                        }
                    }
                }
            }
        }
    }
}
