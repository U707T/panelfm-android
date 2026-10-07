package com.u707t.panelfm.ui.browser

// ================================================================================================
// BrowserController 拆分（extension）：跨窗格 / 剪贴板 / 目录对比 / 任务与冲突桥
// 组织方式：extension 函数（同 package 免 import）；共享状态在 BrowserController.kt（见其头部说明）。
// ================================================================================================

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MtSelection
import com.u707t.panelfm.core.model.ConflictDecision
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.FileOperationPlanner
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferRequest
import com.u707t.panelfm.core.transfer.TransferTask
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.forBrowserBar
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.transfer.isFinished
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

fun BrowserController.copyToOther(
    side: PaneSide = _state.value.focused,
    /** 显式目标（长按菜单按「这一项」复制时传入）；null = 当前选择集 / 当前目录 */
    overrideSources: List<VfsUri>? = null,
) = startCrossPane(side, TransferOp.COPY, overrideSources = overrideSources)

/**
 * MT「选择当前目录」模式的落地动作：把活动窗格的选中项复制到指定目录（[startCrossPane] 的
 * `overrideDest` 通道，与「复制到对面」共用同一条经过验证的链路）。
 */
fun BrowserController.copyTo(side: PaneSide, dest: VfsUri) = startCrossPane(side, TransferOp.COPY, overrideDest = dest)

/** MT「选择当前目录」模式的落地动作：移动到指定目录 */
fun BrowserController.moveTo(side: PaneSide, dest: VfsUri) = startCrossPane(side, TransferOp.MOVE, overrideDest = dest)

fun BrowserController.moveToOther(
    side: PaneSide = _state.value.focused,
    /** 显式目标（长按菜单按「这一项」移动时传入）；null = 当前选择集 / 当前目录 */
    overrideItems: List<FileMetadata>? = null,
) {
    val st = _state.value
    val srcPane = st.pane(side)
    val dstPane = st.pane(side.other)
    // 目标项：显式传入 > 当前选择集 > 当前目录（与 targetSources 的优先级一致）
    val picked = overrideItems ?: if (srcPane.hasSelection) srcPane.selectedItems else srcPane.items
    val sources = picked.map { it.uri }
    if (sources.isEmpty()) {
        showStatus("当前目录为空")
        return
    }
    val count = picked.size
    val bytes = picked.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0) }
    val crossVfs = container.locator.find(srcPane.uri) !== container.locator.find(dstPane.uri)
    update {
        it.copy(
            pendingMove = PendingMove(
                sources = sources,
                destDir = dstPane.uri,
                count = count,
                bytes = bytes,
                crossVfs = crossVfs,
                fromLabel = srcPane.uri.displayPath,
                toLabel = dstPane.uri.displayPath,
            )
        )
    }
}

fun BrowserController.confirmMove() {
    val pending = _state.value.pendingMove ?: return
    update { it.copy(pendingMove = null) }
    startCrossPane(
        side = _state.value.focused,
        op = TransferOp.MOVE,
        overrideSources = pending.sources,
        overrideDest = pending.destDir,
    )
}

fun BrowserController.cancelMove() = update { it.copy(pendingMove = null) }

// ------------------------------------------------------------------ 目录对比（M9）

fun BrowserController.compareDirectories() {
    val st = _state.value
    launchBusy("对比两个目录") { report ->
        val result = FolderDiffEngine.compare(container, st.left.uri, st.right.uri, report)
        update { it.copy(diff = result) }
        showStatus("对比完成：相同 ${result.identical} / 不同 ${result.different}")
    }
}

fun BrowserController.dismissDiff() = update { it.copy(diff = null) }

/** 仅把「只在左侧」的项复制到右侧 */
fun BrowserController.copyDiffOnlyLeft() {
    val diff = _state.value.diff ?: return
    val sources = diff.onlyLeftEntries.mapNotNull { it.left?.uri }
    if (sources.isEmpty()) {
        showStatus("没有仅左侧的项")
        return
    }
    container.engine.enqueue(TransferRequest(sources, diff.rightDir, TransferOp.COPY, ConflictPolicy.ASK))
    showStatus("已开始复制 ${sources.size} 项到右侧")
}

/** 只把左侧较新的项覆盖到右侧 */
fun BrowserController.copyDiffNewer() {
    val diff = _state.value.diff ?: return
    val sources = diff.newerOnLeft.mapNotNull { it.left?.uri }
    if (sources.isEmpty()) {
        showStatus("没有较新的项")
        return
    }
    container.engine.enqueue(TransferRequest(sources, diff.rightDir, TransferOp.COPY, ConflictPolicy.OVERWRITE))
    showStatus("已开始同步 ${sources.size} 个较新项")
}

// ------------------------------------------------------------------ 剪贴板（MT 每窗格 FAB）

/**
 * 把选中项「复制到剪贴板」：记录源 URI 列表（应用内剪贴板，跨窗格 / 跨会话可用）。
 * MT 的剪贴板图标 FAB 是「粘贴」，对应的复制入口在动作菜单。
 *
 * F15：没有选择时**不再静默把整个目录放进剪贴板**（旧实现经 `targetSources()` 回退），
 * 与菜单文案保持一致 —— 提示用户先选中。
 */
fun BrowserController.copySelectionToClipboard(side: PaneSide, overrideSources: List<VfsUri>? = null) {
    val pane = _state.value.pane(side)
    val sources = overrideSources ?: if (pane.hasSelection) pane.selectedItems.map { it.uri } else emptyList()
    if (sources.isEmpty()) {
        showStatus("请先选中要复制到剪贴板的项（无选择时不取整个目录）")
        return
    }
    clipboard = sources
    showStatus("已复制 ${sources.size} 项到剪贴板（可到其他目录粘贴）")
}

/**
 * 从剪贴板粘贴到当前窗格目录（MT 的剪贴板 FAB）。
 * 移动语义：粘贴后清空剪贴板（与 MT 的「剪切后粘贴」一致）。
 */
fun BrowserController.pasteFromClipboard(side: PaneSide, move: Boolean = false) {
    val st = _state.value
    val sources = clipboard
    if (sources.isEmpty()) {
        showStatus("剪贴板为空")
        return
    }
    val dest = st.pane(side).uri
    // 剪贴板里的源可能来自已断开的会话；逐个校验可达性
    val reachable = sources.filter { container.locator.find(it) != null }
    if (reachable.isEmpty()) {
        showStatus("剪贴板中的位置已不可用（会话可能已断开）")
        return
    }
    val op = if (move) TransferOp.MOVE else TransferOp.COPY
    val opText = if (move) "移动" else "复制"
    showStatus("$opText ${reachable.size} 项 → ${dest.displayPath}")
    update { it.copy(highlight = true) }
    container.scope.launch {
        kotlinx.coroutines.delay(1600)
        update { it.copy(highlight = false) }
    }
    container.engine.enqueue(
        TransferRequest(sources = reachable, destDir = dest, op = op, conflict = ConflictPolicy.ASK)
    )
    if (move) clipboard = emptyList()
    clearSelection(side)
}

fun BrowserController.copyWithinPane(side: PaneSide, destDir: VfsUri, overrideSources: List<VfsUri>? = null) =
    enqueueWithinPane(side, TransferOp.COPY, destDir, overrideSources)

fun BrowserController.moveWithinPane(side: PaneSide, destDir: VfsUri, overrideSources: List<VfsUri>? = null) =
    enqueueWithinPane(side, TransferOp.MOVE, destDir, overrideSources)

/**
 * 单窗口复制 / 移动。
 *
 * [overrideSources] = 长按菜单「长按复制/移动 ->」传入的显式目标（F8 修复：旧入口不带目标，
 * `targetSources()` 在没有选择时回退成**整个目录**，会把整个目录搬走）。
 */
private fun BrowserController.enqueueWithinPane(side: PaneSide, op: TransferOp, destDir: VfsUri, overrideSources: List<VfsUri>? = null) {
    val sources = explicitTargets(overrideSources) { targetSources(side) }
    if (sources.isEmpty()) {
        showStatus("当前目录没有可操作的项")
        return
    }
    val opText = if (op == TransferOp.COPY) "复制" else "移动"
    showStatus("$opText ${sources.size} 项 → ${destDir.displayPath}")
    container.engine.enqueue(
        TransferRequest(sources = sources, destDir = destDir, op = op, conflict = ConflictPolicy.ASK)
    )
    clearSelection(side)
}

private fun BrowserController.startCrossPane(
    side: PaneSide,
    op: TransferOp,
    overrideSources: List<VfsUri>? = null,
    overrideDest: VfsUri? = null,
) {
    val st = _state.value
    val srcPane = st.pane(side)
    val dstPane = st.pane(side.other)
    val sources = overrideSources ?: targetSources(side)
    if (sources.isEmpty()) {
        showStatus("当前目录没有可操作的项")
        return
    }
    val destDir = overrideDest ?: dstPane.uri
    val opText = if (op == TransferOp.COPY) "复制" else "移动"
    // MT：另一窗口未打开有效路径时，提示无法操作
    if (overrideDest == null && destDir.scheme != "local" && destDir.scheme != "archive" &&
        container.locator.find(destDir) == null
    ) {
        showStatus("另一窗口未打开有效路径（${destDir.authority} 未连接），无法$opText")
        return
    }

    // ① 两侧路径高亮 + 中央文案（操作前可见性）
    showStatus("$opText ${sources.size} 项 → ${destDir.displayPath}")
    update { it.copy(highlight = true) }
    container.scope.launch {
        kotlinx.coroutines.delay(1600)
        update { it.copy(highlight = false) }
    }

    // ② 入队
    container.engine.enqueue(
        TransferRequest(
            sources = sources,
            destDir = destDir,
            op = op,
            conflict = ConflictPolicy.ASK,
            wholeDirectory = false,
        )
    )
    clearSelection(side)
}

// ------------------------------------------------------------------ 任务 / 冲突

internal fun BrowserController.observeTasks() {
    container.scope.launch {
        // 关键：必须观察 taskEvents（任务状态变化也会发射），否则进度 / 冲突 / 完成都到不了 UI。
        // 旧实现用 engine.tasks（StateFlow<List>）——列表不增删时永远不发射，
        // 导致冲突弹窗永不出现（ASK 任务卡死）、进度不更新、任务完成后不刷新。
        var previousActive = emptySet<String>()
        var knownIds = emptySet<String>()
        container.engine.taskEvents.collect { tasks ->
            val activeIds = tasks.filter { it.state.value.isActive }.map { it.id }.toSet()
            val waitingConflict = tasks.firstOrNull { it.state.value is TaskState.WaitingConflict }
            val conflict = (waitingConflict?.state?.value as? TaskState.WaitingConflict)?.info
            // 任务条只收「进行中 + 失败」：完成 / 已取消立即从任务条消失（引擎会在保留期后
            // 连列表一起收走）。旧实现把完成的任务也塞进来、还只取最早 8 条再显示前 2 条 ——
            // 任务条常驻不消失、真正在跑的任务反而被挤到「还有 N 个任务…」后面。
            val snapshots = tasks.map { it.toSnapshot() }.forBrowserBar()
            update { it.copy(tasks = snapshots, conflict = conflict) }

            // 一批任务全部结束（或第一次就被看到已结束，如极快的完成在两次发射之间）→
            // 刷新两个窗格：外部改动（复制进来的文件等）可能改变两侧列表。
            val finishedNow = previousActive.isNotEmpty() && activeIds.isEmpty()
            val sawNewFinished = tasks.any { it.id !in knownIds && it.state.value.isFinished }
            previousActive = activeIds
            knownIds = tasks.map { it.id }.toSet()
            if (finishedNow || sawNewFinished) {
                load(PaneSide.LEFT)
                load(PaneSide.RIGHT)
            }
        }
    }
}

fun BrowserController.resolveConflict(policy: ConflictPolicy, applyAll: Boolean) {
    val task = container.engine.tasks.value.firstOrNull { it.state.value is TaskState.WaitingConflict } ?: return
    container.scope.launch { task.resolveConflict(ConflictDecision(policy, applyAll)) }
    update { it.copy(conflict = null) }
}

fun BrowserController.pauseTask(id: String) {
    container.engine.findTask(id)?.pause()
}

fun BrowserController.resumeTask(id: String) {
    container.engine.findTask(id)?.resume()
}

fun BrowserController.cancelTask(id: String) {
    container.engine.findTask(id)?.cancel()
}

/** 从列表移除任务（未结束的先取消）：任务页 / 任务条的「移除」。 */
fun BrowserController.removeTask(id: String) {
    container.engine.findTask(id)?.let { container.engine.remove(it) }
}

fun BrowserController.clearFinishedTasks() = container.engine.clearFinished()


