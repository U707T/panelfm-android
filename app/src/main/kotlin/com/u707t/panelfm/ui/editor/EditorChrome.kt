package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * 编辑器顶栏：返回 / 标题 / 撤销 / 重做 / 查找 / 保存 / ⋮ 菜单。
 *
 * v2.0.15 起对齐 NP 编辑器顶栏（`edit_menu.xml` 的 always 项：撤销 / 重做）——
 * 撤销 / 重做从 ⋮ 菜单提到顶栏；字号（A- / A+）收进 ⋮ →「设置」→「字体大小」，
 * 避免 6 个按钮把标题挤没（NP 同：字号在设置子菜单里）。
 *
 * 菜单展开状态内聚在这里；动作回调均为「纯动作」——关菜单由 [EditorMenu] 负责。
 */
@Composable
internal fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    menuInput: EditorMenuInput,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onToggleFind: () -> Unit,
    onMenuAction: (EditorMenuAction) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    MtScreenTopBar(title = title, onBack = onBack) {
        MtIconButton(
            icon = MtIcon.UNDO,
            contentDescription = "撤销",
            buttonSize = 40.dp,
            enabled = canUndo,
            tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onUndo,
        )
        MtIconButton(
            icon = MtIcon.REDO,
            contentDescription = "重做",
            buttonSize = 40.dp,
            enabled = canRedo,
            tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onRedo,
        )
        MtIconButton(
            icon = MtIcon.SEARCH,
            contentDescription = "查找 / 替换",
            buttonSize = 40.dp,
            onClick = onToggleFind,
        )
        MtIconButton(
            icon = MtIcon.SAVE,
            contentDescription = "保存",
            buttonSize = 40.dp,
            enabled = canSave,
            tint = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onSave,
        )
        Box {
            MtIconButton(
                icon = MtIcon.MORE,
                contentDescription = "更多菜单",
                onClick = { showMenu = true },
            )
            EditorMenu(
                expanded = showMenu,
                input = menuInput,
                onDismiss = { showMenu = false },
                onAction = onMenuAction,
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
