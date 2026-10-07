package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.LineOps
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon

/**
 * 编辑器 ⋮ 菜单（复刻 MT 0x7f0e001b：撤销 / 重做 + 行操作 + 代码整理）。
 *
 * - 每一项点击都会先调 `onDismiss` 收起菜单，再执行动作（回调都是纯动作）；
 * - 只读（大文件分段）时禁用全部会改正文的项；「复制行」只写剪贴板，保持可用。
 */
@Composable
internal fun EditorMenu(
    expanded: Boolean,
    languageLabel: String,
    languageManual: Boolean,
    canToggleComment: Boolean,
    canFormat: Boolean,
    wordwrap: Boolean,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onLanguage: () -> Unit,
    onToggleWordwrap: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onCopyLine: () -> Unit,
    onCutLine: () -> Unit,
    onLineOp: (String, (String, Int) -> String) -> Unit,
    onToggleComment: () -> Unit,
    onCompress: () -> Unit,
    onFormat: () -> Unit,
    onGotoLine: () -> Unit,
    onToggleFind: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("💾  保存") }, onClick = { onDismiss(); onSave() }, enabled = !readOnly)
        DropdownMenuItem(text = { Text("↶  撤销") }, onClick = { onDismiss(); onUndo() }, enabled = !readOnly)
        DropdownMenuItem(text = { Text("↷  重做") }, onClick = { onDismiss(); onRedo() }, enabled = !readOnly)
        DropdownMenuItem(text = { Text("🔍  查找 / 替换") }, onClick = { onDismiss(); onToggleFind() })
        DropdownMenuItem(text = { Text("↧  转到指定行…") }, onClick = { onDismiss(); onGotoLine() })
        Text(
            "视图",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        DropdownMenuItem(
            text = { Text("⌨  语法：$languageLabel" + if (languageManual) "（手动）" else "（自动）") },
            onClick = { onDismiss(); onLanguage() },
        )
        DropdownMenuItem(
            text = { Text((if (wordwrap) "✓  " else "    ") + "自动换行") },
            onClick = { onDismiss(); onToggleWordwrap() },
        )
        Text(
            "行操作",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        // 复制行 = 复制当前行到剪贴板（不改正文）；剪切行 = 复制 + 删除（与 MT 的 Copy / Cut line 对齐）
        DropdownMenuItem(text = { Text("⧉  复制行") }, onClick = { onDismiss(); onCopyLine() })
        DropdownMenuItem(text = { Text("✂  剪切行") }, onClick = { onDismiss(); onCutLine() }, enabled = !readOnly)
        DropdownMenuItem(
            text = { Text("🗑  删除行") },
            onClick = { onDismiss(); onLineOp("删除行") { t, i -> LineOps.deleteLine(t, i) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("␣  清空行") },
            onClick = { onDismiss(); onLineOp("清空行") { t, i -> LineOps.clearLine(t, i) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("⇊  重复行") },
            onClick = { onDismiss(); onLineOp("重复行") { t, i -> LineOps.duplicateLine(t, i) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("🅰  转为大写") },
            onClick = { onDismiss(); onLineOp("转为大写") { t, _ -> LineOps.toUpperCase(t) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("🅰  转为小写") },
            onClick = { onDismiss(); onLineOp("转为小写") { t, _ -> LineOps.toLowerCase(t) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("→|  增加缩进") },
            onClick = { onDismiss(); onLineOp("增加缩进") { t, _ -> LineOps.indent(t) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("|←  减小缩进") },
            onClick = { onDismiss(); onLineOp("减小缩进") { t, _ -> LineOps.unindent(t) } },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("//  切换注释" + if (canToggleComment) "" else "（当前语言不支持）") },
            onClick = { onDismiss(); onToggleComment() },
            enabled = canToggleComment && !readOnly,
        )
        Text(
            "代码整理",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        DropdownMenuItem(
            text = { Text("🗜  压缩代码（去空白）") },
            onClick = { onDismiss(); onCompress() },
            enabled = !readOnly,
        )
        DropdownMenuItem(
            text = { Text("✨  格式化代码" + if (canFormat) "" else "（仅 JSON / XML）") },
            onClick = { onDismiss(); onFormat() },
            enabled = canFormat && !readOnly,
        )
    }
}

/** 分页控制条上的小按钮。 */
@Composable
internal fun PageButton(icon: MtIcon, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(
            icon = icon,
            size = 20.dp,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}
