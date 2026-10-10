package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Spacer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtBottomIconButton
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.PaneEdgeShadow
import com.u707t.panelfm.core.ui.VDividerPx
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ================================================================================================
// 从 DualPaneScreen 拆出（重审 🔴1b）：底栏（选择当前目录 / 多选工具栏 / 命令栏）。
// ================================================================================================

@Composable
internal fun BrowserBottomBar(
    container: AppContainer,
    controller: BrowserController,
    ui: BrowserUiState,
    ds: BrowserDialogsState,
    onOpenBookmarks: () -> Unit,
) {
    val settings by container.settings.collectAsState()
    val focused = ui.focusedPane
    val focusSide = ui.focused
    with(ds) {
        // ---------------- 底部：MT「选择当前目录」模式 / 多选工具栏 / 命令栏
        if (ui.pickDirFor != null) {
            // MT 0x7f0c0025：底栏上方浮出一个全宽按钮「选择当前目录」（090084）+ 取消
            val purpose = ui.pickDirFor!!
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(MtSpec.BottomBarHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "选择当前目录：" + middleEllipsis(focused.uri.displayPath.ifEmpty { "/" }, maxChars = 22),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val picked = controller.confirmPickDir()
                    if (picked != null) {
                        val (purposeNow, dir) = picked
                        when (purposeNow) {
                            PickDirPurpose.EXTRACT -> {
                                // 两种来源：长按压缩包文件的「解压到文件夹…」 / 压缩包内选中项的解压
                                val pending = controller.pendingArchiveExtract
                                if (pending != null) {
                                    controller.clearPendingArchiveExtract()
                                    controller.extractArchiveTo(pending, dir)
                                } else {
                                    controller.extractTo(focusSide, dir)
                                }
                            }
                            PickDirPurpose.COPY_TO -> controller.copyTo(side = focusSide, dest = dir)
                            PickDirPurpose.MOVE_TO -> controller.moveTo(side = focusSide, dest = dir)
                        }
                    }
                }) { Text(purpose.label) }
                TextButton(onClick = { controller.cancelPickDir() }) { Text("取消") }
            }
        } else if (focused.hasSelection) {
            // MT：多选模式下底栏变成「全选 / 反选 / 类选 / …」动态按钮（0x7f11062b/632/633）
            val bottomExtraSel = settings.bottomBarPaddingDp.dp
            Column {
                DividerPx()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(MtSpec.BottomBarHeight + bottomExtraSel)
                        .padding(bottom = bottomExtraSel)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BottomTextCommand("全选") { controller.selectAll(focusSide) }
                    BottomTextCommand("反选") { controller.invertSelection(focusSide) }
                    BottomTextCommand("类选") { controller.selectSameType(focusSide) }
                    BottomTextCommand("同步", onLongClick = { filterInput = true }) { controller.syncPath() }
                    BottomTextCommand("取消") { controller.clearSelection(focusSide) }
                }
            }
        } else {
            val bottomExtra = settings.bottomBarPaddingDp.dp
            Column {
                // MT：底栏上方的 1px 分割线（`090110` / `09007D`）
                DividerPx()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(MtSpec.BottomBarHeight + bottomExtra)
                        .padding(bottom = bottomExtra)
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            // 底栏上滑 → 书签（MT 0x7f1107ca「从底部工具栏上滑即可打开书签」）。
                            // 文档 G.1.3 提醒：热区要在底栏上边缘之上、并与全面屏手势错开；
                            // 阈值取 MtGesture.SwipeBookmarkDp（32dp，文档 G.5），且本次手势只触发一次。
                            val threshold = MtGesture.SwipeBookmarkDp.dp.toPx()
                            var accumulated = 0f
                            var fired = false
                            detectVerticalDragGestures(
                                onDragStart = { accumulated = 0f; fired = false },
                                onDragEnd = { accumulated = 0f },
                                onDragCancel = { accumulated = 0f },
                            ) { _, dragAmount ->
                                accumulated += dragAmount
                                if (!fired && accumulated < -threshold && settings.bookmarkSwipe) {
                                    fired = true
                                    onOpenBookmarks()
                                }
                            }
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // MT 底栏：后退 / 前进 / 新建 / 同步 / 上级（§1.5 + G.3.3）
                    MtBottomIconButton(
                        icon = MtIcon.CHEVRON_L,
                        label = "后退",
                        enabled = focused.tab.back.isNotEmpty(),
                    ) { controller.back(focusSide) }
                    MtBottomIconButton(
                        icon = MtIcon.CHEVRON_R,
                        label = "前进",
                        enabled = focused.tab.forward.isNotEmpty(),
                    ) { controller.forward(focusSide) }
                    Box {
                        MtBottomIconButton(
                            icon = MtIcon.PLUS,
                            label = "新建（长按直接新建文件）",
                            onLongClick = { creatingFile = true },
                        ) { showCreateMenu = true }
                        // MT：新建（＋）弹出菜单
                        DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MtVectorIcon(icon = MtIcon.FOLDER, size = 22.dp)
                                        Text("新建文件夹", modifier = Modifier.padding(start = 12.dp))
                                    }
                                },
                                onClick = { showCreateMenu = false; creatingFolder = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MtVectorIcon(icon = MtIcon.FILE, size = 22.dp)
                                        Text("新建文件", modifier = Modifier.padding(start = 12.dp))
                                    }
                                },
                                onClick = { showCreateMenu = false; creatingFile = true },
                            )
                        }
                    }
                    // MT 底栏第 4 个按钮是「同步」（0x7f11069b）：点击 = 另一窗格跟随本窗格路径；
                    // 长按 = 过滤（MT 0x7f11028f「长按底部的「同步」按钮也可以进行过滤」）。
                    // ⚠️ 图标是 **swap_horiz（⇄）** 而不是刷新箭头 —— MT 截图实测，
                    // 语义是「把两个窗格同步成一样」= 两个相向的箭头（`0x7f0801f8`）。
                    MtBottomIconButton(
                        icon = MtIcon.SWAP,
                        label = "同步路径到另一窗口（长按过滤）",
                        onLongClick = { filterInput = true },
                    ) { controller.syncPath() }
                    // 压缩包内部也能「↑」（回到压缩包所在目录），与 PaneView 的 canGoUp 一致
                    MtBottomIconButton(
                        icon = MtIcon.UP,
                        label = "上级目录（长按输入路径）",
                        enabled = focused.uri.parent != null || focused.uri.scheme == "archive",
                        onLongClick = { gotoPath = true },
                    ) { controller.up(focusSide) }
                }
            }
        }
    }
}
