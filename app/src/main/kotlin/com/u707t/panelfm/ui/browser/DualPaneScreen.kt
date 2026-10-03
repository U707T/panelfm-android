package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlinx.coroutines.launch

/**
 * 双列主界面（对齐 MT 管理器 · 官方手册）：
 *  - **打开即是双列**；顶部 ≡ + 路径（中间省略）+ 统计 + ⋮
 *  - 列表首行 `..`，行高固定，左右滑动任意文件即进入多选；多选下支持 全选 / 反选 / 类选
 *  - 底部 `← → ＋ ⇄ ↑`：长按 ⇄ = 过滤（支持 /正则、!/正则、!否定），长按 ↑ = 路径跳转，底栏上滑 = 书签
 *  - 长按文件 → MT 动作菜单（复制 -> / 移动 -> 带 ● 支持长按单窗口操作）
 */
@Composable
fun DualPaneScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenLanScan: () -> Unit,
    onOpenPreview: (VfsUri) -> Unit,
    onOpenEditor: (VfsUri) -> Unit,
    onOpenDiff: (VfsUri, VfsUri) -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val ui by container.browser.state.collectAsState()
    val controller = container.browser
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var rowAction by remember { mutableStateOf<FileMetadata?>(null) }
    var toolsFor by remember { mutableStateOf<FileMetadata?>(null) }
    var renaming by remember { mutableStateOf<FileMetadata?>(null) }
    var deleting by remember { mutableStateOf<FileMetadata?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showCrossMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var gotoPath by remember { mutableStateOf(false) }
    var filterInput by remember { mutableStateOf(false) }
    var singleWindowOp by remember { mutableStateOf<TransferOp?>(null) }
    var permissionFor by remember { mutableStateOf<FileMetadata?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openWithFor by remember { mutableStateOf<FileMetadata?>(null) }
    var openWithManage by remember { mutableStateOf(false) }
    var batchRenameFor by remember { mutableStateOf<List<FileMetadata>?>(null) }
    var compressFormatPicker by remember { mutableStateOf(false) }
    var archiveRename by remember { mutableStateOf<FileMetadata?>(null) }

    val focused = ui.focusedPane
    val focusSide = ui.focused
    val target: FileMetadata? = remember(rowAction, focused.selection.size) {
        rowAction ?: focused.selectedItems.firstOrNull() ?: focused.items.firstOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------- 顶部栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTextButton("≡") { showQuickMenu = true }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
            ) {
                // 面包屑：点任意一级跳转；长按复制完整路径（MT 路径栏）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val full = focused.uri.displayPath.ifEmpty { "/" }
                    val segments = full.trim('/').split('/').filter { it.isNotEmpty() }
                    val shown = if (segments.size > 4) segments.takeLast(4) else segments
                    val prefixBase = "/" + segments.dropLast(shown.size).joinToString("/")
                    if (segments.size > shown.size) {
                        Text(
                            "…",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .clickableNoRipple { controller.open(focusSide, focused.uri.withPath("/"), focused.tab.connectionId, focused.tab.label) },
                        )
                    }
                    shown.forEachIndexed { index, seg ->
                        val path = (prefixBase.trimEnd('/') + "/" + shown.take(index + 1).joinToString("/")).replace("//", "/")
                        Text(
                            seg + "/",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (index == shown.lastIndex) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 1.dp)
                                .combinedClickable(
                                    onClick = {
                                        controller.open(
                                            focusSide,
                                            focused.uri.withPath(path),
                                            focused.tab.connectionId,
                                            focused.tab.label,
                                        )
                                    },
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(focused.uri.toString()))
                                        controller.showStatus("已复制路径：${focused.uri.displayPath}")
                                    },
                                ),
                        )
                    }
                }
                Text(
                    buildString {
                        append("文件夹: ").append(focused.dirCount)
                        append("  文件: ").append(focused.fileCount)
                        focused.space?.let {
                            append("  储存: ").append(Fmt.size(it.total - it.free)).append("/").append(Fmt.size(it.total))
                        }
                        if (focused.filtered) append("  ·  已过滤")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconTextButton("⋮") { showMoreMenu = true }
        }

        HSeparator()

        // ---------------- 两个窗格
        Row(Modifier.weight(1f)) {
            val showLeft = !ui.singlePane || ui.focused == PaneSide.LEFT
            val showRight = !ui.singlePane || ui.focused == PaneSide.RIGHT
            if (showLeft) {
                PaneView(
                    container = container,
                    side = PaneSide.LEFT,
                    pane = ui.left,
                    focused = ui.focused == PaneSide.LEFT,
                    highlight = ui.highlight && ui.focused == PaneSide.LEFT,
                    controller = controller,
                    modifier = Modifier.weight(ui.splitRatio),
                    onRowAction = { rowAction = it },
                )
            }
            if (showLeft && showRight) {
                // 可拖动分隔条（MT：左右比例可调）
                Box(
                    Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            val widthPx = this.size.width.toFloat().coerceAtLeast(1f)
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                controller.setSplitRatio(ui.splitRatio + dragAmount.x / widthPx)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
            }
            if (showRight) {
                PaneView(
                    container = container,
                    side = PaneSide.RIGHT,
                    pane = ui.right,
                    focused = ui.focused == PaneSide.RIGHT,
                    highlight = ui.highlight && ui.focused == PaneSide.RIGHT,
                    controller = controller,
                    modifier = Modifier.weight(1f - ui.splitRatio),
                    onRowAction = { rowAction = it },
                )
            }
        }

        // ---------------- 任务条
        if (ui.tasks.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ui.tasks.take(2).forEach { snapshot -> TaskRow(snapshot, controller, onOpenTasks) }
                if (ui.tasks.size > 2) {
                    Text(
                        "还有 ${ui.tasks.size - 2} 个任务…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------- 底部：多选工具栏 或 命令栏
        if (focused.hasSelection) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextCommand("全选") { controller.selectAll(focusSide) }
                TextCommand("反选") { controller.invertSelection(focusSide) }
                TextCommand("类选") { controller.selectSameType(focusSide) }
                TextCommand("复制到对面") { controller.copyToOther(focusSide) }
                TextCommand("取消") { controller.clearSelection(focusSide) }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        // 底栏上滑 → 书签（MT 手册：从底栏上滑调出书签）
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f && container.settings.value.bookmarkSwipe) onOpenBookmarks()
                        }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomCommand("←", enabled = focused.tab.back.isNotEmpty()) { controller.back(focusSide) }
                BottomCommand("→", enabled = focused.tab.forward.isNotEmpty()) { controller.forward(focusSide) }
                BottomCommand("＋", onLongClick = { creatingFile = true }) { creatingFolder = true }
                Box {
                    BottomCommand(
                        "⇄",
                        highlighted = showCrossMenu,
                        onLongClick = { filterInput = true },   // 长按 = 过滤（MT）
                    ) { showCrossMenu = true }
                    DropdownMenu(expanded = showCrossMenu, onDismissRequest = { showCrossMenu = false }) {
                        DropdownMenuItem(text = { Text("复制到对面窗格") }, onClick = { showCrossMenu = false; controller.copyToOther() })
                        DropdownMenuItem(text = { Text("移动到对面窗格") }, onClick = { showCrossMenu = false; controller.moveToOther() })
                        DropdownMenuItem(text = { Text("同步（另一窗格跟随本窗格）") }, onClick = { showCrossMenu = false; controller.syncPath() })
                        DropdownMenuItem(text = { Text("交换窗口") }, onClick = { showCrossMenu = false; controller.swapPanes() })
                        DropdownMenuItem(text = { Text("压缩到对面（zip）") }, onClick = { showCrossMenu = false; controller.compressToOther() })
                        DropdownMenuItem(
                            text = { Text("进入压缩包") },
                            onClick = {
                                showCrossMenu = false
                                val item = focused.selectedItems.firstOrNull()
                                    ?: focused.items.firstOrNull { com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(it.name) != null }
                                if (item != null) controller.openArchiveInPane(focusSide, item)
                                else controller.showStatus("当前目录没有压缩包")
                            },
                        )
                        if (focused.uri.scheme == "archive") {
                            DropdownMenuItem(
                                text = { Text("添加对面选中项到压缩包（ZIP）") },
                                onClick = { showCrossMenu = false; controller.addToArchive(focusSide) },
                            )
                            DropdownMenuItem(
                                text = { Text("解压到对面窗格") },
                                onClick = { showCrossMenu = false; controller.extractTo(focusSide, ui.pane(focusSide.other).uri) },
                            )
                            DropdownMenuItem(
                                text = { Text("解压到压缩包所在目录") },
                                onClick = {
                                    showCrossMenu = false
                                    val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
                                    val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                                    val dir = host?.parent
                                    if (dir != null) controller.extractTo(focusSide, dir)
                                    else controller.showStatus("无法确定压缩包所在目录")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("测试压缩包完整性") },
                                onClick = { showCrossMenu = false; controller.testArchive(focusSide) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("比较两个目录") },
                            onClick = { showCrossMenu = false; controller.compareDirectories() },
                        )
                    }
                }
                BottomCommand("↑", enabled = focused.uri.parent != null, onLongClick = { gotoPath = true }) { controller.up(focusSide) }
            }
        }
    }

    // ---------------- ⋮ 菜单（MT 顺序）
    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
        DropdownMenuItem(text = { Text("刷新") }, onClick = { showMoreMenu = false; controller.refresh(focusSide) })
        DropdownMenuItem(text = { Text("输入路径…") }, onClick = { showMoreMenu = false; gotoPath = true })
        DropdownMenuItem(text = { Text("搜索 / 过滤…") }, onClick = { showMoreMenu = false; filterInput = true })
        DropdownMenuItem(text = { Text("全选") }, onClick = { showMoreMenu = false; controller.selectAll(focusSide) })
        DropdownMenuItem(text = { Text("过滤…") }, onClick = { showMoreMenu = false; showFilterDialog = true })
        DropdownMenuItem(text = { Text("排序方式…") }, onClick = { showMoreMenu = false; showSortDialog = true })
        DropdownMenuItem(
            text = { Text(if (focused.showHidden) "隐藏文件：已显示" else "隐藏文件：已隐藏") },
            onClick = { showMoreMenu = false; controller.toggleHidden(focusSide) },
        )
        DropdownMenuItem(text = { Text("添加书签") }, onClick = { showMoreMenu = false; controller.addBookmark(focusSide) })
        DropdownMenuItem(text = { Text("设为首页") }, onClick = { showMoreMenu = false; controller.setAsHome(focusSide) })
        DropdownMenuItem(text = { Text("交换窗口") }, onClick = { showMoreMenu = false; controller.swapPanes() })
        DropdownMenuItem(
            text = { Text(if (ui.singlePane) "切换为双列" else "切换为单列") },
            onClick = { showMoreMenu = false; controller.toggleSinglePane() },
        )
        DropdownMenuItem(
            text = { Text("打开终端（当前路径）") },
            onClick = { showMoreMenu = false; onOpenTerminal(focused.uri.displayPath.ifEmpty { "/" }) },
        )
        DropdownMenuItem(text = { Text("主页") }, onClick = { showMoreMenu = false; onOpenHome() })
        DropdownMenuItem(text = { Text("传输任务") }, onClick = { showMoreMenu = false; onOpenTasks() })
        DropdownMenuItem(text = { Text("设置") }, onClick = { showMoreMenu = false; onOpenSettings() })
        DropdownMenuItem(
            text = { Text("退出") },
            onClick = {
                showMoreMenu = false
                (context as? Activity)?.finishAffinity()
            },
        )
    }

    // ---------------- ≡ 菜单（快速位置 + 书签）
    DropdownMenu(expanded = showQuickMenu, onDismissRequest = { showQuickMenu = false }) {
        DropdownMenuItem(text = { Text("主页") }, onClick = { showQuickMenu = false; onOpenHome() })
        DropdownMenuItem(
            text = { Text("内部存储") },
            onClick = { showQuickMenu = false; controller.open(focusSide, VfsUri.of("local", "emulated", "/"), null, "内部存储") },
        )
        DropdownMenuItem(
            text = { Text("根目录 /") },
            onClick = { showQuickMenu = false; controller.open(focusSide, VfsUri.of("local", "root", "/"), null, "根目录") },
        )
        DropdownMenuItem(
            text = { Text("应用私有目录") },
            onClick = { showQuickMenu = false; controller.open(focusSide, VfsUri.of("local", "app", "/"), null, "应用目录") },
        )
        DropdownMenuItem(text = { Text("书签…") }, onClick = { showQuickMenu = false; onOpenBookmarks() })
        DropdownMenuItem(text = { Text("局域网扫描…") }, onClick = { showQuickMenu = false; onOpenLanScan() })
        val bookmarks = remember(showQuickMenu) { if (showQuickMenu) controller.bookmarks() else emptyList() }
        if (bookmarks.isNotEmpty()) {
            HSeparator()
            Text("  书签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bookmarks.take(8).forEach { bm ->
                DropdownMenuItem(
                    text = { Text(bm.name.ifEmpty { bm.uri.displayPath }, maxLines = 1) },
                    onClick = { showQuickMenu = false; controller.openBookmark(bm) },
                )
            }
        }
    }

    // ---------------- MT 动作菜单（长按文件）
    rowAction?.let { item ->
        val multi = focused.selection.size
        val subject = if (multi > 1) "已选 $multi 项" else item.name
        MtActionSheet(
            title = subject,
            actions = listOf(
                MtAction("copy_to", "复制 ->", "⧉", singleWindow = true),
                MtAction("move_to", "移动 ->", "✂", singleWindow = true),
                MtAction("delete", "删除", "🗑"),
                MtAction("rename", "重命名", "✎", enabled = multi <= 1),
                MtAction("tools", "工具", "🔧"),
                MtAction("compress", "压缩", "⬇"),
                MtAction("diff", "文件对比", "⇄", enabled = multi >= 1),
                MtAction("properties", "属性", "ⓘ", enabled = multi <= 1),
                MtAction("share", "分享", "⇪"),
                MtAction("open_with", "打开方式…", "✓", enabled = !item.isDirectory),
                MtAction("bookmark", "添加书签", "🔖"),
            ),
            onAction = { id ->
                rowAction = null
                when (id) {
                    "copy_to" -> controller.copyToOther(focusSide)
                    "move_to" -> controller.moveToOther(focusSide)
                    "delete" -> {
                        if (focused.uri.scheme == "archive") {
                            controller.deleteInsideArchive(focusSide, focused.selectedItems.ifEmpty { listOf(item) })
                        } else {
                            deleting = item
                        }
                    }
                    "rename" -> {
                        val picked = focused.selectedItems
                        when {
                            focused.uri.scheme == "archive" -> archiveRename = item
                            picked.size > 1 -> batchRenameFor = picked
                            else -> renaming = item
                        }
                    }
                    "diff" -> controller.startFileDiff(focusSide)
                    "tools" -> toolsFor = item
                    "compress" -> compressFormatPicker = true
                    "properties" -> controller.showProperties(item)
                    "share" -> shareItem(container, context, item) { msg -> controller.showStatus(msg) }
                    "open_with" -> openWithFor = item
                    "bookmark" -> {
                        controller.addBookmark(focusSide)
                    }
                }
            },
            onLongAction = { id ->
                rowAction = null
                when (id) {
                    "copy_to" -> singleWindowOp = TransferOp.COPY
                    "move_to" -> singleWindowOp = TransferOp.MOVE
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
                MtAction("copy_path", "复制路径", "⧉"),
                MtAction("md5", "校验值 MD5", "#"),
                MtAction("sha256", "校验值 SHA-256", "#"),
                MtAction("chmod", "修改权限", "🔒", enabled = !item.isDirectory.not()),
                MtAction("select_all", "全选", "☑"),
                MtAction("invert", "反选", "☐"),
                MtAction("same_type", "类选", "▣"),
                MtAction("exit", "退出多选", "✕"),
            ),
            onAction = { id ->
                toolsFor = null
                when (id) {
                    "copy_path" -> {
                        clipboard.setText(AnnotatedString(item.uri.toString()))
                        controller.showStatus("路径已复制：${item.uri.displayPath}")
                    }
                    "md5" -> controller.checksum(item.uri, "MD5") { r ->
                        message = "MD5" to (r ?: "计算失败")
                    }
                    "sha256" -> controller.checksum(item.uri, "SHA-256") { r ->
                        message = "SHA-256" to (r ?: "计算失败")
                    }
                    "chmod" -> permissionFor = item
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

    // ---------------- 各种输入框
    if (gotoPath) {
        TextInputDialog(
            title = "跳转路径",
            initial = focused.uri.displayPath,
            label = "路径",
            hint = "可写完整 URI（s3://bucket/dir、/share/sub），也可写相对路径",
            onConfirm = { input ->
                val text = input.trim()
                val dest = if (text.contains("://")) runCatching { VfsUri.parse(text) }.getOrNull()
                else focused.uri.withPath(if (text.startsWith("/")) text else "/$text")
                if (dest != null) controller.open(focusSide, dest, focused.tab.connectionId, focused.tab.label)
                else controller.showStatus("路径格式不正确")
            },
            onDismiss = { gotoPath = false },
        )
    }
    if (filterInput) {
        TextInputDialog(
            title = "过滤（长按同步按钮同款）",
            initial = focused.search,
            label = "关键字",
            hint = "普通文本=包含；!文本=不包含；/正则；!/正则=正则否定。留空清除。",
            onConfirm = { q -> controller.setSearch(focusSide, q) },
            onDismiss = { filterInput = false },
        )
    }
    if (singleWindowOp != null) {
        val op = singleWindowOp!!
        TextInputDialog(
            title = if (op == TransferOp.COPY) "复制到（本窗格内）" else "移动到（本窗格内）",
            initial = focused.uri.displayPath,
            label = "目标目录",
            hint = "单窗口操作：目标仍在当前窗格内，输入目录后立即执行",
            onConfirm = { path ->
                val dest = focused.uri.withPath(if (path.startsWith("/")) path else "/$path")
                if (op == TransferOp.COPY) controller.copyWithinPane(focusSide, dest)
                else controller.moveWithinPane(focusSide, dest)
            },
            onDismiss = { singleWindowOp = null },
        )
    }
    permissionFor?.let { item ->
        TextInputDialog(
            title = "修改权限（八进制）",
            initial = item.permissions?.let { Integer.toOctalString(it) } ?: "644",
            label = "权限，如 644 / 755",
            hint = "支持本地文件与支持 chmod 的协议（SFTP / FTP SITE CHMOD）",
            onConfirm = { octal ->
                val mode = runCatching { Integer.parseInt(octal, 8) }.getOrNull()
                if (mode == null) {
                    controller.showStatus("八进制格式不正确")
                } else {
                    scope.launch {
                        val vfs = container.locator.find(item.uri)
                        val ok = runCatching { vfs?.setPermissions(item.uri, mode) }.isSuccess
                        controller.showStatus(if (ok) "权限已修改为 $octal" else "该位置不支持修改权限")
                        if (ok) controller.refresh(focusSide)
                    }
                }
            },
            onDismiss = { permissionFor = null },
        )
    }
    // 压缩格式选择（MT 支持 zip / 7z / tar / tar.gz / tar.bz2）
    if (compressFormatPicker) {
        AlertDialog(
            onDismissRequest = { compressFormatPicker = false },
            title = { Text("压缩格式") },
            text = {
                Column {
                    com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.entries.forEach { fmt ->
                        TextButton(onClick = {
                            compressFormatPicker = false
                            controller.compressHere(focusSide, fmt)
                        }) { Text(fmt.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { compressFormatPicker = false }) { Text("关闭") } },
        )
    }
    // 压缩包内重命名（完整路径，可改父目录 = 移动）
    archiveRename?.let { item ->
        TextInputDialog(
            title = "重命名（压缩包内）",
            initial = item.name,
            label = "完整路径（可含目录）",
            hint = "MT 语义：重命名的是完整路径，改父目录即为移动",
            onConfirm = { newPath -> controller.renameInsideArchive(focusSide, item, newPath) },
            onDismiss = { archiveRename = null },
        )
    }
    message?.let { (title, body) ->
        MessageDialog(title, body) { message = null }
    }
    // 打开方式（MT：内置打开方式列表 + 长按设为默认 + 管理）
    openWithFor?.let { item ->
        val kind = MimeTypes.kindOf(item.extension)
        OpenWithDialog(
            fileName = item.name,
            options = listOf(
                OpenWithOption(PreviewMode.TEXT, available = kind == MimeTypes.Kind.TEXT || kind == MimeTypes.Kind.CODE || kind == MimeTypes.Kind.OTHER),
                OpenWithOption(PreviewMode.EDITOR, available = kind != MimeTypes.Kind.IMAGE && kind != MimeTypes.Kind.AUDIO && kind != MimeTypes.Kind.VIDEO),
                OpenWithOption(PreviewMode.HEX, available = true),
                OpenWithOption(PreviewMode.IMAGE, available = kind == MimeTypes.Kind.IMAGE),
                OpenWithOption(PreviewMode.MEDIA, available = kind == MimeTypes.Kind.AUDIO || kind == MimeTypes.Kind.VIDEO),
                OpenWithOption(
                    PreviewMode.ARCHIVE,
                    available = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name) != null,
                ),
                OpenWithOption(PreviewMode.FONT, available = kind == MimeTypes.Kind.FONT),
                OpenWithOption(PreviewMode.SYSTEM, available = item.uri.scheme == "local"),
            ),
            defaultMode = controller.defaultOpenMode(item),
            onPick = { mode ->
                openWithFor = null
                if (mode == PreviewMode.SYSTEM) {
                    openWithSystem(container, context, item) { msg -> controller.showStatus(msg) }
                } else {
                    controller.openWith(item, mode)
                }
            },
            onSetDefault = { mode ->
                controller.setDefaultOpenMode(item, mode)
            },
            onManage = { openWithManage = true },
            onDismiss = { openWithFor = null },
        )
    }
    if (openWithManage) {
        var entries by remember { mutableStateOf(controller.openModes()) }
        OpenWithManageDialog(
            entries = entries,
            onDelete = { ext -> controller.clearOpenMode(ext); entries = controller.openModes() },
            onDismiss = { openWithManage = false },
        )
    }
    // 批量重命名（MT 表达式）
    batchRenameFor?.let { items ->
        BatchRenameDialog(
            items = items,
            onConfirm = { expression ->
                scope.launch {
                    var ok = 0
                    items.forEachIndexed { index, fm ->
                        val newName = BatchRename.newName(expression, fm, index)
                        if (newName != fm.name && newName.isNotBlank()) {
                            val target = fm.uri.parent?.child(newName)
                            val vfs = container.locator.find(fm.uri)
                            if (target != null && vfs != null) {
                                val done = runCatching { vfs.rename(fm.uri, target) }.getOrDefault(false)
                                if (done) ok++
                            }
                        }
                    }
                    controller.showStatus("批量重命名完成：$ok / ${items.size}")
                    controller.clearSelection(focusSide)
                    controller.refreshAll()
                }
            },
            onDismiss = { batchRenameFor = null },
        )
    }
    renaming?.let { item ->
        TextInputDialog(
            title = "重命名",
            initial = item.name,
            onConfirm = { newName -> controller.rename(item.uri, newName, focusSide) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = "删除",
            message = "确定删除「${item.name}」？" + if (item.isDirectory) "（含目录内容）" else "",
            confirmText = "删除",
            onConfirm = { controller.deleteSelected(focusSide) },
            onDismiss = { deleting = null },
        )
    }
    if (creatingFolder) {
        TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onConfirm = { name -> controller.createFolder(focusSide, name) },
            onDismiss = { creatingFolder = false },
        )
    }
    if (creatingFile) {
        TextInputDialog(
            title = "新建文件",
            label = "文件名",
            onConfirm = { name -> controller.createFile(focusSide, name) },
            onDismiss = { creatingFile = false },
        )
    }

    // ---------------- 排序 / 过滤 对话框
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    SortBy.entries.forEach { by ->
                        TextButton(onClick = {
                            val asc = if (focused.sort.by == by) !focused.sort.ascending else true
                            controller.setSort(focusSide, SortSpec(by, asc, focused.sort.dirsFirst))
                            showSortDialog = false
                        }) {
                            Text(
                                sortLabel(by) + if (focused.sort.by == by) (if (focused.sort.ascending) " ↑" else " ↓") else "",
                                color = if (focused.sort.by == by) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = {
                        controller.setSort(focusSide, focused.sort.copy(dirsFirst = !focused.sort.dirsFirst))
                        showSortDialog = false
                    }) { Text(if (focused.sort.dirsFirst) "文件夹置顶：开" else "文件夹置顶：关") }
                }
            },
            confirmButton = { TextButton(onClick = { showSortDialog = false }) { Text("关闭") } },
        )
    }
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("过滤（按类型）") },
            text = {
                Column {
                    TextButton(onClick = { controller.setFilter(focusSide, null); showFilterDialog = false }) {
                        Text("全部", color = if (focused.filterKind == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    listOf(
                        MimeTypes.Kind.IMAGE to "图片",
                        MimeTypes.Kind.VIDEO to "视频",
                        MimeTypes.Kind.AUDIO to "音频",
                        MimeTypes.Kind.TEXT to "文本",
                        MimeTypes.Kind.CODE to "代码",
                        MimeTypes.Kind.ARCHIVE to "压缩包",
                        MimeTypes.Kind.FONT to "字体",
                        MimeTypes.Kind.PDF to "PDF",
                    ).forEach { (kind, label) ->
                        TextButton(onClick = { controller.setFilter(focusSide, kind.name); showFilterDialog = false }) {
                            Text(
                                label,
                                color = if (focused.filterKind == kind.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFilterDialog = false }) { Text("关闭") } },
        )
    }

    // ---------------- 拖拽幽灵与落点提示
    ui.drag?.let { drag ->
        Box(Modifier.fillMaxSize()) {
            ui.dropHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                if (drag.sources.size > 1) "${drag.sources.size} 项" else (drag.sources.firstOrNull()?.name ?: "拖拽中"),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(drag.x.toInt() - 40, drag.y.toInt() - 30) }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    // ---------------- 状态提示
    ui.status?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.padding(bottom = 66.dp),
                action = { TextButton(onClick = { controller.consumeStatus() }) { Text("知道了") } },
            ) { Text(msg) }
        }
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            controller.consumeStatus()
        }
    }

    // ---------------- 冲突 / 移动确认 / 属性
    ui.renameConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { controller.dismissRenameConflict() },
            title = { Text("目标名称已存在") },
            text = { Text("「${conflict.target.name}」已存在，与源文件同名（都不是文件夹）。请选择处理方式：") },
            confirmButton = { TextButton(onClick = { controller.resolveRenameConflict("swap") }) { Text("交换") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.resolveRenameConflict("delete") }) { Text("删除目标") }
                    TextButton(onClick = { controller.resolveRenameConflict("backup") }) { Text("备份(.bak)") }
                    TextButton(onClick = { controller.dismissRenameConflict() }) { Text("取消") }
                }
            },
        )
    }
    ui.pendingMove?.let { pending ->
        MoveConfirmDialog(pending, onConfirm = { controller.confirmMove() }, onCancel = { controller.cancelMove() })
    }
    ui.conflict?.let { info ->
        ConflictDialog(info) { policy, applyAll -> controller.resolveConflict(policy, applyAll) }
    }
    ui.diff?.let { diff ->
        FolderDiffDialog(
            result = diff,
            onCopyOnlyLeft = { controller.copyDiffOnlyLeft() },
            onCopyNewer = { controller.copyDiffNewer() },
            onDismiss = { controller.dismissDiff() },
        )
    }
    ui.property?.let { item ->
        PropertiesDialog(item, space = focused.space?.let { Fmt.transferred(it.total - it.free, it.total) }) {
            controller.dismissProperties()
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun BottomCommand(
    symbol: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .let { base ->
                if (onLongClick != null) {
                    base.pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = null,
                            onLongPress = { onLongClick() },
                            onTap = { if (enabled) onClick() },
                        )
                    }
                } else base.clickableNoRipple(enabled, onClick)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                highlighted -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
        )
    }
}

@Composable
private fun TextCommand(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun sortLabel(by: SortBy): String = when (by) {
    SortBy.NAME -> "按名称"
    SortBy.SIZE -> "按大小"
    SortBy.TIME -> "按时间"
    SortBy.TYPE -> "按类型"
}

/** 分享：本地文件走 FileProvider（可分享给任何应用） */
internal fun shareItem(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再分享")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    if (!file.exists()) {
        onMessage("文件不存在")
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        onMessage("无法生成分享链接")
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享 ${item.name}")) }
        .onFailure { onMessage("没有可用的分享目标") }
}

/** 打开方式：交给系统选择器 */
internal fun openWithSystem(
    container: AppContainer,
    context: android.content.Context,
    item: FileMetadata,
    onMessage: (String) -> Unit,
) {
    if (item.uri.scheme != "local") {
        onMessage("网络文件请先复制到本地再打开")
        return
    }
    val file = File(container.localVfs.absolutePath(item.uri))
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return onMessage("无法生成打开链接")
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "打开方式")) }
        .onFailure { onMessage("没有可用的应用") }
}

@Composable
private fun TaskRow(snapshot: TransferTaskSnapshot, controller: BrowserController, onOpenTasks: () -> Unit) {
    val state = snapshot.state
    val progress = when (state) {
        is TaskState.Running -> if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
        is TaskState.Done -> 1f
        else -> 0f
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (snapshot.op == TransferOp.COPY) "复制" else "移动"} · ${snapshot.subtitle}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                is TaskState.Running, TaskState.Queued ->
                    TextButton(onClick = { controller.pauseTask(snapshot.id) }) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                TaskState.Paused ->
                    TextButton(onClick = { controller.resumeTask(snapshot.id) }) { Text("继续", style = MaterialTheme.typography.labelSmall) }
                else -> {}
            }
            TextButton(onClick = { controller.cancelTask(snapshot.id) }) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = onOpenTasks) { Text("详情", style = MaterialTheme.typography.labelSmall) }
        }
        ThinProgressBar(progress)
    }
}
