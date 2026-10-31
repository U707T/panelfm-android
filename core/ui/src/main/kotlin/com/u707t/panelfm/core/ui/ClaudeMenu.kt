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
 * Claude 风格浮层的暖色板。
 *
 * 只用于**菜单 / 弹层**，不改变文件列表的 MT 主题：
 *  - 浅色：暖纸底 `#FAF9F5`、暖黑文字 `#1F1E1D`、陶土橙强调 `#C96442`；
 *  - 深色：深暖灰底 `#262624`、米白文字 `#F3F2EC`、亮陶土橙 `#D97757`。
 *
 * 视觉要点：**暖中性色 + 大圆角 + 1px 细边 + 很轻的阴影 + 克制的图标**，
 * 不使用 M3 默认的冷灰容器色与 4dp 小圆角。
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

val ClaudeLight = ClaudePalette(
    surface = Color(0xFFFAF9F5),
    text = Color(0xFF1F1E1D),
    muted = Color(0xFF73726C),
    border = Color(0xFFE6E3D9),
    divider = Color(0xFFECEAE2),
    accent = Color(0xFFC96442),
    hover = Color(0xFFF0EEE6),
)

val ClaudeDark = ClaudePalette(
    surface = Color(0xFF262624),
    text = Color(0xFFF3F2EC),
    muted = Color(0xFFA6A49B),
    border = Color(0xFF3D3C38),
    divider = Color(0xFF353431),
    accent = Color(0xFFD97757),
    hover = Color(0xFF302F2C),
)

/** null = 没有显式包 [ClaudeMenuTheme]，此时按面板明暗自动取色 */
val LocalClaudePalette = staticCompositionLocalOf<ClaudePalette?> { null }

@Composable
fun currentClaudePalette(): ClaudePalette =
    LocalClaudePalette.current ?: if (LocalPanelDarkTheme.current) ClaudeDark else ClaudeLight

/**
 * 给一块浮层内容套上 Claude 色板 + 菜单排版。
 *
 * 只影响内部：`MaterialTheme` 的 surface / onSurface / primary 会被替换成暖色，
 * 这样即使内部用了原生 `DropdownMenuItem`，也不会忽然回到冷灰 M3 配色。
 */
@Composable
fun ClaudeMenuTheme(content: @Composable () -> Unit) {
    val p = currentClaudePalette()
    CompositionLocalProvider(LocalClaudePalette provides p) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = p.surface,
                surfaceContainer = p.surface,
                surfaceContainerHigh = p.surface,
                surfaceContainerHighest = p.surface,
                surfaceContainerLow = p.surface,
                surfaceContainerLowest = p.surface,
                onSurface = p.text,
                onSurfaceVariant = p.muted,
                outline = p.border,
                outlineVariant = p.divider,
                primary = p.accent,
                onPrimary = Color.White,
                secondary = p.accent,
            ),
            typography = MaterialTheme.typography.copy(
                bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            ),
            content = content,
        )
    }
}

/**
 * 统一菜单浮层（替换 `DropdownMenu`）：
 *  - 16dp 大圆角、暖纸底、1px 细边、12dp 柔和阴影；
 *  - 内容四周 6dp 内边距，行本身再用 10dp 圆角点击态；
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
            modifier = modifier,
            scrollState = scrollState,
            shape = RoundedCornerShape(16.dp),
            containerColor = p.surface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
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
