package com.u707t.panelfm.ui.browser

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.u707t.panelfm.core.vfs.VfsUri

/**
 * 双列主界面（对齐 MT 管理器）：
 *  - 顶部：≡（快速位置/书签） + 聚焦窗格路径 + 「文件夹 / 文件 / 储存」统计 + ⋮（MT 动作菜单）
 *  - 中部：左右两个独立窗格
 *  - 底部：← → ＋ ⇄ ↑
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
) {
    val ui by container.browser.state.collectAsState()
    val controller = container.browser
    val context = LocalContext.current

    var rowAction by remember { mutableStateOf<FileMetadata?>(null) }
    var renaming by remember { mutableStateOf<FileMetadata?>(null) }
    var deleting by remember { mutableStateOf<FileMetadata?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showCrossMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var gotoPath by remember { mutableStateOf(false) }

    val focused = ui.focusedPane

    Column(Modifier.fillMaxSize()) {
        // ---------------- 顶部栏（MT 风格）
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
                Text(
                    text = focused.uri.displayPath.ifEmpty { "/" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("文件夹: ").append(focused.dirCount)
                        append("  文件: ").append(focused.fileCount)
                        focused.space?.let { append("  储存: ").append(Fmt.size(it.total - it.free)).append("/").append(Fmt.size(it.total)) }
                        if (focused.filtered) append("  ·  已过滤")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconTextButton("⋮") { showMoreMenu = true }
        }

        // 搜索栏（MT 的「搜索」）
        if (searchMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜索：", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.weight(1f)) {
                    if (focused.search.isEmpty()) {
                        Text("输入名称关键字（当前目录）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = focused.search,
                        onValueChange = { q ->
                            controller.setSearch(ui.focused, q)
                            controller.refresh(ui.focused)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = {
                    controller.setSearch(ui.focused, "")
                    controller.refresh(ui.focused)
                    searchMode = false
                }) { Text("关闭") }
            }
        }

        HSeparator()

        // ---------------- 两个窗格
        Row(Modifier.weight(1f)) {
            val showLeft = !ui.singlePane || ui.focused == PaneSide.LEFT
            val showRight = !ui.singlePane || ui.focused == PaneSide.RIGHT
            if (showLeft) {
                PaneView(
                    side = PaneSide.LEFT,
                    pane = ui.left,
                    focused = ui.focused == PaneSide.LEFT,
                    highlight = ui.highlight && ui.focused == PaneSide.LEFT,
                    controller = controller,
                    modifier = Modifier.weight(1f),
                    onRowAction = { rowAction = it },
                )
            }
            if (showLeft && showRight) {
                Box(
                    Modifier
                        .size(width = 1.dp, height = 0.dp)
                        .fillMaxWidth(0.0001f)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            if (showRight) {
                PaneView(
                    side = PaneSide.RIGHT,
                    pane = ui.right,
                    focused = ui.focused == PaneSide.RIGHT,
                    highlight = ui.highlight && ui.focused == PaneSide.RIGHT,
                    controller = controller,
                    modifier = Modifier.weight(1f),
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

        // ---------------- 底部命令栏（← → ＋ ⇄ ↑）
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomCommand("←", enabled = focused.tab.back.isNotEmpty()) { controller.back(ui.focused) }
            BottomCommand("→", enabled = focused.tab.forward.isNotEmpty()) { controller.forward(ui.focused) }
            BottomCommand("＋") { creatingFolder = true }
            Box {
                BottomCommand("⇄", highlighted = showCrossMenu) { showCrossMenu = true }
                DropdownMenu(expanded = showCrossMenu, onDismissRequest = { showCrossMenu = false }) {
                    DropdownMenuItem(text = { Text("复制到对面窗格") }, onClick = { showCrossMenu = false; controller.copyToOther() })
                    DropdownMenuItem(text = { Text("移动到对面窗格") }, onClick = { showCrossMenu = false; controller.moveToOther() })
                    DropdownMenuItem(text = { Text("同步路径到对面") }, onClick = { showCrossMenu = false; controller.syncPath() })
                    DropdownMenuItem(text = { Text("交换窗口") }, onClick = { showCrossMenu = false; controller.swapPanes() })
                    DropdownMenuItem(
                        text = { Text(if (focused.hasSelection) "取消选择" else "全选") },
                        onClick = {
                            showCrossMenu = false
                            if (focused.hasSelection) controller.clearSelection(ui.focused) else controller.selectAll(ui.focused)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("压缩到对面（zip）") },
                        onClick = { showCrossMenu = false; controller.compressToOther() },
                    )
                    DropdownMenuItem(
                        text = { Text("进入压缩包") },
                        onClick = {
                            showCrossMenu = false
                            val item = focused.selectedItems.firstOrNull()
                                ?: focused.items.firstOrNull { com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(it.name) != null }
                            if (item != null) controller.openArchiveInPane(ui.focused, item)
                            else controller.showStatus("当前目录没有压缩包")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("比较两个目录") },
                        onClick = {
                            showCrossMenu = false
                            controller.showStatus("目录比较：M9 计划（差异对比引擎已占位）")
                        },
                    )
                }
            }
            BottomCommand("↑", enabled = focused.uri.parent != null) { controller.up(ui.focused) }
        }
    }

    // ---------------- ⋮ 菜单（MT 的动作菜单）
    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
        DropdownMenuItem(text = { Text("刷新") }, onClick = { showMoreMenu = false; controller.refresh(ui.focused) })
        DropdownMenuItem(
            text = { Text("搜索") },
            onClick = { showMoreMenu = false; searchMode = !searchMode },
        )
        DropdownMenuItem(text = { Text("全选") }, onClick = { showMoreMenu = false; controller.selectAll(ui.focused) })
        DropdownMenuItem(text = { Text("过滤…") }, onClick = { showMoreMenu = false; showFilterDialog = true })
        DropdownMenuItem(text = { Text("排序方式…") }, onClick = { showMoreMenu = false; showSortDialog = true })
        DropdownMenuItem(
            text = { Text(if (focused.showHidden) "隐藏文件：已显示" else "隐藏文件：已隐藏") },
            onClick = { showMoreMenu = false; controller.toggleHidden(ui.focused) },
        )
        DropdownMenuItem(text = { Text("添加书签") }, onClick = { showMoreMenu = false; controller.addBookmark(ui.focused) })
        DropdownMenuItem(text = { Text("设为首页") }, onClick = { showMoreMenu = false; controller.setAsHome(ui.focused) })
        DropdownMenuItem(text = { Text("交换窗口") }, onClick = { showMoreMenu = false; controller.swapPanes() })
        DropdownMenuItem(
            text = { Text(if (ui.singlePane) "切换为双列" else "切换为单列") },
            onClick = { showMoreMenu = false; controller.toggleSinglePane() },
        )
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
        DropdownMenuItem(
            text = { Text("主页") },
            onClick = { showQuickMenu = false; onOpenHome() },
        )
        DropdownMenuItem(
            text = { Text("内部存储") },
            onClick = {
                showQuickMenu = false
                controller.open(ui.focused, VfsUri.of("local", "emulated", "/"), null, "内部存储")
            },
        )
        DropdownMenuItem(
            text = { Text("根目录 /") },
            onClick = { showQuickMenu = false; controller.open(ui.focused, VfsUri.of("local", "root", "/"), null, "根目录") },
        )
        DropdownMenuItem(
            text = { Text("应用私有目录") },
            onClick = { showQuickMenu = false; controller.open(ui.focused, VfsUri.of("local", "app", "/"), null, "应用目录") },
        )
        DropdownMenuItem(text = { Text("书签…") }, onClick = { showQuickMenu = false; onOpenBookmarks() })
        DropdownMenuItem(text = { Text("局域网扫描…") }, onClick = { showQuickMenu = false; onOpenLanScan() })
        val bookmarks = remember(showQuickMenu) { if (showQuickMenu) controller.bookmarks() else emptyList() }
        if (bookmarks.isNotEmpty()) {
            HSeparator()
            Text(
                "  书签",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bookmarks.take(8).forEach { bm ->
                DropdownMenuItem(
                    text = { Text(bm.name.ifEmpty { bm.uri.displayPath }, maxLines = 1) },
                    onClick = { showQuickMenu = false; controller.openBookmark(bm) },
                )
            }
        }
    }

    // ---------------- 排序 / 过滤
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    SortBy.entries.forEach { by ->
                        TextButton(onClick = {
                            val asc = if (focused.sort.by == by) !focused.sort.ascending else true
                            controller.setSort(ui.focused, SortSpec(by, asc, focused.sort.dirsFirst))
                            showSortDialog = false
                        }) {
                            Text(
                                sortLabel(by) + if (focused.sort.by == by) (if (focused.sort.ascending) " ↑" else " ↓") else "",
                                color = if (focused.sort.by == by) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = {
                        controller.setSort(ui.focused, focused.sort.copy(dirsFirst = !focused.sort.dirsFirst))
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
            title = { Text("过滤") },
            text = {
                Column {
                    TextButton(onClick = { controller.setFilter(ui.focused, null); showFilterDialog = false }) {
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
                        TextButton(onClick = { controller.setFilter(ui.focused, kind.name); showFilterDialog = false }) {
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

    // ---------------- 对话框
    ui.pendingMove?.let { pending ->
        MoveConfirmDialog(pending, onConfirm = { controller.confirmMove() }, onCancel = { controller.cancelMove() })
    }
    ui.conflict?.let { info ->
        ConflictDialog(info) { policy, applyAll -> controller.resolveConflict(policy, applyAll) }
    }
    ui.property?.let { item ->
        PropertiesDialog(item, space = focused.space?.let { Fmt.transferred(it.total - it.free, it.total) }) {
            controller.dismissProperties()
        }
    }
    rowAction?.let { item ->
        RowActionsDialog(
            item = item,
            onDismiss = { rowAction = null },
            onOpen = { controller.openItem(ui.focused, item) },
            onRename = { renaming = item },
            onCopyToOther = { controller.copyToOther() },
            onMoveToOther = { controller.moveToOther() },
            onDelete = { deleting = item },
            onProperties = { controller.showProperties(item) },
            onOpenWithSystem = { onOpenPreview(item.uri) },
        )
    }
    renaming?.let { item ->
        TextInputDialog(
            title = "重命名",
            initial = item.name,
            onConfirm = { newName -> controller.rename(item.uri, newName, ui.focused) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = "删除",
            message = "确定删除「${item.name}」？" + if (item.isDirectory) "（含目录内容）" else "",
            confirmText = "删除",
            onConfirm = { controller.deleteSelected(ui.focused) },
            onDismiss = { deleting = null },
        )
    }
    if (creatingFolder) {
        TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onConfirm = { name -> controller.createFolder(ui.focused, name) },
            onDismiss = { creatingFolder = false },
        )
    }
    if (gotoPath) {
        TextInputDialog(
            title = "跳转路径",
            initial = focused.uri.displayPath,
            label = "路径（可写完整 URI，如 s3://bucket/dir、/share/sub）",
            onConfirm = { input ->
                val text = input.trim()
                val target = if (text.contains("://")) {
                    runCatching { VfsUri.parse(text) }.getOrNull()
                } else {
                    focused.uri.withPath(if (text.startsWith("/")) text else "/$text")
                }
                if (target != null) {
                    controller.open(ui.focused, target, focused.tab.connectionId, focused.tab.label)
                } else {
                    controller.showStatus("路径格式不正确")
                }
            },
            onDismiss = { gotoPath = false },
        )
    }
    if (creatingFile) {
        TextInputDialog(
            title = "新建文件",
            label = "文件名",
            onConfirm = { name -> controller.createFile(ui.focused, name) },
            onDismiss = { creatingFile = false },
        )
    }
}

@Composable
private fun BottomCommand(
    symbol: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickableNoRipple(enabled, onClick),
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

private fun sortLabel(by: SortBy): String = when (by) {
    SortBy.NAME -> "按名称"
    SortBy.SIZE -> "按大小"
    SortBy.TIME -> "按时间"
    SortBy.TYPE -> "按类型"
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
