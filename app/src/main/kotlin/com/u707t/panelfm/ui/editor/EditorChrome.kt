package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtScreenTopBar

/**
 * 编辑器顶栏：返回 / 标题 / A- A+ / 查找 / 保存 / ⋮ 菜单。
 *
 * 菜单展开状态内聚在这里；动作回调均为「纯动作」——关菜单由 [EditorMenu] 负责。
 */
@Composable
internal fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onFontSizeDelta: (Int) -> Unit,
    onToggleFind: () -> Unit,
    languageLabel: String,
    languageManual: Boolean,
    canToggleComment: Boolean,
    canFormat: Boolean,
    wordwrap: Boolean,
    readOnly: Boolean,
    onLanguage: () -> Unit,
    onToggleWordwrap: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopyLine: () -> Unit,
    onCutLine: () -> Unit,
    onLineOp: (String, (String, Int) -> String) -> Unit,
    onToggleComment: () -> Unit,
    onCompress: () -> Unit,
    onFormat: () -> Unit,
    onGotoLine: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    MtScreenTopBar(title = title, onBack = onBack) {
        Box {
            MtIconButton(
                icon = MtIcon.MORE,
                contentDescription = "更多菜单",
                onClick = { showMenu = true },
            )
            EditorMenu(
                expanded = showMenu,
                languageLabel = languageLabel,
                languageManual = languageManual,
                canToggleComment = canToggleComment,
                canFormat = canFormat,
                wordwrap = wordwrap,
                readOnly = readOnly,
                onDismiss = { showMenu = false },
                onLanguage = onLanguage,
                onToggleWordwrap = onToggleWordwrap,
                onUndo = onUndo,
                onRedo = onRedo,
                onSave = onSave,
                onCopyLine = onCopyLine,
                onCutLine = onCutLine,
                onLineOp = onLineOp,
                onToggleComment = onToggleComment,
                onCompress = onCompress,
                onFormat = onFormat,
                onGotoLine = onGotoLine,
                onToggleFind = onToggleFind,
            )
        }
        TextButton(onClick = { onFontSizeDelta(-1) }) { Text("A-") }
        TextButton(onClick = { onFontSizeDelta(+1) }) { Text("A+") }
        TextButton(onClick = onToggleFind) { Text("查找") }
        TextButton(enabled = canSave, onClick = onSave) {
            Text(
                "保存",
                color = if (canSave) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 大文件分段浏览控制条（首段 / 上一段 / 区间 / 下一段 / 末段）。 */
@Composable
internal fun EditorPageBar(
    page: Int,
    pageCount: Int,
    rangeStart: Long,
    rangeEnd: Long,
    loading: Boolean,
    onGo: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageButton(MtIcon.FIRST_PAGE, enabled = page > 0 && !loading) { onGo(0) }
        PageButton(MtIcon.CHEVRON_L, enabled = page > 0 && !loading) { onGo(page - 1) }
        Text(
            "段 ${page + 1}/$pageCount · ${Fmt.size(rangeStart)}–${Fmt.size(rangeEnd)}" +
                if (loading) " · 加载中…" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        PageButton(MtIcon.CHEVRON_R, enabled = page < pageCount - 1 && !loading) { onGo(page + 1) }
        PageButton(MtIcon.FORWARD, enabled = page < pageCount - 1 && !loading) { onGo(pageCount - 1) }
    }
}
