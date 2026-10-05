package com.u707t.panelfm.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoadingState(text: String = "加载中…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

@Composable
fun ErrorState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** 分段标题（可折叠，MT 首页的「本地 / 网络 / 工具」） */
@Composable
fun SectionHeader(
    title: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = onToggle != null) { onToggle?.invoke() }
            // MT 截图实测：分段标题上下留白较紧（上 14dp / 下 6dp），左 18dp
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            // MT 的分段标题：16sp、常规字重、**中灰**（截图实测是 #666 一类的灰，不是纯黑）
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal),
            // 主题感知（旧实现写死浅色主题的 #666：深色主题下分段标题偏暗）
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            // MT 用**矢量细箭头**（︿/﹀ 是文字符号，字宽与基线会漂移）
            MtVectorIcon(
                icon = if (expanded) MtIcon.UNFOLD_UP else MtIcon.UNFOLD_DOWN,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** MT 首页的储存占用条（带百分比，蓝色） */
@Composable
fun UsageBar(
    used: Long,
    total: Long,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    val ratio = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    // MT 的占用条：细线 + 右侧百分比（截图实测「────── 80%」），
    // 百分比与横线**基线对齐**、间距 6dp，横线本身不圆角（MT 是直角细线）
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .weight(1f)
                .height(height)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.toFloat())
                    .height(height)
                    .background(AccentBlue),
            )
        }
        Text(
            text = "${(ratio * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * MT 的「已用/可用」文案（`0x7f110206` = `%1$s已用 , %2$s可用`）。
 *
 * 注意 MT 的原文在「已用」后有空格再逗号（`384.71G已用 , 94.80G可用`），
 * 且大小用**紧凑单位**（无空格）。
 */
fun usageText(used: Long, free: Long): String =
    "${com.u707t.panelfm.core.common.Fmt.sizeCompact(used)}已用 , ${com.u707t.panelfm.core.common.Fmt.sizeCompact(free)}可用"

/** MT 工具/网络列表的圆形图标底 */
@Composable
fun RoundIconBox(size: Dp = 40.dp, background: Color = MaterialTheme.colorScheme.onSurface, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 列表行：左图标 + 标题 + 副标题 + 右侧附加内容（MT 首页列表） */
@Composable
fun MtListRow(
    title: String,
    subtitle: String? = null,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    extraBelow: @Composable (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // MT 列表行（0x7f0c00e4 语义）：左右 16dp、上下 8dp；图标与文字间距 8dp
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                title,
                // MT 主标题 16sp（抽屉/主页行）
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = titleColor,
            )
            extraBelow?.invoke()
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.RowSubSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** 顶部栏右侧的图标按钮位（用文字/emoji 避免额外依赖） */
@Composable
fun IconTextButton(
    symbol: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .clickable(enabled = enabled) { onClick() }
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * 横向分割线。
 *
 * MT 的分割线是 **1px**（`dividerHeight=1px`、`0903F8` 高 1px、`090111/090112` 同理），
 * 不是 1dp；这里默认取 MT 的分割线色（`0x7f06003a`：日 #FFBBBBBB / 夜 #FF505050）。
 */
@Composable
fun HSeparator(color: Color = MtDividerColor) {
    // 与 DividerPx 同源：MT 的分割线就是 1px（此前这里是把同一实现抄了第二份）
    DividerPx(color)
}

@Composable
fun VSeparator(color: Color = MaterialTheme.colorScheme.outline, width: Dp = 1.dp) {
    Box(
        Modifier
            .width(width)
            .fillMaxSize()
            .background(color),
    )
}

/**
 * 全面屏安全区统一内缩（状态栏 / 挖孔 / 导航栏 / 输入法）。
 * 普通页面都在根布局加这一个修饰符；**沉浸式全屏页（视频播放器）不加**，
 * 由该页自己用 statusBarsPadding / navigationBarsPadding 摆放悬浮控件。
 */
@Composable
fun Modifier.safeAreaPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing)

