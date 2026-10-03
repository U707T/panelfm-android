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
 * MT 风格的配色：默认「跟随系统」，浅色为主，中性灰 + 蓝色强调。
 * 动态取色默认关闭（MT 的观感是固定的中性色板），设置里可开。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2B66),
    secondary = Color(0xFF4C5560),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111315),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111315),
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF8A8F98),
    outline = Color(0xFFE1E4E8),
    outlineVariant = Color(0xFFEFF1F3),
    error = Color(0xFFD93025),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA0FF),
    onPrimary = Color(0xFF08203F),
    primaryContainer = Color(0xFF1B2A44),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFB6BCC5),
    background = Color(0xFF121417),
    onBackground = Color(0xFFECEDEE),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFECEDEE),
    surfaceVariant = Color(0xFF1E2126),
    onSurfaceVariant = Color(0xFF9AA1AB),
    outline = Color(0xFF2A2E34),
    outlineVariant = Color(0xFF23262A),
    error = Color(0xFFFF6B5F),
)

/** MT 的排版略紧凑、标题偏粗 */
private val PanelTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.5.sp),
    )
}

@Composable
fun PanelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = PanelTypography, content = content)
}

/** MT 里面板主色（进度条、选中态） */
val AccentBlue = Color(0xFF2F6FED)

/** 供 UI 复用的等宽字体样式（Hex / 编辑器） */
val MonoStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
