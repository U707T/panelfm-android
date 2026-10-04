package com.u707t.panelfm.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对话框图标（复刻 MT 的「对话框图标」设置，文案 `0x7f1102f3 / 2f4 / 2f5 / 2f6`）：
 *
 *  ① 深色背景（自适应）  ② 浅色背景（自适应）  ③ 无背景（受系统影响）
 *
 * MT 的对话框（属性 / 冲突 / 权限…）左侧都有一枚方形图标；
 * 「自适应」= 图标底色的明暗跟随主题反相（深色主题给浅底，浅色主题给深底），
 * 「无背景」= 不画底色，只用当前主题的前景色。
 */
enum class DialogIconMode(val label: String) {
    DARK("深色背景（自适应）"),
    LIGHT("浅色背景（自适应）"),
    NONE("无背景（受系统影响）"),
    ;

    companion object {
        fun of(index: Int): DialogIconMode = entries.getOrElse(index) { DARK }
    }
}

/**
 * 对话框标题左侧的方形图标（**矢量版**：用 MT 反解出来的真实图标，比文字符号更贴 MT）。
 * [mode] 由设置项 `dialogIconMode` 决定（调用方从 `AppSettings` 传入）。
 */
@Composable
fun DialogIcon(
    icon: MtIcon,
    mode: DialogIconMode,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val dark = isSystemInDarkTheme()
    val bg = when (mode) {
        DialogIconMode.DARK -> if (dark) Color(0xFFEFEFEF) else Color(0xFF3C3C3C)
        DialogIconMode.LIGHT -> if (dark) Color(0xFF3C3C3C) else Color(0xFFEFEFEF)
        DialogIconMode.NONE -> Color.Transparent
    }
    val fg = when (mode) {
        DialogIconMode.NONE -> MaterialTheme.colorScheme.primary
        DialogIconMode.DARK -> if (dark) Color(0xFF3C3C3C) else Color.White
        DialogIconMode.LIGHT -> if (dark) Color.White else Color(0xFF3C3C3C)
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(icon = icon, size = size * 0.58f, tint = fg)
    }
}

/**
 * 对话框标题左侧的方形图标（**文字版**，保留给没有对应矢量的场景）。
 * [mode] 由设置项 `dialogIconMode` 决定（调用方从 `AppSettings` 传入）。
 */
@Composable
fun DialogIcon(
    glyph: String,
    mode: DialogIconMode,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val dark = isSystemInDarkTheme()
    // 「深色背景」= 底色取深色（浅色主题下是深底白字；深色主题下自适应转浅底深字）
    val bg = when (mode) {
        DialogIconMode.DARK -> if (dark) Color(0xFFEFEFEF) else Color(0xFF3C3C3C)
        DialogIconMode.LIGHT -> if (dark) Color(0xFF3C3C3C) else Color(0xFFEFEFEF)
        DialogIconMode.NONE -> Color.Transparent
    }
    val fg = when (mode) {
        DialogIconMode.NONE -> MaterialTheme.colorScheme.primary
        DialogIconMode.DARK -> if (dark) Color(0xFF3C3C3C) else Color.White
        DialogIconMode.LIGHT -> if (dark) Color.White else Color(0xFF3C3C3C)
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = fg,
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
