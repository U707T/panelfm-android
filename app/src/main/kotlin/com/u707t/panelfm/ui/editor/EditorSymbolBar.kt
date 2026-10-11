package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 符号输入栏（NP `menu_symbol_input` + `symbol_input_view`；MT 也有同位置的符号键盘）。
 *
 * 形态与 NP 一致：**底部一条可横滑的窄栏**（36dp、12sp 小按钮），点击把符号插到光标处。
 * 实现上没有直接用 sora 的 `SymbolInputView`（它的按钮是 view 层实例、绑定时机与
 * Compose 的组合顺序耦合），改为 Compose 版——插入动作仍走编辑器的 `insertText`，
 * `⇥` 特判走 `indentOrCommitTab()`（与键盘 Tab 键 / sora 原生实现完全一致）。
 */
@Composable
internal fun EditorSymbolBar(
    enabled: Boolean,
    textColor: Color,
    background: Color,
    onSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(background)
            .height(36.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EDITOR_SYMBOLS.forEach { (label, value) ->
            Box(
                Modifier
                    .widthIn(min = 34.dp)
                    .height(36.dp)
                    .clickable(enabled = enabled) { onSymbol(value) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                )
            }
        }
    }
}
