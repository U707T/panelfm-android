package com.u707t.panelfm.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MT 风格的配色（默认主题 = MT 蓝）：
 *  - 主文字 `0x7f060043` = #ee000000（浅）/ #eed0d0d0（深）
 *  - 次文字 `0x7f060047` = #99000000（浅）/ #99c4c4c4（深）
 *  - 分割线 `0x7f06003a` = #ffbbbbbb（浅）/ #ff505050（深）
 *  - 强调蓝 `0x7f0400ed` = #ff1976d2（浅）/ #ff42a5f5（深）
 *  - 顶栏（标题栏）**始终深色**，底色随主题预设换（见 [ThemePreset.topBar]）
 * 各套预设（MT 蓝 / 墨笺 / 黛青 / 松烟 / 赭石）在 [ThemePreset] 里给出完整明暗 ColorScheme。
 * 动态取色默认关闭；开启后优先于预设。
 */
val LocalTopBarBackground = androidx.compose.runtime.staticCompositionLocalOf { MtSpec.TopBarLight }

/** 列表 / 顶栏分割线颜色；由 [PanelTheme] 按主题提供，MT 预设保持原来的灰色。 */
val LocalDividerColor = androidx.compose.runtime.staticCompositionLocalOf { MtSpec.DividerLight }

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
    /** 配色预设 id；未知 id 回落到 MT */
    presetId: String = "mt",
    fontScale: Float = 0.94f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preset = ThemePreset.byId(presetId)
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> preset.scheme(darkTheme)
    }
    // 顶栏始终深色；动态取色没有配套的顶栏色，回落 MT 的深色
    val topBar = if (dynamic) ThemePreset.MT.topBar(darkTheme) else preset.topBar(darkTheme)
    val divider = if (dynamic) colors.outlineVariant else preset.divider(darkTheme)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPanelDarkTheme provides darkTheme,
        LocalTopBarBackground provides topBar,
        LocalDividerColor provides divider,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = androidx.compose.runtime.remember(fontScale) { scaledTypography(fontScale) },
            content = content,
        )
    }
}

/** 供 UI 复用的等宽字体样式（编辑器 / 文本预览） */
val MonoStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
