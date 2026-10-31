package com.u707t.panelfm.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 浮层的「Claude 几何 + 应用主题色」。
 *
 * 只保留 Claude 的**形状与排版**：16–20dp 大圆角、1px 细边、柔和阴影、40dp 紧凑行、
 * 18dp 淡图标、10.5sp 分组标题。
 *
 * 颜色不再写死暖纸 / 陶土橙，而是全部从 [MaterialTheme.colorScheme] 派生：
 *  - 浅色：`surface` 白卡 + `onSurface` 文字 + `outlineVariant` 细边 + `primary` 强调；
 *  - 深色：`surfaceVariant` 浮起层 + 主题的 onSurface/outline/primary；
 *  - 选中底 = primary 的低透明度叠色。
 * 这样菜单/弹层与文件列表、顶栏、进度条的蓝色强调保持同一套色，不再出现冷暖割裂。
 */
data class ClaudePalette(
    val surface: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val hover: Color,
)

/** null = 没有显式包 [ClaudeMenuTheme]，此时按当前 Material 主题自动派生 */
val LocalClaudePalette = staticCompositionLocalOf<ClaudePalette?> { null }

@Composable
private fun themeClaudePalette(): ClaudePalette {
    val cs = MaterialTheme.colorScheme
    val dark = LocalPanelDarkTheme.current
    return ClaudePalette(
        surface = if (dark) cs.surfaceVariant else cs.surface,
        text = cs.onSurface,
        muted = cs.onSurfaceVariant,
        border = cs.outlineVariant,
        divider = cs.outlineVariant.copy(alpha = 0.65f),
        accent = cs.primary,
        hover = cs.primary.copy(alpha = if (dark) 0.16f else 0.08f),
    )
}

@Composable
fun currentClaudePalette(): ClaudePalette =
    LocalClaudePalette.current ?: themeClaudePalette()

/**
 * 给一块浮层内容提供浮层色板。
 *
 * 不再覆盖 `MaterialTheme`：菜单行已经全部换成 [ClaudeMenuItem]/[MtMenuRow]，
 * 内部少数 `MaterialTheme` 颜色应当继续跟随应用主题（而不是被浮层强行改色）。
 */
@Composable
fun ClaudeMenuTheme(content: @Composable () -> Unit) {
    val p = themeClaudePalette()
    CompositionLocalProvider(LocalClaudePalette provides p, content = content)
}

/**
 * 统一菜单浮层（替换 `DropdownMenu`）：
 *  - 16dp 圆角、主题 surface、1px 细边、8dp 柔和阴影；
 *  - 内容四周 6dp 内边距，行本身再用 10dp 圆角点击态；
 *  - 宽度上限 360dp，避免长文案把菜单拉成整屏宽的「大白板」；
 *  - 位置仍由 Compose 按锚点自动避让，不会跑出屏幕。
 */
@Composable
fun ClaudeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = androidx.compose.foundation.rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = currentClaudePalette()
    ClaudeMenuTheme {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.widthIn(max = 360.dp),
            scrollState = scrollState,
            shape = RoundedCornerShape(16.dp),
            containerColor = p.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, p.border),
        ) {
            Column(Modifier.padding(6.dp), content = content)
        }
    }
}

/** 菜单分组小标题（11sp、微字距、暖灰） */
@Composable
fun ClaudeMenuSection(title: String) {
    val p = currentClaudePalette()
    Text(
        title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            letterSpacing = 0.08.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = p.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 1px 细分隔线（左右留白，避免贴到卡片边） */
@Composable
fun ClaudeMenuDivider() {
    val p = currentClaudePalette()
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        HSeparator(p.divider)
    }
}

/**
 * 菜单行：18dp 线性图标 + 14sp 常规字 + 右侧 16dp 指示。
 *
 * 与 M3 `DropdownMenuItem` 的区别：高度更紧凑（≥40dp）、图标更小更淡、
 * 选中行用暖灰底 + 陶土橙勾，而不是 M3 的主色块。
 */
@Composable
fun ClaudeMenuItem(
    label: String,
    icon: MtIcon? = null,
    trailing: MtIcon? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    checked: Boolean = false,
    /** 子菜单 / 层级列表的左缩进（10dp 基础上再加） */
    indent: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val p = currentClaudePalette()
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) p.hover else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = 10.dp + indent, end = 10.dp, top = 7.dp, bottom = 7.dp)
            .semantics {
                role = Role.Button
                if (!enabled) stateDescription = "不可用"
                if (checked) contentDescription = "$label，当前"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MtVectorIcon(icon = icon, size = 18.dp, tint = p.muted.copy(alpha = alpha))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = p.text.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon != null) 12.dp else 0.dp),
        )
        when {
            checked -> MtVectorIcon(MtIcon.CHECK, size = 16.dp, tint = p.accent.copy(alpha = alpha))
            trailing != null -> MtVectorIcon(trailing, size = 16.dp, tint = p.muted.copy(alpha = alpha))
            trailingText != null -> Text(
                trailingText,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = p.muted.copy(alpha = alpha),
            )
        }
    }
}
