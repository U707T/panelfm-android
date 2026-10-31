package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.mtCombinedClickable
import com.u707t.panelfm.core.vfs.local.LocalVolumes

/**
 * 顶部路径（对齐 MT 原版观感）。
 *
 * 视觉上只有**一行居中路径 + 一行统计**：没有分段底色、没有分隔符、没有常驻 ▾。
 * 交互收进路径本身：
 *  - 点路径 → 弹出层级菜单（祖先目录 + 跳转 / 复制 / 书签）；
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

    fun pick(crumb: PathCrumb) {
        menu = false
        if (!crumb.current) controller.open(focusSide, crumb.uri, label = crumb.mountLabel)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box {
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // 祖先层级：保留「点哪级跳哪级」，但不占顶栏视觉
                if (crumbs.size > 1) {
                    crumbs.forEach { crumb ->
                        MtMenuRow(
                            icon = if (crumb.current) MtIcon.LOCATE else MtIcon.FOLDER,
                            label = crumb.label.ifBlank { "/" },
                            checked = crumb.current,
                            onClick = { pick(crumb) },
                        )
                    }
                    HSeparator()
                }
                MtMenuRow(MtIcon.KEYBOARD, "跳转到路径…") {
                    menu = false
                    ds.gotoPath = true
                }
                MtMenuRow(MtIcon.COPY, "复制完整路径") {
                    menu = false
                    copyPath()
                }
                MtMenuRow(MtIcon.BOOKMARK, "添加书签") {
                    menu = false
                    controller.addBookmark(focusSide)
                }
            }
        }
    }
}
