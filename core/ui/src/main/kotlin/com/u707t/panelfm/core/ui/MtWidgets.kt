package com.u707t.panelfm.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MT 复刻专用控件集（数值全部来自 `/workspace/mt-analysis/MT-UI-功能-逻辑-解析.md`）。
 *
 * 这些控件把「MT 的观感」集中到一处，避免每个界面各写各的魔法数字：
 *  - [MtDialog]：MT 的对话框骨架（标题居中 + 内容 paddingTop 18dp，见 §6.1 / B.1）
 *  - [MtFab]：MT 的 FAB 规范（50dp / 图标 20dp / #FFFF0000 底 / elevation 3→6，见 A.2）
 *  - [PaneEdgeShadow]：双窗格焦点阴影四件套（5dp 渐变 #67000000→透明，见 E.2）
 *  - [MtMenuRow]：⋮ 菜单行（30dp 图标列 + 文字 + 可选右侧箭头/勾选）
 *  - [MtActionButton]：顶栏动作条按钮（图标 22dp + 文字 14sp + 左右 padding 15dp，见 §1.3）
 *  - [MtBottomIconButton]：底栏图标按钮（整高点击区 + 24dp 图标，见 §1.5 / G.3.3）
 *  - [DividerPx]：MT 的分割线是 **1px**（不是 1dp），见 §1.2 / E.3
 */

/** MT 的分割线色（`0x7f06003a`：日 #FFBBBBBB / 夜 #FF505050） */
val MtDividerColor: Color
    @Composable get() = if (androidx.compose.foundation.isSystemInDarkTheme()) MtSpec.DividerDark else MtSpec.DividerLight

/**
 * 横向分割线：MT 用 **1px**（`dividerHeight=1px` / `0903F8` / `090111/090112`）。
 *
 * 注意是**物理 1px**，不是 1dp —— 在 3x 屏上 1dp = 3px，会明显比 MT 粗。
 * 这里用 `1px → dp` 换算，保证任何密度下都是 1 个物理像素。
 */
@Composable
fun DividerPx(color: Color = MtDividerColor, modifier: Modifier = Modifier) {
    val onePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(onePx)
            .background(color),
    )
}

/** 竖向分割线：MT 的双窗格中线 `090111/090112` = 1px */
@Composable
fun VDividerPx(color: Color = MtDividerColor, modifier: Modifier = Modifier) {
    val onePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }
    Box(
        modifier
            .width(onePx)
            .fillMaxHeight()
            .background(color),
    )
}

/**
 * 活动窗格边缘阴影（复刻 MT 的 shadow_left / shadow_right，附录 E.2）：
 *  - 左窗格的**右边缘**：`#67000000 → #00000000`（自左向右淡出）
 *  - 右窗格的**左边缘**：`#67000000 → #00000000`（自右向左淡出，centerX=0.3）
 *
 * MT 只在**活动窗格**一侧亮阴影；配合顶栏高亮表达焦点，不整体调透明度。
 */
@Composable
fun PaneEdgeShadow(
    /** true = 本窗格是活动窗口（阴影亮起） */
    active: Boolean,
    /** true = 左窗格（阴影画在右边缘）；false = 右窗格（阴影画在左边缘） */
    isLeftPane: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!active) return
    val start = Color(0x67000000)
    val end = Color.Transparent
    val brush = if (isLeftPane) {
        // 左窗格右缘：靠内侧深、向外（右）淡出
        Brush.horizontalGradient(colors = listOf(start, end))
    } else {
        // 右窗格左缘：靠内侧深、向外（左）淡出 → 从透明到深，再翻转
        Brush.horizontalGradient(colors = listOf(end, start))
    }
    Box(
        modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(brush),
    )
}

/** 顶栏 / 底栏的横向渐变（`0x7f0801da` 顶部横条 `#60000000→透明`，`0x7f0801dd` 底部 `#50000000→透明`） */
@Composable
fun PaneHorizontalShadow(top: Boolean, modifier: Modifier = Modifier) {
    val start = if (top) Color(0x60000000) else Color(0x50000000)
    val brush = if (top) {
        Brush.verticalGradient(colors = listOf(start, Color.Transparent))
    } else {
        Brush.verticalGradient(colors = listOf(Color.Transparent, start))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(brush),
    )
}

/**
 * MT 的 FAB（复刻 A.2 的统一约定）：
 * `fabCustomSize=50dp` · `maxImageSize=20dp` · `backgroundTint=@7F060042 (#FFFF0000)` ·
 * `elevation=3dp` · `pressedTranslationZ=6dp` · 图标色 = colorControlNormal（红底上用白）。
 */
@Composable
fun MtFab(
    icon: MtIcon,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(MtSpec.FabSize)
            .clip(CircleShape)
            .background(if (enabled) MtSpec.FabRed else MtSpec.FabRed.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(icon = icon, size = MtSpec.FabIcon, tint = Color.White)
    }
}

/**
 * MT 的对话框骨架（复刻 §6.1 / 附录 B.1）：
 *  - 标题 `TextView id=090464`：`ellipsize=3`、`textAlignment=5`（**居中**）、上下 padding 用主题值
 *  - 内容区：左右 padding = 主题属性 `?7F04017D`、**paddingTop = 18dp**（`@7F070025`）
 *  - 选项：RadioButton `textSize=16sp`、CheckBox 默认样式、Spinner
 *
 * 用法与 [AlertDialog] 一致，但排版按 MT 的规范固定下来。
 */
@Composable
fun MtDialog(
    onDismissRequest: () -> Unit,
    title: String,
    /** 标题左侧的图标（MT 的「对话框图标」三模式，见 [DialogIcon]） */
    titleIcon: MtIcon? = null,
    dialogIconMode: Int = 0,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (titleIcon != null) {
                    DialogIcon(
                        icon = titleIcon,
                        mode = DialogIconMode.of(dialogIconMode),
                        size = 28.dp,
                    )
                }
                Text(
                    title,
                    // MT：标题居中、单行省略
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = MtSpec.DialogPaddingTop),
                )
            }
        },
        text = { Column(Modifier.padding(top = MtSpec.DialogPaddingTop)) { text() } },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/**
 * ⋮ 菜单项（MT 菜单：左图标 + 文字 + 右侧指示）。
 *
 * [trailing] 传 [MtIcon] 时画**矢量箭头**（子菜单用 `CHEVRON_R`），
 * 不再用「▶」字符（字宽/基线随字体漂移，MT 用的是 24dp 线性图标）。
 */
@Composable
fun MtMenuRow(
    icon: MtIcon,
    label: String,
    trailing: MtIcon? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        enabled = enabled,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MtVectorIcon(
                    icon = icon,
                    size = MtSpec.MenuIcon,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    label,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                )
                when {
                    checked -> MtVectorIcon(
                        icon = MtIcon.CHECK,
                        size = MtSpec.MenuIcon,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    trailing != null -> MtVectorIcon(
                        icon = trailing,
                        size = 18.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    trailingText != null -> Text(
                        trailingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/**
 * 顶栏动作条按钮（复刻 `0x7f0c0034` 的 `09022C/09022D/09022E`）：
 * 图标 **22dp** + 文字 **14sp** + 左右 padding **15dp**，整高点击区。
 */
@Composable
fun MtActionButton(
    icon: MtIcon,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxHeight()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) stateDescription = "不可用"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        MtVectorIcon(icon = icon, size = MtSpec.ActionIcon, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            maxLines = 1,
            color = tint,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 底栏图标按钮（复刻 `0x7f0c0033` 的 `09007F` 工具栏）：
 * 整高点击区（底栏 64dp）+ 24dp 线性图标；支持「长按 = 第二功能」（MT 的 `0x7f1106e4` 范式）。
 */
@Composable
fun MtBottomIconButton(
    icon: MtIcon,
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val base = Modifier
        .fillMaxHeight()
        .width(MtSpec.BottomButtonWidth)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        .background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
    val modifier = if (onLongClick != null) {
        base
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = "长按可执行第二功能"
            }
    } else {
        base
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        MtVectorIcon(
            icon = icon,
            size = MtSpec.BottomBarIcon,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                highlighted -> MaterialTheme.colorScheme.primary
                else -> LocalContentColor.current.copy(alpha = 0.87f)
            },
        )
    }
}

/** MT 的行内「小标题 + 值」两列（属性面板 / 设置页复用），标签列宽 88dp 对齐 MT 的 Barrier 布局 */
@Composable
fun MtInfoRow(
    label: String,
    value: String,
    labelWidth: androidx.compose.ui.unit.Dp = 88.dp,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 分段小标题（MT 的「---- 特殊权限 ----」范式） */
@Composable
fun MtSectionDivider(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "----  $text  ----",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * MT 的输入框（复刻 MT 的 `com.google.android.material.textfield.TextInputLayout`）。
 *
 * MT 的四个连接表单（WebDAV / SFTP / SMB / FTP）用的都是 **Material 填充式**输入框：
 *  - 上方一行小号灰色 **label**（`hint` 提升后的样子，如「URL」「用户名」「密码」）
 *  - 输入框内一行更淡的 **placeholder**（如 `https://dav.xxx.com:443/dav`、`可空`）
 *  - 底部一条细下划线，聚焦时变主题蓝
 *
 * 与 Compose 的 `OutlinedTextField`（四边框）观感差别很大 —— MT 是**只有下划线**的填充式，
 * 所以这里自绘一个，保证和 MT 截图一致。
 */
@Composable
fun MtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val labelColor = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = if (focused) accent else MaterialTheme.colorScheme.outline
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        // MT：label 在输入框**上方**（Material 的 expandedHint），13sp 灰字
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = labelColor,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    interactionSource = interaction,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 6.dp),
                )
            }
            trailing?.invoke()
        }
        // MT：只有一条下划线（不是四边框）
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(lineColor),
        )
        // 记录焦点（用 LaunchedEffect 观察 BasicTextField 的焦点状态）
        LaunchedEffect(interaction) {
            interaction.interactions.collect { i ->
                focused = i is androidx.compose.foundation.interaction.FocusInteraction.Focus
            }
        }
    }
}
