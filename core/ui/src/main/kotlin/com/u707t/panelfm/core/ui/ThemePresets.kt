package com.u707t.panelfm.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 内置配色主题。
 *
 * 参考 cfbrowser 的「墨笺 / 黛青 / 松烟 / 赭石」四套纸墨配色，加上原来的 **MT 蓝** 作为默认：
 *  - 每套都给出完整的浅色 / 深色 [ColorScheme]（surfaceContainer 五个层级也显式给出，
 *    抽屉 / 输入框 / 浮层不会掉回 M3 默认的紫灰）；
 *  - 顶栏仍然「始终深色」，但底色随主题换（见 [topBar]）；
 *  - 菜单 / 弹层全部从 `MaterialTheme.colorScheme` 取色，所以换主题后自动跟随。
 *
 * 动态取色（Android 12+）优先于预设：开启时走系统壁纸色，顶栏回落 MT 深色。
 */
enum class ThemePreset(
    val id: String,
    val label: String,
    val description: String,
    private val lightSpec: SchemeSpec,
    private val darkSpec: SchemeSpec,
    private val topBarLightColor: Color,
    private val topBarDarkColor: Color,
) {
    MT(
        id = "mt",
        label = "MT 蓝",
        description = "冷白 + MT 强调蓝（默认）",
        lightSpec = SchemeSpec(
            primary = Color(0xFF1976D2), onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF0B2B66),
            background = Color(0xFFFFFFFF), onBackground = Color(0xEE000000),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xEE000000),
            surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0x99000000),
            outline = Color(0xFFBBBBBB), outlineVariant = Color(0xFFE0E0E0),
            containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFF7F7F7),
            container = Color(0xFFF2F2F2), containerHigh = Color(0xFFECECEC),
            containerHighest = Color(0xFFE6E6E6), error = Color(0xFFD93025),
        ),
        darkSpec = SchemeSpec(
            primary = Color(0xFF42A5F5), onPrimary = Color(0xFF08203F),
            primaryContainer = Color(0xFF1B2A44), onPrimaryContainer = Color(0xFFD7E3FF),
            background = Color(0xFF121212), onBackground = Color(0xEED0D0D0),
            surface = Color(0xFF1B1B1B), onSurface = Color(0xEED0D0D0),
            surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0x99C4C4C4),
            outline = Color(0xFF505050), outlineVariant = Color(0xFF3A3A3A),
            containerLowest = Color(0xFF101010), containerLow = Color(0xFF161616),
            container = Color(0xFF1B1B1B), containerHigh = Color(0xFF212121),
            containerHighest = Color(0xFF262626), error = Color(0xFFFF6B5F),
            onError = Color(0xFF3A0906),
        ),
        topBarLightColor = Color(0xFF151515),
        topBarDarkColor = Color(0xFF303030),
    ),

    /** cfbrowser 墨笺：暖纸 + 朱砂；深色是深墨蓝纸。 */
    MOJIAN(
        id = "mojian",
        label = "墨笺",
        description = "暖纸 + 朱砂（参考 cfbrowser）",
        lightSpec = SchemeSpec(
            primary = Color(0xFFB23A26), onPrimary = Color(0xFFFFF9F2),
            primaryContainer = Color(0xFFF3E1DA), onPrimaryContainer = Color(0xFF4A1710),
            background = Color(0xFFF6F2EA), onBackground = Color(0xFF1D1B18),
            surface = Color(0xFFFBF8F2), onSurface = Color(0xFF1D1B18),
            surfaceVariant = Color(0xFFEEE8DC), onSurfaceVariant = Color(0xFF68615A),
            outline = Color(0xFFCDC3B1), outlineVariant = Color(0xFFE3DBCD),
            containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFFBF8F2),
            container = Color(0xFFF6F2EA), containerHigh = Color(0xFFEEE8DC),
            containerHighest = Color(0xFFE6DED0), error = Color(0xFFB3261E),
        ),
        darkSpec = SchemeSpec(
            primary = Color(0xFFE3735A), onPrimary = Color(0xFF2B0E08),
            primaryContainer = Color(0xFF4A1F18), onPrimaryContainer = Color(0xFFFFDAD0),
            background = Color(0xFF12161C), onBackground = Color(0xFFECE6DA),
            surface = Color(0xFF12161C), onSurface = Color(0xFFECE6DA),
            surfaceVariant = Color(0xFF181D25), onSurfaceVariant = Color(0xFF9A9385),
            outline = Color(0xFF343C47), outlineVariant = Color(0xFF242A33),
            containerLowest = Color(0xFF0B0C0F), containerLow = Color(0xFF0D1015),
            container = Color(0xFF12161C), containerHigh = Color(0xFF181D25),
            containerHighest = Color(0xFF1F242D), error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        ),
        topBarLightColor = Color(0xFF1D1B18),
        topBarDarkColor = Color(0xFF181D25),
    ),

    /** cfbrowser 黛青：冷灰青纸 + 靛蓝。 */
    DAIQING(
        id = "daiqing",
        label = "黛青",
        description = "冷灰青 + 靛蓝（参考 cfbrowser）",
        lightSpec = SchemeSpec(
            primary = Color(0xFF2C5A78), onPrimary = Color(0xFFF6FAFF),
            primaryContainer = Color(0xFFD6E7F3), onPrimaryContainer = Color(0xFF0B2536),
            background = Color(0xFFF7FAFC), onBackground = Color(0xFF16212B),
            surface = Color(0xFFFBFDFF), onSurface = Color(0xFF16212B),
            surfaceVariant = Color(0xFFE8F0F6), onSurfaceVariant = Color(0xFF5A6B78),
            outline = Color(0xFFA9BAC7), outlineVariant = Color(0xFFD6E2EA),
            containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFFBFDFF),
            container = Color(0xFFF3F8FC), containerHigh = Color(0xFFE8F0F6),
            containerHighest = Color(0xFFDCE8F0), error = Color(0xFFB3261E),
        ),
        darkSpec = SchemeSpec(
            primary = Color(0xFF84B2D3), onPrimary = Color(0xFF08283A),
            primaryContainer = Color(0xFF1B3A4E), onPrimaryContainer = Color(0xFFD0E7F7),
            background = Color(0xFF0F171F), onBackground = Color(0xFFDCE6EE),
            surface = Color(0xFF121B24), onSurface = Color(0xFFDCE6EE),
            surfaceVariant = Color(0xFF1A2733), onSurfaceVariant = Color(0xFF9FB2C0),
            outline = Color(0xFF3A4C5A), outlineVariant = Color(0xFF24333F),
            containerLowest = Color(0xFF0A1015), containerLow = Color(0xFF0D141A),
            container = Color(0xFF121B24), containerHigh = Color(0xFF1A2733),
            containerHighest = Color(0xFF223140), error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        ),
        topBarLightColor = Color(0xFF1A2733),
        topBarDarkColor = Color(0xFF1A2733),
    ),

    /** cfbrowser 松烟：灰绿纸 + 松烟绿。 */
    SONGYAN(
        id = "songyan",
        label = "松烟",
        description = "灰绿 + 松烟（参考 cfbrowser）",
        lightSpec = SchemeSpec(
            primary = Color(0xFF3B6A4E), onPrimary = Color(0xFFF5FFF8),
            primaryContainer = Color(0xFFD7E9DD), onPrimaryContainer = Color(0xFF0D2A19),
            background = Color(0xFFF7FAF8), onBackground = Color(0xFF16201A),
            surface = Color(0xFFFBFDFB), onSurface = Color(0xFF16201A),
            surfaceVariant = Color(0xFFE6F0E9), onSurfaceVariant = Color(0xFF586B5F),
            outline = Color(0xFFA9BCAF), outlineVariant = Color(0xFFD5E3D9),
            containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFFBFDFB),
            container = Color(0xFFF2F8F4), containerHigh = Color(0xFFE6F0E9),
            containerHighest = Color(0xFFDAE8DE), error = Color(0xFFB3261E),
        ),
        darkSpec = SchemeSpec(
            primary = Color(0xFF87BE9C), onPrimary = Color(0xFF0A2A16),
            primaryContainer = Color(0xFF1B3A29), onPrimaryContainer = Color(0xFFD0F0DC),
            background = Color(0xFF0F1713), onBackground = Color(0xFFDCE8DF),
            surface = Color(0xFF121C17), onSurface = Color(0xFFDCE8DF),
            surfaceVariant = Color(0xFF1A241E), onSurfaceVariant = Color(0xFF9FB3A6),
            outline = Color(0xFF3A4C40), outlineVariant = Color(0xFF243329),
            containerLowest = Color(0xFF0A100C), containerLow = Color(0xFF0D1510),
            container = Color(0xFF121C17), containerHigh = Color(0xFF1A241E),
            containerHighest = Color(0xFF22302A), error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        ),
        topBarLightColor = Color(0xFF1A241E),
        topBarDarkColor = Color(0xFF1A241E),
    ),

    /** cfbrowser 赭石：沙纸 + 赭石。 */
    ZHESHI(
        id = "zheshi",
        label = "赭石",
        description = "沙纸 + 赭石（参考 cfbrowser）",
        lightSpec = SchemeSpec(
            primary = Color(0xFF875B22), onPrimary = Color(0xFFFFF8EE),
            primaryContainer = Color(0xFFF0DFC4), onPrimaryContainer = Color(0xFF3A2306),
            background = Color(0xFFFBF8F2), onBackground = Color(0xFF241C14),
            surface = Color(0xFFFFFDF8), onSurface = Color(0xFF241C14),
            surfaceVariant = Color(0xFFF0E8DA), onSurfaceVariant = Color(0xFF6B5C49),
            outline = Color(0xFFC2B29A), outlineVariant = Color(0xFFE3D7C4),
            containerLowest = Color(0xFFFFFFFF), containerLow = Color(0xFFFFFDF8),
            container = Color(0xFFFBF5EA), containerHigh = Color(0xFFF0E8DA),
            containerHighest = Color(0xFFE6DAC8), error = Color(0xFFB3261E),
        ),
        darkSpec = SchemeSpec(
            primary = Color(0xFFDCAA66), onPrimary = Color(0xFF33200A),
            primaryContainer = Color(0xFF4A3418), onPrimaryContainer = Color(0xFFF6E0BC),
            background = Color(0xFF17120C), onBackground = Color(0xFFEDE3D3),
            surface = Color(0xFF1D1710), onSurface = Color(0xFFEDE3D3),
            surfaceVariant = Color(0xFF241C14), onSurfaceVariant = Color(0xFFB3A48F),
            outline = Color(0xFF4A3D2E), outlineVariant = Color(0xFF302619),
            containerLowest = Color(0xFF120E09), containerLow = Color(0xFF17120C),
            container = Color(0xFF1D1710), containerHigh = Color(0xFF241C14),
            containerHighest = Color(0xFF2C2318), error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        ),
        topBarLightColor = Color(0xFF241C14),
        topBarDarkColor = Color(0xFF241C14),
    ),
    ;

    fun scheme(dark: Boolean): ColorScheme =
        (if (dark) darkSpec else lightSpec).toScheme(dark)

    /** 顶栏 / 抽屉头始终深色，但底色随主题换 */
    fun topBar(dark: Boolean): Color = if (dark) topBarDarkColor else topBarLightColor

    /** 列表 / 顶栏的分割线：MT 保持原灰色，其它主题用各自的 outlineVariant */
    fun divider(dark: Boolean): Color = when (this) {
        MT -> if (dark) MtSpec.DividerDark else MtSpec.DividerLight
        else -> scheme(dark).outlineVariant
    }

    companion object {
        fun byId(id: String?): ThemePreset = entries.firstOrNull { it.id == id } ?: MT
    }
}

/** 一套预设的完整色值；[toScheme] 负责补齐 M3 的其它 role。 */
private data class SchemeSpec(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val error: Color,
    val onError: Color = Color.White,
)

private fun SchemeSpec.toScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        // fixed 系列显式收敛到主题色，避免 M3 默认紫灰从这些 role 漏出来
        primaryFixed = primary,
        primaryFixedDim = primaryContainer,
        onPrimaryFixed = onPrimary,
        onPrimaryFixedVariant = onPrimaryContainer,
        // 次要 / 第三色都收敛到中性 + 主色，避免 M3 默认紫灰混进来
        secondary = outline,
        onSecondary = surface,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = onSurface,
        secondaryFixed = surfaceVariant,
        secondaryFixedDim = containerHigh,
        onSecondaryFixed = onSurface,
        onSecondaryFixedVariant = onSurfaceVariant,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        tertiaryFixed = primary,
        tertiaryFixedDim = primaryContainer,
        onTertiaryFixed = onPrimary,
        onTertiaryFixedVariant = onPrimaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = containerLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        // 深色时 surfaceDim 应比 surface 更暗、surfaceBright 更亮；浅色相反
        surfaceDim = if (dark) containerLowest else container,
        surfaceBright = if (dark) containerHighest else containerLowest,
        surfaceTint = Color.Transparent,
        outline = outline,
        outlineVariant = outlineVariant,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        inversePrimary = primary,
        error = error,
        onError = onError,
        // errorContainer 不能拿 surfaceVariant 顶替，否则错误态会被主题吞成中性灰
        errorContainer = if (dark) Color(0xFF8C1D18) else Color(0xFFF9DEDC),
        onErrorContainer = if (dark) Color(0xFFF9DEDC) else Color(0xFF410E0B),
    )
}
