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
// 从 DualPaneScreen 拆出（重审 🔴1b）：⋮ 菜单 + 长按动作菜单（共享 BrowserDialogsState）。
// ================================================================================================

@Composable
internal fun BrowserMoreMenu(
    controller: BrowserController,
    ui: BrowserUiState,
    ds: BrowserDialogsState,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val focused = ui.focusedPane
    val focusSide = ui.focused
    with(ds) {
        // ---------------- ⋮ 菜单
        //   顺序**逐条对照 MT 截图**（右半屏的菜单）：
        //     刷新 / 搜索 / 全选 / 过滤 / 排序方式 / 隐藏文件 ▶ / 添加书签 / 设为首页 /
        //     交换窗口 / 设置 / 退出
        //   PanelFM 的扩展项（粘贴 / 类型过滤 / 浏览模式 / 比较目录 / 网络初始路径 /
        //   压缩包专属）插在同语义位置，不改变 MT 的前几项顺序。
        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
            if (!hiddenSub) {
                MtMenuRow(MtIcon.SYNC, "刷新") { showMoreMenu = false; controller.refresh(focusSide) }
                MtMenuRow(MtIcon.SEARCH, "搜索") { showMoreMenu = false; showSearch = true }
                // F13：结果被用户收起后，从这里重新打开（不再被中间结果自动弹回来）
                if (searchResultsDismissed && searchResults != null) {
                    MtMenuRow(MtIcon.SEARCH, "搜索结果（${searchResults?.size ?: 0}）${if (searching) " · 搜索中" else ""}") {
                        showMoreMenu = false
                        searchResultsOpen = true
                        searchResultsDismissed = false
                    }
                }
                MtMenuRow(MtIcon.SELECT_ALL, "全选") { showMoreMenu = false; controller.selectAll(focusSide) }
                MtMenuRow(MtIcon.LOW_PRIORITY, "过滤") { showMoreMenu = false; filterInput = true }
                // 已过滤时给一个显式出口（不必再打开对话框清空）
                if (focused.filtered) {
                    MtMenuRow(MtIcon.CLOSE, "清除过滤") {
                        showMoreMenu = false
                        controller.setSearch(focusSide, "")
                        controller.setFilter(focusSide, null)
                        controller.refresh(focusSide)
                        controller.showStatus("已清除过滤")
                    }
                }
                MtMenuRow(MtIcon.SORT, "排序方式") { showMoreMenu = false; showSortDialog = true }
                // 隐藏文件 ▶（MT：带勾选态的子菜单）
                MtMenuRow(MtIcon.EYE_OFF, "隐藏文件", trailing = MtIcon.CHEVRON_R) { hiddenSub = true }
                MtMenuRow(MtIcon.BOOKMARK, "添加书签") { showMoreMenu = false; controller.addBookmark(focusSide) }
                MtMenuRow(MtIcon.HOME, "设为首页") { showMoreMenu = false; controller.setAsHome(focusSide) }
                MtMenuRow(MtIcon.SWAP, "交换窗口") {
                    showMoreMenu = false
                    controller.swapPanes()
                    controller.showStatus("已交换窗口")
                }
                // ---- 以下是 PanelFM 的扩展项（MT 的 ⋮ 里没有，但语义上属于同一层）----
                MtMenuRow(MtIcon.KEYBOARD, "输入路径") { showMoreMenu = false; gotoPath = true }
                MtMenuRow(MtIcon.COPY, "复制到剪贴板") { showMoreMenu = false; controller.copySelectionToClipboard(focusSide) }
                if (controller.hasClipboard) {
                    MtMenuRow(MtIcon.PASTE, "粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide) }
                    MtMenuRow(MtIcon.CUT, "移动粘贴到当前目录") { showMoreMenu = false; controller.pasteFromClipboard(focusSide, move = true) }
                }
                // MT 0x7f1104ab「已设置为该网络存储的初始路径」：把当前路径写回连接的初始路径
                if (focused.uri.scheme != "local" && focused.uri.scheme != "archive") {
                    MtMenuRow(MtIcon.LOCATE, "设为该网络存储的初始路径") {
                        showMoreMenu = false
                        controller.setAsConnectionInitialPath(focusSide)
                    }
                }
                MtMenuRow(MtIcon.SYNC, "同步（另一窗格跟随本窗格）") { showMoreMenu = false; controller.syncPath() }
                MtMenuRow(MtIcon.VIEW_SIDEBAR, "新建标签页") { showMoreMenu = false; controller.newTab(focusSide) }
                if (focused.uri.scheme == "archive") {
                    // MT：压缩包内时，右上角菜单提供「测试压缩包完整性」与解压
                    MtMenuRow(MtIcon.VERIFIED, "测试压缩包完整性") { showMoreMenu = false; controller.testArchive(focusSide) }
                    MtMenuRow(MtIcon.ARCHIVE, "解压到对面窗格") {
                        showMoreMenu = false
                        controller.extractTo(focusSide, ui.pane(focusSide.other).uri)
                    }
                    MtMenuRow(MtIcon.FOLDER, "解压到压缩包所在目录") {
                        showMoreMenu = false
                        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
                        val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                        val dir = host?.parent
                        if (dir != null) controller.extractTo(focusSide, dir)
                        else controller.showStatus("无法确定压缩包所在目录")
                    }
                    // MT 0x7f0c00ce「解压」对话框：三个单选（单独的文件夹 / 当前目录 / 文件夹…）+ 基于另一窗口路径
                    MtMenuRow(MtIcon.ARCHIVE, "解压…") {
                        showMoreMenu = false
                        extractDirPicker = true
                    }
                    MtMenuRow(MtIcon.UPLOAD, "添加对面选中项到压缩包") { showMoreMenu = false; controller.addToArchive(focusSide) }
                }
                MtMenuRow(MtIcon.COMPARE, "比较两个目录") { showMoreMenu = false; controller.compareDirectories() }
                // 浏览模式（MT 0x7f1101fb/1fc/1fd/1fe：单列 / 双列 / 自动切换）
                Box {
                    MtMenuRow(MtIcon.LAYERS, "浏览模式（${ui.effectiveBrowseMode.label}）", trailing = MtIcon.CHEVRON_R) { browseSub = true }
                    DropdownMenu(expanded = browseSub, onDismissRequest = { browseSub = false }) {
                        BrowseMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.label + if (mode == ui.browseMode) "  ✓" else "",
                                        color = if (mode == ui.browseMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = { showMoreMenu = false; browseSub = false; controller.setBrowseMode(mode) },
                            )
                        }
                    }
                }
                // 类型过滤（MT 的「过滤」下拉：文件夹 / 图片 / 视频 …）
                MtMenuRow(
                    MtIcon.FILE,
                    "类型过滤" + (focused.filterKind?.let { "（已过滤）" } ?: ""),
                ) { showMoreMenu = false; showTypeFilter = true }
                MtMenuRow(MtIcon.SETTINGS, "设置") { showMoreMenu = false; onOpenSettings() }
                MtMenuRow(MtIcon.EXIT, "退出") {
                    showMoreMenu = false
                    (context as? Activity)?.finishAffinity()
                }
            } else {
                // 隐藏文件 ▶ 子菜单（MT：带勾选态）
                MtMenuRow(MtIcon.CHEVRON_L, "隐藏文件") { hiddenSub = false }
                MtMenuRow(MtIcon.CHECK, "显示隐藏文件", checked = focused.showHidden) {
                    showMoreMenu = false
                    if (!focused.showHidden) controller.toggleHidden(focusSide)
                }
                MtMenuRow(MtIcon.CLOSE, "不显示隐藏文件", checked = !focused.showHidden) {
                    showMoreMenu = false
                    if (focused.showHidden) controller.toggleHidden(focusSide)
                }
            }
        }
    }
}

@Composable
internal fun BrowserActionSheets(
    container: AppContainer,
    controller: BrowserController,
    ui: BrowserUiState,
    ds: BrowserDialogsState,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focused = ui.focusedPane
    val focusSide = ui.focused
    with(ds) {
        // ---------------- MT 动作菜单（长按文件，截图2 布局）
        rowAction?.let { item ->
            // 长按弹的是「这一项」的菜单：它若在当前选择集里 → 作用于整个选择集，否则只作用于这一项。
            // （不能让下游退化成 targetSources()：没有选择时那会指向**整个目录**，见 menuTargets 注释）
            val picked = menuTargets(focused.selectedItems, item)
            val multi = picked.size
            val pickedUris = picked.map { it.uri }
            // MT 置灰规则：选中项含文件夹时，分享 / 打开方式 不可用（系统不支持分享文件夹）
            val anyDirectory = picked.any { it.isDirectory }
            // MT：同时选中两个文件时长按出现「文件对比」
            val twoFiles = picked.size == 2 && picked.none { it.isDirectory }
            MtActionSheet(
                actions = buildList {
                    // MT：箭头跟随目标窗口方向 —— 选中项在左窗格 → `复制 ->`；在右窗格 → `<- 复制`
                    add(MtAction("copy_to", crossPaneLabel("复制", focusSide), MtIcon.COPY, singleWindow = true))
                    add(MtAction("move_to", crossPaneLabel("移动", focusSide), MtIcon.CUT, singleWindow = true))
                    add(MtAction("delete", "删除", MtIcon.DELETE))
                    add(MtAction("rename", "重命名", MtIcon.EDIT))
                    add(MtAction("tools", "工具", MtIcon.BUILD))
                    add(MtAction("compress", "压缩", MtIcon.ARCHIVE))
                    if (twoFiles) add(MtAction("diff", "文件对比", MtIcon.COMPARE))
                    add(MtAction("properties", "属性", MtIcon.INFO, enabled = multi <= 1))
                    add(MtAction("share", "分享", MtIcon.SHARE, enabled = !anyDirectory))
                    add(MtAction("open_with", "打开方式…", MtIcon.CHECK, enabled = !anyDirectory))
                    add(MtAction("clipboard", "复制到剪贴板", MtIcon.PASTE))
                    add(MtAction("bookmark", "添加书签", MtIcon.BOOKMARK))
    
                    // ---- 按文件类型的二级菜单（MT 语义）：压缩包解压 / APK 安装等
                    val inArchive = focused.uri.scheme == "archive"
                    val single = picked.singleOrNull()
                    if (single != null && !single.isDirectory) {
                        val kind = TypeActions.kindOf(single.extension)
                        val installable = single.uri.scheme == "local"
                        val ids = TypeActions.typedActionIds(
                            extension = single.extension,
                            isDirectory = false,
                            inArchive = inArchive,
                            apkInstallable = installable,
                        )
                        if (ids.isNotEmpty()) {
                            val section = "对「${single.name}」"
                            ids.forEach { id ->
                                val icon = when (id) {
                                    TypeActions.ACTION_EXTRACT_HERE -> MtIcon.ARCHIVE
                                    TypeActions.ACTION_EXTRACT_OWN_FOLDER -> MtIcon.FOLDER
                                    TypeActions.ACTION_EXTRACT_PICK -> MtIcon.FOLDER
                                    TypeActions.ACTION_BROWSE_ARCHIVE -> MtIcon.EXPLORE
                                    TypeActions.ACTION_INSTALL -> MtIcon.GET_APP
                                    TypeActions.ACTION_APK_INFO -> MtIcon.ANDROID
                                    TypeActions.ACTION_EXTRACT_APK_ICON -> MtIcon.IMAGE
                                    TypeActions.ACTION_OPEN_INTERNAL -> MtIcon.EYE
                                    TypeActions.ACTION_EDIT_TEXT -> MtIcon.EDIT
                                    else -> MtIcon.CHECK
                                }
                                add(MtAction(id, TypeActions.labelOf(id, kind), icon, section = section))
                            }
                        }
                    }
                },
                onAction = { id ->
                    rowAction = null
                    when (id) {
                        "copy_to" -> controller.copyToOther(focusSide, overrideSources = pickedUris)
                        "move_to" -> controller.moveToOther(focusSide, overrideItems = picked)
                        "delete" -> {
                            if (focused.uri.scheme == "archive") {
                                controller.deleteInsideArchive(focusSide, picked)
                            } else {
                                deleting = picked
                            }
                        }
                        "rename" -> {
                            when {
                                focused.uri.scheme == "archive" -> archiveRename = item
                                picked.size > 1 -> batchRenameFor = picked
                                else -> renaming = picked.first()
                            }
                        }
                        "diff" -> controller.startFileDiff(focusSide)
                        "tools" -> toolsFor = item
                        "compress" -> {
                            compressTargets = picked
                            compressFormatPicker = true
                        }
                        "properties" -> controller.showProperties(picked.first())
                        "share" -> shareItems(container, context, picked) { msg -> controller.showStatus(msg) }
                        "open_with" -> openWithFor = picked.first()
                        "clipboard" -> controller.copySelectionToClipboard(focusSide, overrideSources = pickedUris)
    
                        "bookmark" -> {
                            controller.addBookmark(focusSide)
                        }
    
                        // ---- 按类型的二级菜单（MT 语义）
                        // ⚠️ 文件列表里的压缩包 → extractArchiveTo（整包展开）；
                        //    旧接线误用了「压缩包内部解压」的 extractTo → 「解压到当前目录」实际是**复制**压缩包自身。
                        TypeActions.ACTION_EXTRACT_HERE ->
                            if (item.uri.scheme == "archive") controller.extractTo(focusSide, focused.uri)
                            else controller.extractArchiveTo(item, focused.uri)
                        TypeActions.ACTION_EXTRACT_OWN_FOLDER -> {
                            val parent = item.uri.parent
                            when {
                                parent == null -> controller.showStatus("无法确定压缩包所在目录")
                                item.uri.scheme == "archive" -> controller.extractToOwnFolder(focusSide, parent)
                                else -> controller.extractArchiveTo(item, parent, ownFolder = true)
                            }
                        }
                        TypeActions.ACTION_EXTRACT_PICK -> {
                            extractDialogFor = item
                        }
                        TypeActions.ACTION_BROWSE_ARCHIVE -> {
                            controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE)
                        }
                        TypeActions.ACTION_INSTALL -> installApk(container, context, item) { msg ->
                            controller.showStatus(msg)
                        }
                        TypeActions.ACTION_APK_INFO -> {
                            controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.APK_INFO)
                        }
                        TypeActions.ACTION_EXTRACT_APK_ICON -> extractApkIcon(container, context, item) { msg ->
                            controller.showStatus(msg)
                        }
                        TypeActions.ACTION_OPEN_INTERNAL -> controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.AUTO)
                        TypeActions.ACTION_EDIT_TEXT -> controller.openWith(item, com.u707t.panelfm.ui.preview.PreviewMode.EDITOR)
                        TypeActions.ACTION_OPEN_WITH -> openWithFor = item
                    }
                },
                onLongAction = { id ->
                    rowAction = null
                    when (id) {
                        // F8：把本次菜单的显式目标一起记下来（菜单长按 = 单窗口操作，目标不能丢）
                        "copy_to" -> {
                            singleWindowTargets = picked
                            singleWindowOp = TransferOp.COPY
                        }
                        "move_to" -> {
                            singleWindowTargets = picked
                            singleWindowOp = TransferOp.MOVE
                        }
                    }
                },
                onDismiss = { rowAction = null },
            )
        }
    
        // ---------------- 工具子菜单（MT 的「工具」）
        toolsFor?.let { item ->
            MtActionSheet(
                title = "工具 · ${item.name}",
                actions = listOf(
                    MtAction("copy_path", "复制路径", MtIcon.COPY),
                    MtAction("crc32", "校验值 CRC32", MtIcon.TAG),
                    MtAction("md5", "校验值 MD5", MtIcon.TAG),
                    MtAction("sha1", "校验值 SHA-1", MtIcon.TAG),
                    MtAction("sha256", "校验值 SHA-256", MtIcon.TAG),
                    MtAction("chmod", "修改权限", MtIcon.LOCK),
                    MtAction(
                        "swap_name",
                        "交换文件名",
                        MtIcon.SWAP,
                        enabled = focused.selection.size == 2,
                    ),
                    MtAction("select_all", "全选", MtIcon.SELECT_ALL),
                    MtAction("invert", "反选", MtIcon.SELECT_ALL),
                    MtAction("same_type", "类选", MtIcon.LAYERS),
                    MtAction("exit", "退出多选", MtIcon.CLOSE),
                ),
                onAction = { id ->
                    toolsFor = null
                    when (id) {
                        "copy_path" -> {
                            clipboard.setText(AnnotatedString(item.uri.toString()))
                            controller.showStatus("路径已复制：${item.uri.displayPath}")
                        }
                        "crc32" -> controller.checksum(item.uri, "CRC32") { r ->
                            message = "CRC32" to (r ?: "计算失败")
                        }
                        "sha1" -> controller.checksum(item.uri, "SHA-1") { r ->
                            message = "SHA-1" to (r ?: "计算失败")
                        }
                        "md5" -> controller.checksum(item.uri, "MD5") { r ->
                            message = "MD5" to (r ?: "计算失败")
                        }
                        "sha256" -> controller.checksum(item.uri, "SHA-256") { r ->
                            message = "SHA-256" to (r ?: "计算失败")
                        }
                        "chmod" -> permissionFor = item
                        "swap_name" -> controller.swapSelectedNames(focusSide)
                        "select_all" -> controller.selectAll(focusSide)
                        "invert" -> controller.invertSelection(focusSide)
                        "same_type" -> controller.selectSameType(focusSide)
                        "exit" -> controller.clearSelection(focusSide)
                    }
                },
                onLongAction = {},
                onDismiss = { toolsFor = null },
            )
        }
    }
}
