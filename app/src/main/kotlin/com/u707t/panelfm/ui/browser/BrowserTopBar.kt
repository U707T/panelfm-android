package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.u707t.panelfm.core.ui.safeAreaPadding
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
// 从 DualPaneScreen 拆出（重审 🔴1b）：顶栏（路径+统计 / 动作条 / 标签页 / ⋮ 入口）。
// ================================================================================================

private fun tabLabelOf(tab: PaneTab): String = tab.label.ifBlank { tab.uri.name.ifBlank { "/" } }

@Composable
internal fun BrowserTopBar(
    container: AppContainer,
    controller: BrowserController,
    ui: BrowserUiState,
    ds: BrowserDialogsState,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val focused = ui.focusedPane
    val focusSide = ui.focused
    with(ds) {
        // ---------------- 顶部条（复刻 MT 0x7f0c0033 的 09046B + include 0x7f0c0034）
        //   MT 的顶栏**始终是深色**（浅色主题 #151515 / 深色 #303030）；
        //   结构：TabLayout(0903F9) + ⋮(0902B2) + ＋(090116) + 动作条(09022F) + 1px 分割线(0903F8)
        //   NORMAL 态：动作条整行 GONE；SELECTING 态：出现「复制 / 移动 / 删除」三连（可横向滚动）
        val topBarBg = if (LocalPanelDarkTheme.current) MtSpec.TopBarDark else MtSpec.TopBarLight
        Column(
            Modifier
                .fillMaxWidth()
                .background(topBarBg),
        ) {
            // ---- 顶栏主体（复刻 MT `0x7f0c0033` 的 `09046B` + 自定义 View `09038A`）
            //   MT 截图实测：**☰、路径/统计（居中）、⋮ 全在同一块里** ——
            //   ☰ 与 ⋮ 在垂直方向跨两行居中，中间是「路径（大字）+ 统计（小字）」。
            Row(
                Modifier
                    .fillMaxWidth()
                    // 顶栏固定高度（MT `0x7f070002` = 56dp）：
                    // 给整行一个**有界高度**，行内任何 fillMaxHeight 子项都只会填满这一行，
                    // 不会把顶栏撑到整屏（曾经的「黑屏怪页面」根因）
                    .height(MtSpec.TopBarHeight)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ☰ 侧边栏
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoRipple(onClick = onOpenDrawer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "打开侧边栏"
                        },
                ) {
                    MtVectorIcon(icon = MtIcon.MENU, size = 26.dp, tint = MtSpec.TopBarText)
                }

                // 中间：路径（大字，居中）+ 统计（小字，居中）
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // MT：路径完整显示，放不下才省略
                        focused.uri.displayPath.ifEmpty { "/" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = MtSpec.TopBarTitleSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MtSpec.TopBarText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("文件夹: ").append(focused.dirCount)
                            append("  文件: ").append(focused.fileCount)
                            focused.space?.let {
                                append("  储存: ")
                                    .append(Fmt.sizeCompact(it.total - it.free))
                                    .append("/")
                                    .append(Fmt.sizeCompact(it.total))
                            }
                            // MT 0x7f11063b「已选: %d」——多选计数必须实时更新
                            if (focused.hasSelection) append("  已选: ").append(focused.selection.size)
                            if (focused.filtered) append("  ·  已过滤")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = MtSpec.TopBarSubSize),
                        color = MtSpec.TopBarSubText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 动作条（09022F）：横向可滚动，选中项后出现（MT 的「复制/移动/删除」三连）
                if (focused.hasSelection) {
                    Row(
                        Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TopActionItems(
                            focused = focused,
                            focusSide = focusSide,
                            controller = controller,
                            onDelete = { picked -> deleting = picked },
                            onRename = { item: FileMetadata ->
                                when {
                                    focused.uri.scheme == "archive" -> archiveRename = item
                                    focused.selection.size > 1 -> batchRenameFor = focused.selectedItems
                                    else -> renaming = item
                                }
                            },
                            onCompress = {
                                compressTargets = focused.selectedItems
                                compressFormatPicker = true
                            },
                            onCopyTo = { controller.startPickDir(PickDirPurpose.COPY_TO) },
                            onMoveTo = { controller.startPickDir(PickDirPurpose.MOVE_TO) },
                            onProperties = { item: FileMetadata -> controller.showProperties(item) },
                            onShare = { items: List<FileMetadata> ->
                                // F9：顶栏「分享」与长按菜单共用多选实现（旧入口只取第一个文件）
                                shareItems(container, context, items) { msg -> controller.showStatus(msg) }
                            },
                            onTypedAction = { id, it ->
                                // 与长按菜单同一套处理（复用同一份实现，避免两处行为漂移）
                                when (id) {
                                    TypeActions.ACTION_EXTRACT_HERE ->
                                        if (it.uri.scheme == "archive") controller.extractTo(focusSide, focused.uri)
                                        else controller.extractArchiveTo(it, focused.uri)
                                    TypeActions.ACTION_INSTALL -> installApk(container, context, it) { msg ->
                                        controller.showStatus(msg)
                                    }
                                    TypeActions.ACTION_APK_INFO -> controller.openWith(
                                        it, com.u707t.panelfm.ui.preview.PreviewMode.APK_INFO,
                                    )
                                    TypeActions.ACTION_OPEN_INTERNAL -> controller.openWith(
                                        it, com.u707t.panelfm.ui.preview.PreviewMode.AUTO,
                                    )
                                }
                            },
                        )
                    }
                }

                // TabLayout + ＋：**只有多标签时才出现**（MT 截图：文件浏览态顶栏没有它们）
                if (focused.tabs.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        focused.tabs.forEachIndexed { index, tab ->
                            val active = index == focused.activeTab
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickableNoRipple { controller.switchTab(focusSide, index) }
                                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                    .semantics {
                                        role = Role.Tab
                                        contentDescription = "标签页 ${tabLabelOf(tab)}" + if (active) "，当前" else ""
                                    },
                            ) {
                                Text(
                                    tabLabelOf(tab),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MtSpec.TopBarText else MtSpec.TopBarSubText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 76.dp),
                                )
                                MtVectorIcon(
                                    icon = MtIcon.CLOSE,
                                    size = 14.dp,
                                    tint = MtSpec.TopBarSubText,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickableNoRipple { controller.closeTab(focusSide, index) }
                                        .padding(2.dp),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickableNoRipple { controller.newTab(focusSide) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "新建标签页"
                                },
                        ) {
                            MtVectorIcon(icon = MtIcon.PLUS, size = 22.dp, tint = MtSpec.TopBarText)
                        }
                    }
                }

                // ⋮ 更多（0902B2）
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoRipple { showMoreMenu = true; hiddenSub = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "更多菜单"
                        },
                ) {
                    MtVectorIcon(icon = MtIcon.MORE, size = 26.dp, tint = MtSpec.TopBarText)
                }
            }
        }
    }
}
