package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow

/**
 * 编辑器 ⋮ 菜单（v2.0.15 重构：内容由 [editorMenuSections] 纯模型产出，顺序 / 分组对齐
 * NP管理器 `edit_menu.xml`——文件 → 编辑 → 查找 → 跳转 → 视图 → 编码 ▶ → 设置 ▶ → 代码整理 → 退出）。
 *
 * 二级菜单用「同一个 DropdownMenu 内换页」实现（与本项目 `BrowserMoreMenu` 的「隐藏文件 ▶」
 * 同一范式）：不再弹第二个浮层，避免两层级联菜单在窄屏上互相遮挡。
 */
@Composable
internal fun EditorMenu(
    expanded: Boolean,
    input: EditorMenuInput,
    onDismiss: () -> Unit,
    onAction: (EditorMenuAction) -> Unit,
) {
    // 当前打开的二级菜单（null = 一级）
    var openSubmenu by remember { mutableStateOf<String?>(null) }
    val sections = editorMenuSections(input)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            openSubmenu = null
            onDismiss()
        },
    ) {
        val sub = openSubmenu?.let { title -> sections.firstOrNull { it.title == title && it.submenu } }
        if (sub == null) {
            sections.forEach { section ->
                if (section.submenu) {
                    MtMenuRow(
                        icon = submenuIcon(section.title),
                        label = section.title,
                        trailing = MtIcon.CHEVRON_R,
                    ) { openSubmenu = section.title }
                } else {
                    if (section.title.isNotEmpty()) SectionLabel(section.title)
                    section.entries.forEach { entry ->
                        MenuEntryRow(entry) {
                            // 关菜单时把二级菜单状态一并归零：下次打开回到一级
                            openSubmenu = null
                            onDismiss()
                            onAction(entry.action)
                        }
                    }
                }
            }
        } else {
            MtMenuRow(MtIcon.CHEVRON_L, sub.title) { openSubmenu = null }
            sub.entries.forEach { entry ->
                MenuEntryRow(entry) {
                    openSubmenu = null
                    onDismiss()
                    onAction(entry.action)
                }
            }
        }    }
}

@Composable
private fun MenuEntryRow(entry: EditorMenuEntry, onClick: () -> Unit) {
    MtMenuRow(
        icon = actionIcon(entry.action),
        label = entry.label,
        enabled = entry.enabled,
        checked = entry.checked == true,
        onClick = onClick,
    )
}

/** 分组小标题（MT 菜单里的「视图 / 行操作」同款；只在一级菜单出现）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp),
    )
}

/** 二级菜单入口的图标（NP 没有图标位，这里按语义给）。 */
private fun submenuIcon(title: String): MtIcon = when (title) {
    "编码" -> MtIcon.SYNC
    "设置" -> MtIcon.SETTINGS
    else -> MtIcon.MENU
}

/** 动作 → 图标（MT 风格菜单：左图标 + 文字 + 右侧勾选 / 箭头）。 */
private fun actionIcon(action: EditorMenuAction): MtIcon = when (action) {
    EditorMenuAction.SAVE -> MtIcon.SAVE
    EditorMenuAction.RELOAD -> MtIcon.SYNC
    EditorMenuAction.EXIT -> MtIcon.EXIT
    EditorMenuAction.UNDO -> MtIcon.UNDO
    EditorMenuAction.REDO -> MtIcon.REDO
    EditorMenuAction.COPY_LINE -> MtIcon.COPY
    EditorMenuAction.CUT_LINE -> MtIcon.CUT
    EditorMenuAction.DELETE_LINE -> MtIcon.DELETE
    EditorMenuAction.CLEAR_LINE -> MtIcon.CLOSE
    EditorMenuAction.DUPLICATE_LINE -> MtIcon.REPEAT
    EditorMenuAction.TO_UPPER, EditorMenuAction.TO_LOWER -> MtIcon.SORT_ALPHA
    EditorMenuAction.INDENT -> MtIcon.FORWARD
    EditorMenuAction.UNINDENT -> MtIcon.BACK
    EditorMenuAction.TOGGLE_COMMENT -> MtIcon.EDIT
    EditorMenuAction.FIND, EditorMenuAction.MAGNIFIER -> MtIcon.SEARCH
    EditorMenuAction.REPLACE -> MtIcon.EDIT
    EditorMenuAction.GOTO_LINE,
    EditorMenuAction.GOTO_LINE_START,
    EditorMenuAction.GOTO_LINE_END,
    EditorMenuAction.GOTO_FILE_START,
    EditorMenuAction.GOTO_FILE_END,
    -> MtIcon.LOCATE
    EditorMenuAction.LANGUAGE -> MtIcon.TERMINAL
    EditorMenuAction.THEME -> MtIcon.COLORIZE
    EditorMenuAction.WORDWRAP -> MtIcon.WRAP
    EditorMenuAction.FLUENT_MODE -> MtIcon.BRIGHTNESS
    EditorMenuAction.FLUENT_AUTO -> MtIcon.SYNC
    EditorMenuAction.CHARSET_RELOAD -> MtIcon.SYNC
    EditorMenuAction.CHARSET_SAVE -> MtIcon.SAVE_ALT
    EditorMenuAction.FONT_SIZE -> MtIcon.TEXT_SIZE
    EditorMenuAction.WHITESPACE -> MtIcon.LOW_PRIORITY
    EditorMenuAction.MONOSPACE -> MtIcon.FONT
    EditorMenuAction.SYMBOL_BAR -> MtIcon.KEYBOARD
    EditorMenuAction.INDENT_GUIDE -> MtIcon.LAYERS
    EditorMenuAction.FORMAT -> MtIcon.CODE
    EditorMenuAction.COMPRESS -> MtIcon.ARCHIVE
    EditorMenuAction.VALIDATE -> MtIcon.VERIFIED
}
