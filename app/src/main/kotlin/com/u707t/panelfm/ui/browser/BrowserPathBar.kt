package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.u707t.panelfm.core.ui.ClaudeMenuDivider
import com.u707t.panelfm.core.ui.ClaudeMenuSection
import com.u707t.panelfm.core.ui.ClaudeMenuTheme
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.currentClaudePalette
import com.u707t.panelfm.core.ui.mtCombinedClickable
import com.u707t.panelfm.core.vfs.local.LocalVolumes

/**
 * 顶部路径（对齐 MT 原版观感）。
 *
 * 视觉上只有**一行居中路径 + 一行统计**：没有分段底色、没有分隔符、没有常驻 ▾。
 * 交互收进路径本身：
 *  - 点路径 → 顶部居中的 Claude 风格路径卡片（层级 + 操作）；
 *  - 长按路径 → 复制完整路径（MT「已复制完整路径到剪贴板」）；
 *  - 路径显示真实完整路径（本地 `/storage/emulated/0/…`），中间省略，和 MT 一致。
 */
@Composable
internal fun BrowserPathBar(
    focused: PaneState,
    focusSide: PaneSide,
    controller: BrowserController,
    ds: BrowserDialogsState,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val volumes = remember(context) { LocalVolumes.volumes(context) }
    val crumbs = remember(focused.uri, focused.tab.label, volumes) {
        pathCrumbs(focused.uri, focused.tab.label, volumes)
    }
    val fullPath = remember(focused.uri, volumes) { pathTextOf(focused.uri, volumes) }
    val shownPath = remember(focused.uri, volumes) { pathDisplayOf(focused.uri, volumes) }
    var menu by remember { mutableStateOf(false) }

    fun copyPath() {
        clipboard.setText(AnnotatedString(fullPath))
        controller.showStatus("已复制完整路径到剪贴板")
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            shownPath,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = MtSpec.TopBarTitleSize,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MtSpec.TopBarText,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 160.dp)
                .mtCombinedClickable(
                    onClick = { menu = true },
                    onLongClick = { copyPath() },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "当前路径 $fullPath，点击打开路径菜单"
                }
                .padding(vertical = 2.dp),
        )
    }

    if (menu) {
        PathDialog(
            shownPath = shownPath,
            crumbs = crumbs,
            onDismiss = { menu = false },
            onPick = { crumb ->
                menu = false
                if (!crumb.current) controller.open(focusSide, crumb.uri, label = crumb.mountLabel)
            },
            onGoto = {
                menu = false
                ds.gotoPath = true
            },
            onCopy = {
                menu = false
                copyPath()
            },
            onBookmark = {
                menu = false
                controller.addBookmark(focusSide)
            },
        )
    }
}

/**
 * 顶部居中的路径卡片（Claude 风格）：
 * 暖纸底、20dp 圆角、1px 细边、轻阴影；固定顶部起点，不再贴着路径文字往下掉。
 */
@Composable
private fun PathDialog(
    shownPath: String,
    crumbs: List<PathCrumb>,
    onDismiss: () -> Unit,
    onPick: (PathCrumb) -> Unit,
    onGoto: () -> Unit,
    onCopy: () -> Unit,
    onBookmark: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        ClaudeMenuTheme {
            val p = currentClaudePalette()
            // Claude 的浮层遮罩很轻：保留上下文，不把文件列表压成黑块
            val view = LocalView.current
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.18f)
                onDispose { }
            }
            // 小屏 / 横屏时不要把卡片撑出屏幕：顶部间距与列表高度一起收缩
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val compact = LocalConfiguration.current.screenHeightDp < 600
            val topGap = if (compact) 48.dp else 72.dp
            val listMaxHeight = (screenHeight - if (compact) 250.dp else 220.dp)
                .coerceIn(140.dp, 440.dp)
            Box(Modifier.fillMaxSize()) {
                // 卡片外点击关闭（系统 dim 之上的一层透明捕获）
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickableNoRipple(onClick = onDismiss),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = topGap, start = 16.dp, end = 16.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .clickableNoRipple { },
                    shape = RoundedCornerShape(20.dp),
                    color = p.surface,
                    border = BorderStroke(1.dp, p.border),
                    shadowElevation = 18.dp,
                ) {
                    Column(Modifier.padding(8.dp)) {
                        PathDialogHeader(shownPath = shownPath, onDismiss = onDismiss)
                        ClaudeMenuDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = listMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (crumbs.size > 1) {
                                ClaudeMenuSection("层级")
                                crumbs.forEach { crumb ->
                                    MtMenuRow(
                                        icon = if (crumb.current) MtIcon.LOCATE else MtIcon.FOLDER,
                                        label = crumb.label.ifBlank { "/" },
                                        checked = crumb.current,
                                        onClick = { onPick(crumb) },
                                    )
                                }
                                ClaudeMenuDivider()
                            }
                            ClaudeMenuSection("操作")
                            MtMenuRow(MtIcon.KEYBOARD, "跳转到路径…") { onGoto() }
                            MtMenuRow(MtIcon.COPY, "复制完整路径") { onCopy() }
                            MtMenuRow(MtIcon.BOOKMARK, "添加书签") { onBookmark() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PathDialogHeader(shownPath: String, onDismiss: () -> Unit) {
    val p = currentClaudePalette()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 陶土橙的轻量图标块：Claude 式的「一个暖色锚点」
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(p.hover),
            contentAlignment = Alignment.Center,
        ) {
            MtVectorIcon(icon = MtIcon.FOLDER, size = 16.dp, tint = p.accent)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                "路径",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    letterSpacing = 0.09.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = p.muted,
            )
            Text(
                shownPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = p.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(p.hover)
                .clickableNoRipple(onClick = onDismiss)
                .semantics {
                    role = Role.Button
                    contentDescription = "关闭路径菜单"
                },
            contentAlignment = Alignment.Center,
        ) {
            MtVectorIcon(icon = MtIcon.CLOSE, size = 16.dp, tint = p.muted)
        }
    }
}
