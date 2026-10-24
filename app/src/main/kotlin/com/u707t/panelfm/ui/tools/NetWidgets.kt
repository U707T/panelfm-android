package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtSpec

// ================================================================================================
// 网络工具箱共用控件
//
// 集中在一处的原因：四个子页（Ping / DNS / HTTP / 端口）的输入区、动作行、结果区必须长得一样。
// 之前每个工具各写一套 Box + RoundedCornerShape + 0.35f 底色，参数一漂移整页就不齐。
// ================================================================================================

/** 结果区绿色（Size 徽标 / 开放端口标记）：深浅两个主题下都够亮。 */
val NetGreen = Color(0xFF5CBB7A)

/** 结果区琥珀色（警告：丢包 / 非 NOERROR / 端口过滤）。 */
val NetAmber = Color(0xFFE0A030)

/**
 * 顶部页签：等分 + 选中态主色淡底。
 *
 * 旧实现只有两个页签、用 `weight(1f)` 铺满整行 —— 加第三个页签时文字被挤成两行。
 * 这里仍是等分，但强制单行 + 省略，页签数量变化时高度不跳。
 */
@Composable
fun NetTabRow(
    tabs: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val selected = i == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MtSpec.CornerSmall))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 小胶囊按钮（预设 / 记录类型 / 视图切换）。选中态主色淡底 + 主色字。 */
@Composable
fun NetChip(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            !enabled -> idle.copy(alpha = 0.4f)
            selected -> accent
            else -> idle
        },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    selected -> accent.copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 统计条：`标签 值` 成组平铺，值用等宽字体（数字跳动时不抖）。
 *
 * 旧实现把所有结果塞进一大段 monospace 文本，用户得自己从 ping 输出里找丢包率。
 */
@Composable
fun NetStatBar(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    if (items.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** 结果框：等宽 + 可选中 + 可滚。空内容时显示占位说明（灰字）。 */
@Composable
fun NetOutputBox(
    text: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 外部持有的滚动状态（Ping 的「输出跟进到底」要用；不传则内部自建） */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    Box(
        modifier
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .padding(10.dp),
    ) {
        SelectionContainer {
            Text(
                text.ifEmpty { placeholder },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            )
        }
    }
}

/** 细进度条。 */
@Composable
fun NetProgress(progress: Float?, modifier: Modifier = Modifier) {
    val h = 3.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(h)
            .clip(RoundedCornerShape(h / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val p = (progress ?: 0.35f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth(p)
                .height(h)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** 一行的状态说明（成功 / 失败 / 进行中三色）。 */
@Composable
fun NetStatusLine(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = when {
        text.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
        text.contains("失败") || text.contains("错误") || text.contains("不可达") ||
            text.contains("拒绝") || text.contains("超时") -> MaterialTheme.colorScheme.error
        text.startsWith("完成") || text.startsWith("已") || text.contains("开放") -> NetGreen
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 竖线分隔（动作行内）。 */
@Composable
fun NetVLine(height: Dp = 16.dp) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(1.dp, height)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

/** 空结果占位（居中，比一整块空白清楚）。 */
@Composable
fun NetEmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** 结果表格行（端口探测 / DNS 记录共用）：左主文本 + 右副文本 + 徽标。 */@Composable
fun NetResultRow(
    primary: String,
    secondary: String?,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            primary,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        secondary?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 动作行：**横向可滚**。
 *
 * 为什么不直接用 Row：Ping 有「开始 / 停止 / 复制 / 存到窗格 / 清空」五个按钮，
 * 端口页有六个 —— 在 360dp 宽的屏上一行放不下，Row 会把最后一个按钮挤出屏幕外（点不到）。
 * 横向滚动保证任何屏宽下每个动作都可达，且不会因为按钮数量变化改变行高。
 */
@Composable
fun NetActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
