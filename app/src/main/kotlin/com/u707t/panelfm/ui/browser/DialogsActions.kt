package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.ClaudeMenuTheme
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.currentClaudePalette
import com.u707t.panelfm.core.ui.mtCombinedClickable

// ---------------------------------------------------------------------------
// MT 的动作菜单（截图复刻）：顶部提示条（带 ● 说明 + ✕）+ 两列网格 + 带 ● 的项支持长按
// ---------------------------------------------------------------------------

data class MtAction(
    val id: String,
    val label: String,
    /** MT 的真实矢量图标（原先用 emoji/文字符号，观感与字宽都漂移） */
    val icon: MtIcon,
    /** 带 ● ：长按可触发「单窗口操作」 */
    val singleWindow: Boolean = false,
    val enabled: Boolean = true,
    /**
     * 分组：MT 的菜单分「通用动作」与「按文件类型出现的动作」两类，
     * 后者在面板里单独起一行小标题（见 [MtActionSheet]）。
     */
    val section: String? = null,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MtActionSheet(
    actions: List<MtAction>,
    onAction: (String) -> Unit,
    onLongAction: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 子菜单（如「工具」）可传标题；主动作菜单按 MT 截图只显示提示条 */
    title: String? = null,
) {
    val showTip = actions.any { it.singleWindow }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        ClaudeMenuTheme {
            val p = currentClaudePalette()
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = p.surface,
                border = BorderStroke(1.dp, p.border),
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    // 顶部：MT 的提示条（带 ● 的菜单表示可以长按触发单窗口操作）+ ✕
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showTip) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(p.accent),
                            )
                            Text(
                                "带 ● 的菜单表示可以长按触发单窗口操作",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = p.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                            )
                        } else {
                            Text(
                                title.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = p.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(p.hover)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) {
                            MtVectorIcon(
                                icon = MtIcon.CLOSE,
                                size = 15.dp,
                                tint = p.muted,
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 4.dp))
                    // 两列网格（MT 截图2 布局）；带 section 的项前插一行小标题
                    //（按文件类型的二级菜单：压缩包的解压、APK 的安装等）
                    var lastSection: String? = null
                    actions.chunked(2).forEach { row ->
                        val section = row.firstOrNull()?.section
                        if (section != null && section != lastSection) {
                            Text(
                                section,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    letterSpacing = 0.06.sp,
                                ),
                                color = p.accent,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                        lastSection = section
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { action ->
                                MtActionCell(action, Modifier.weight(1f), onAction, onLongAction)
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MtActionCell(
    action: MtAction,
    modifier: Modifier,
    onAction: (String) -> Unit,
    onLongAction: (String) -> Unit,
) {
    val p = currentClaudePalette()
    val alpha = if (action.enabled) 1f else 0.38f
    Row(
        modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .mtCombinedClickable(
                enabled = action.enabled,
                onClick = { onAction(action.id) },
                onLongClick = { if (action.singleWindow) onLongAction(action.id) },
            )
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MtVectorIcon(
            icon = action.icon,
            size = 18.dp,
            tint = p.muted.copy(alpha = alpha),
        )
        Text(
            action.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = p.text.copy(alpha = alpha),
            modifier = Modifier.padding(start = 10.dp),
        )
        if (action.singleWindow) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(p.accent.copy(alpha = alpha)),
            )
        }
    }
}
