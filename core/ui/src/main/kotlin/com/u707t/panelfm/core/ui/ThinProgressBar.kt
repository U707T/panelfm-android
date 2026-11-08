package com.u707t.panelfm.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘进度条（避免 M3 版本间 `LinearProgressIndicator` 签名差异）。
 *
 * v2.3.2 从 `ui/browser/BrowserWidgets.kt` 移到 core/ui：任务对话框、任务页、浏览页长操作条
 * 都在用它，而「任务」不属于浏览页 —— 留在 browser 包等于让 ui/tasks 反向依赖 ui/browser。
 *
 * [trackColor] 给「卡片 / 对话框内」用：底色本身已是 surfaceVariant 时，轨道再用同色就看不见了。
 */
@Composable
fun ThinProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 4.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .background(color),
        )
    }
}
