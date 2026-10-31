package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.mtCombinedClickable
import com.u707t.panelfm.core.vfs.local.LocalVolumes

/**
 * 顶部**可点面包屑**（对齐 MT / NP 的路径栏）。
 *
 * 交互：
 *  - 点祖先层级 = 直接跳过去（不用再开「输入路径」对话框）；
 *  - 点当前层级 / 右侧 ▾ = 路径菜单（跳转 / 复制 / 书签 / 另一窗格 / 回根）；
 *  - 长按任意层级 = 复制该层完整路径（MT「已复制完整路径到剪贴板」）；
 *  - 横向可滑动，切目录时自动滚到最右端 —— 当前目录永远可见。
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
    // 卷列表要读 getExternalFilesDirs：每个页面组合只算一次
    val volumes = remember(context) { LocalVolumes.volumes(context) }
    val crumbs = remember(focused.uri, focused.tab.label, volumes) {
        pathCrumbs(focused.uri, focused.tab.label, volumes)
    }
    val fullPath = remember(focused.uri, volumes) { pathTextOf(focused.uri, volumes) }
    val scroll = rememberScrollState()
    var menu by remember { mutableStateOf(false) }

    // 路径变长 / 变深后旧滚动位置会停在中间 —— 每次都贴到最右（当前目录）
    LaunchedEffect(focused.uri.toString(), scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }

    fun copyPath(text: String) {
        clipboard.setText(AnnotatedString(text))
        controller.showStatus("已复制完整路径到剪贴板")
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Text(
                        "›",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MtSpec.TopBarSubText,
                        modifier = Modifier.padding(horizontal = 1.dp),
                    )
                }
                PathCrumbChip(
                    crumb = crumb,
                    onTap = {
                        if (crumb.current) menu = true
                        else controller.open(focusSide, crumb.uri, label = crumb.mountLabel)
                    },
                    onLongPress = { copyPath(pathTextOf(crumb.uri, volumes)) },
                )
            }
        }

        // 固定在右端的路径菜单：路径很长、面包屑滚到左边时它也始终可见
        Box {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .mtCombinedClickable(onClick = { menu = true })
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "路径菜单"
                    },
            ) {
                MtVectorIcon(icon = MtIcon.ARROW_DOWN, size = 16.dp, tint = MtSpec.TopBarSubText)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                PathMenuItems(
                    focused = focused,
                    focusSide = focusSide,
                    controller = controller,
                    crumbs = crumbs,
                    fullPath = fullPath,
                    onDismiss = { menu = false },
                    onCopy = { copyPath(fullPath) },
                    onGoto = { menu = false; ds.gotoPath = true },
                )
            }
        }
    }
}

@Composable
private fun PathCrumbChip(
    crumb: PathCrumb,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val current = crumb.current
    Text(
        crumb.label.ifBlank { "/" },
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = 16.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (current) MtSpec.TopBarText else MtSpec.TopBarSubText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 176.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (current) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .mtCombinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics {
                role = Role.Button
                val suffix = if (current) "，当前目录，打开路径菜单" else "，点击跳转"
                contentDescription = "路径层级 ${crumb.label}$suffix"
            },
    )
}

@Composable
private fun PathMenuItems(
    focused: PaneState,
    focusSide: PaneSide,
    controller: BrowserController,
    crumbs: List<PathCrumb>,
    fullPath: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onGoto: () -> Unit,
) {
    val inArchive = focused.uri.scheme == "archive"
    MtMenuRow(MtIcon.KEYBOARD, "跳转到路径…") { onGoto() }
    MtMenuRow(MtIcon.COPY, "复制完整路径") {
        onDismiss()
        onCopy()
    }
    MtMenuRow(MtIcon.BOOKMARK, "添加书签") {
        onDismiss()
        controller.addBookmark(focusSide)
    }
    MtMenuRow(MtIcon.SYNC, "在另一窗格打开") {
        onDismiss()
        controller.open(focusSide.other, focused.uri, focused.tab.connectionId, focused.tab.label)
        controller.showStatus("已在另一窗格打开")
    }
    MtMenuRow(MtIcon.HOME, if (inArchive) "回到压缩包根目录" else "回到根目录") {
        onDismiss()
        controller.open(focusSide, PathInput.mountRootOf(focused.uri))
    }
    if (inArchive) {
        // 压缩包宿主层级就在面包屑里，但给一个显式出口（MT 的动作菜单习惯）
        val host = PathInput.archiveHostOf(focused.uri)
        val parent = host?.parent
        if (parent != null) {
            MtMenuRow(MtIcon.EXIT, "退出压缩包") {
                onDismiss()
                controller.open(focusSide, parent, label = crumbs.firstOrNull()?.mountLabel)
            }
        }
    }
    // 菜单底部把完整路径显示出来：长路径被省略时，用户能在这里核对 / 一键复制
    Text(
        fullPath,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(6.dp))
            .mtCombinedClickable(
                onClick = {
                    onDismiss()
                    onCopy()
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics {
                role = Role.Button
                contentDescription = "复制完整路径 $fullPath"
            },
    )
}
