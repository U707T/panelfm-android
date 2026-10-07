package com.u707t.panelfm.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MT 风格的配色（取自 MT 2.14.5 的真实资源值）：
 *  - 主文字 `0x7f060043` = #ee000000（浅）/ #eed0d0d0（深）
 *  - 次文字 `0x7f060047` = #99000000（浅）/ #99c4c4c4（深）
 *  - 分割线 `0x7f06003a` = #ffbbbbbb（浅）/ #ff505050（深）
 *  - 强调蓝 `0x7f0400ed` = #ff1976d2（浅）/ #ff42a5f5（深）
 *  - 顶栏（标题栏）**始终深色**：#ff151515（浅色主题）/ #ff303030（深色主题）——
 *    见 MtSpec.TopBarLight / TopBarDark，由 DualPaneScreen 单独上色，不走主题 surface。
 * 动态取色默认关闭（MT 是固定中性色板），设置里可开。
 */
private val LightColors = lightColorScheme(
    primary = MtSpec.AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2B66),
    secondary = Color(0xFF4C5560),
    background = Color(0xFFFFFFFF),
    onBackground = MtSpec.RowNameLight,
    surface = Color(0xFFFFFFFF),
    onSurface = MtSpec.RowNameLight,
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = MtSpec.RowSubLight,
    outline = MtSpec.DividerLight,
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFD93025),
)

private val DarkColors = darkColorScheme(
    primary = MtSpec.AccentDark,
    onPrimary = Color(0xFF08203F),
    primaryContainer = Color(0xFF1B2A44),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFB6BCC5),
    background = Color(0xFF121212),
    onBackground = MtSpec.RowNameDark,
    surface = Color(0xFF1B1B1B),
    onSurface = MtSpec.RowNameDark,
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = MtSpec.RowSubDark,
    outline = MtSpec.DividerDark,
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B5F),
)

/** MT 的排版略紧凑、标题偏粗；整体乘 [scale] 缩放（设置里的「字体大小」） */
private fun scaledTypography(scale: Float): Typography {
    fun androidx.compose.ui.text.TextStyle.s(size: Float, weight: FontWeight? = null) =
        copy(fontSize = (size * scale).sp, fontWeight = weight ?: fontWeight)

    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.s(57f),
        displayMedium = base.displayMedium.s(45f),
        displaySmall = base.displaySmall.s(36f),
        headlineLarge = base.headlineLarge.s(32f),
        headlineMedium = base.headlineMedium.s(28f),
        headlineSmall = base.headlineSmall.s(24f),
        titleLarge = base.titleLarge.s(22f, FontWeight.Bold),
        titleMedium = base.titleMedium.s(17f, FontWeight.SemiBold),
        titleSmall = base.titleSmall.s(15f, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.s(16f),
        bodyMedium = base.bodyMedium.s(15f),
        bodySmall = base.bodySmall.s(13f),
        labelLarge = base.labelLarge.s(14f),
        labelMedium = base.labelMedium.s(12f),
        labelSmall = base.labelSmall.s(11.5f),
    )
}

/** 字体大小档位：0 紧凑 / 1 适中（默认）/ 2 标准 */
fun fontScaleFactor(level: Int): Float = when (level) {
    0 -> 0.88f
    2 -> 1.0f
    else -> 0.94f
}

/**
 * 当前**应用主题**是否为深色。
 *
 * 为什么需要它：项目里多处直接调 `isSystemInDarkTheme()`，但用户可以在设置里
 * 强制浅色 / 深色。当「应用内浅色 + 系统深色」（或反之）时，那些地方会取错分支 ——
 * 表现就是「深色主题下文字仍是近黑色，列表几乎不可读」。
 * 统一读这个 Local（由 [PanelTheme] 提供）。
 */
val LocalPanelDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun PanelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    fontScale: Float = 0.94f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPanelDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            typography = androidx.compose.runtime.remember(fontScale) { scaledTypography(fontScale) },
            content = content,
        )
    }
}

/** MT 里面板主色（进度条、选中态） */
val AccentBlue = Color(0xFF2F6FED)

/** 供 UI 复用的等宽字体样式（编辑器 / 文本预览） */
val MonoStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
