package com.u707t.panelfm.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 对话框标题左侧的方形图标（复刻 MT 的「对话框图标」）。
 *
 * 视觉固定为 MT 的「深色背景（自适应）」：浅色主题下深底白图标，深色主题下浅底深图标。
 *
 * v2.1.4 之前它是一条三档设置项（深色背景 / 浅色背景 / 无背景）。删掉的理由是**覆盖面**：
 * 全仓库只有传输冲突框与属性框读了这条设置，其余对话框一律默认档 —— 用户改完看不到整体变化，
 * 选项承诺远大于实际效果。收敛成一种后少一个设置项、少一套分支。
 */
@Composable
fun DialogIcon(
    icon: MtIcon,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val dark = LocalPanelDarkTheme.current
    val bg = if (dark) Color(0xFFEFEFEF) else Color(0xFF3C3C3C)
    val fg = if (dark) Color(0xFF3C3C3C) else Color.White
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
