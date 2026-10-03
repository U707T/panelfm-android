package com.u707t.panelfm.ui.browser

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri

/**
 * 双列主界面：左/右两个独立窗格 + MT 式底部命令栏 `← → ＋ ⇄ ↑`。
 * 所有跨窗格操作的目标恒为「另一窗格当前目录」，不弹目标选择框。
 */
@Composable
fun DualPaneScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPreview: (VfsUri) -> Unit,
) {
    val ui by container.browser.state.collectAsState()
    val controller = container.browser

    var rowAction by remember { mutableStateOf<FileMetadata?>(null) }
    var renaming by remember { mutableStateOf<FileMetadata?>(null) }
    var deleting by remember { mutableStateOf<FileMetadata?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showCrossMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---------------- 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PanelFM", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "  双列 · ${ui.focusedPane.tab.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenHome) { Text("主页") }
            TextButton(onClick = onOpenTasks) { Text("任务") }
            IconButton(onClick = onOpenSettings) { Text("⚙", style = MaterialTheme.typography.titleMedium) }
        }

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
                PaneDivider(
                    focused = true,
                    highlight = ui.highlight,
                    onToggleFocus = { controller.focus(ui.focused.other) },
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
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ui.tasks.take(2).forEach { snapshot ->
                    TaskRow(snapshot, controller, onOpenTasks)
                }
                if (ui.tasks.size > 2) {
                    Text("还有 ${ui.tasks.size - 2} 个任务…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------- 命令栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CommandButton("←") { controller.back(ui.focused) }
            CommandButton("→") { controller.forward(ui.focused) }
            CommandButton("＋") { creatingFolder = true }
            Box {
                CommandButton("⇄", highlighted = showCrossMenu) { showCrossMenu = true }
                DropdownMenu(expanded = showCrossMenu, onDismissRequest = { showCrossMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制到对面窗格") },
                        onClick = { showCrossMenu = false; controller.copyToOther() },
                    )
                    DropdownMenuItem(
                        text = { Text("移动到对面窗格") },
                        onClick = { showCrossMenu = false; controller.moveToOther() },
                    )
                    DropdownMenuItem(
                        text = { Text("同步路径到对面") },
                        onClick = { showCrossMenu = false; controller.syncPath() },
                    )
                    DropdownMenuItem(
                        text = { Text("交换两个窗格") },
                        onClick = { showCrossMenu = false; controller.swapPanes() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (ui.focusedPane.hasSelection) "取消选择" else "全选") },
                        onClick = {
                            showCrossMenu = false
                            if (ui.focusedPane.hasSelection) controller.clearSelection(ui.focused)
                            else controller.selectAll(ui.focused)
                        },
                    )
                }
            }
            CommandButton("↑") { controller.up(ui.focused) }
            Box {
                CommandButton("⋮") { showMoreMenu = true }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(text = { Text("新建文件") }, onClick = { showMoreMenu = false; creatingFile = true })
                    DropdownMenuItem(text = { Text("刷新两个窗格") }, onClick = { showMoreMenu = false; controller.refreshAll() })
                    DropdownMenuItem(
                        text = { Text("删除选中项") },
                        onClick = { showMoreMenu = false; deleting = ui.focusedPane.selectedItems.firstOrNull() ?: ui.focusedPane.items.firstOrNull() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (ui.singlePane) "切换为双列" else "切换为单列") },
                        onClick = { showMoreMenu = false; controller.toggleSinglePane() },
                    )
                }
            }
        }
    }

    // ---------------- 状态提示
    ui.status?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.padding(bottom = 78.dp),
                action = { TextButton(onClick = { controller.consumeStatus() }) { Text("知道了") } },
            ) { Text(msg) }
        }
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3200)
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
        PropertiesDialog(item, space = ui.focusedPane.space?.let { Fmt.transferred(it.total - it.free, it.total) }) {
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
            onOpenWithSystem = { /* 交系统应用（本地文件） */ },
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
            )
            when (state) {
                is TaskState.Running, TaskState.Queued -> {
                    TextButton(onClick = { controller.pauseTask(snapshot.id) }) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                }
                TaskState.Paused -> {
                    TextButton(onClick = { controller.resumeTask(snapshot.id) }) { Text("继续", style = MaterialTheme.typography.labelSmall) }
                }
                else -> {}
            }
            TextButton(onClick = { controller.cancelTask(snapshot.id) }) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = onOpenTasks) { Text("详情", style = MaterialTheme.typography.labelSmall) }
        }
        ThinProgressBar(progress)
    }
}
