package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.ConflictDecision
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.FileOperationPlanner
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferRequest
import com.u707t.panelfm.core.transfer.TransferTask
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 双列浏览控制器（应用级单例，转屏/切屏都不丢状态）。
 *  - 两个窗格完全独立：路径、历史栈、标签页、排序、隐藏文件、选择集
 *  - 跨窗格操作：目标恒为另一窗格当前目录（实时取值，不弹目标选择框）
 */
class BrowserController(private val container: AppContainer) {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state

    /** 请求打开预览（由 AppRoot 观察后跳转） */
    private val _previewRequest = MutableStateFlow<VfsUri?>(null)
    val previewRequest: StateFlow<VfsUri?> = _previewRequest

    private val loadJobs = mutableMapOf<PaneSide, Job>()

    init {
        _state.value = _state.value.copy(
            left = _state.value.left.copy(showHidden = false),
        )
        load(PaneSide.LEFT)
        load(PaneSide.RIGHT)
        observeTasks()
    }

    // ------------------------------------------------------------------ 状态读写

    private fun update(transform: (BrowserUiState) -> BrowserUiState) {
        _state.value = transform(_state.value)
    }

    fun pane(side: PaneSide): PaneState = _state.value.pane(side)

    fun updatePane(side: PaneSide, transform: (PaneState) -> PaneState) {
        update { st ->
            if (side == PaneSide.LEFT) st.copy(left = transform(st.left)) else st.copy(right = transform(st.right))
        }
    }

    fun focus(side: PaneSide) = update { it.copy(focused = side) }

    fun toggleSinglePane() = update { it.copy(singlePane = !it.singlePane) }

    fun consumeStatus() = update { it.copy(status = null) }

    fun dismissPreviewRequest() { _previewRequest.value = null }

    fun showStatus(message: String) = update { it.copy(status = message) }

    // ------------------------------------------------------------------ 列表加载

    fun load(side: PaneSide) {
        loadJobs[side]?.cancel()
        val pane = pane(side)
        val uri = pane.uri
        updatePane(side) { it.copy(loading = true, error = null) }
        loadJobs[side] = container.scope.launch {
            try {
                val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("未连接：${uri.authority}（请先在主页添加/打开该存储）")
                vfs.connect()
                val options = ListOptions(
                    sort = pane.sort,
                    showHidden = pane.showHidden,
                    filter = pane.search.takeIf { it.isNotBlank() },
                )
                val listed = withContext(container.dispatchers.vfs) { vfs.list(uri, options) }
                val items = pane.filterKind?.let { kindName ->
                    listed.filter { it.isDirectory || it.extension.isEmpty() ||
                        com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kindName }
                } ?: listed
                val space = runCatching { withContext(container.dispatchers.vfs) { vfs.space(uri) } }.getOrNull()
                updatePane(side) { it.copy(items = items, loading = false, error = null, space = space) }
                runCatching { container.bookmarkDao.recordVisit(uri, pane.tab.connectionId) }
            } catch (e: Exception) {
                Logx.w("Browser", "list failed ${uri}: ${e.message}", e)
                updatePane(side) {
                    it.copy(
                        loading = false,
                        error = (e as? VfsException)?.userMessage ?: (e.message ?: "加载失败"),
                        items = emptyList(),
                    )
                }
            }
        }
    }

    fun refresh(side: PaneSide) = load(side)

    fun refreshAll() {
        load(PaneSide.LEFT)
        load(PaneSide.RIGHT)
    }

    // ------------------------------------------------------------------ 导航

    fun open(side: PaneSide, uri: VfsUri, connectionId: Long? = null, label: String? = null, pushHistory: Boolean = true) {
        val pane = pane(side)
        val tab = pane.tab
        if (tab.uri == uri) {
            load(side)
            return
        }
        val newBack = if (pushHistory) (tab.back + tab.uri).takeLast(HISTORY_LIMIT) else tab.back
        val newTab = tab.copy(
            uri = uri,
            connectionId = connectionId ?: tab.connectionId,
            label = label ?: tab.label,
            back = newBack,
            forward = if (pushHistory) emptyList() else tab.forward,
        )
        updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }, selection = emptySet()) }
        load(side)
    }

    /** 点击列表项：目录进入；压缩包挂载后进入；其它文件交预览 */
    fun openItem(side: PaneSide, item: FileMetadata) {
        if (item.isDirectory) {
            open(side, item.uri)
            return
        }
        val kind = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name)
        if (kind != null) {
            openArchiveInPane(side, item)
            return
        }
        _previewRequest.value = item.uri
    }

    /** 把压缩包挂载成只读 VFS 并在当前窗格进入（MT 的「进入压缩包」体验） */
    fun openArchiveInPane(side: PaneSide, item: FileMetadata) {
        container.scope.launch {
            try {
                val archive = container.openArchive(item.uri)
                val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(item.uri, archive.kind)
                open(side, inner, connectionId = null, label = "${item.name} · ${archive.kind.label}")
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "无法打开压缩包：${e.message}")
            }
        }
    }

    /** 压缩到对面窗格（zip） */
    fun compressToOther(side: PaneSide = _state.value.focused) {
        val st = _state.value
        val srcPane = st.pane(side)
        val dstPane = st.pane(side.other)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可压缩的项")
            return
        }
        val name = com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.zipNameFor(sources)
        val dest = dstPane.uri.child(name)
        update { it.copy(highlight = true, status = "压缩 ${sources.size} 项 → ${dest.displayPath}") }
        container.scope.launch {
            kotlinx.coroutines.delay(1200)
            update { it.copy(highlight = false) }
            try {
                com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
                    .compress(sources, dest) { done, _ ->
                        // 进度节流由 UI 侧省略；这里只在结束时提示
                    }
                showStatus("已压缩为 ${name}")
                load(side.other)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "压缩失败：${e.message}")
            }
        }
    }

    fun back(side: PaneSide) {
        val tab = pane(side).tab
        val prev = tab.back.lastOrNull() ?: return
        val newTab = tab.copy(back = tab.back.dropLast(1), forward = (tab.forward + tab.uri).takeLast(HISTORY_LIMIT), uri = prev)
        updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }, selection = emptySet()) }
        load(side)
    }

    fun forward(side: PaneSide) {
        val tab = pane(side).tab
        val next = tab.forward.lastOrNull() ?: return
        val newTab = tab.copy(forward = tab.forward.dropLast(1), back = (tab.back + tab.uri).takeLast(HISTORY_LIMIT), uri = next)
        updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }, selection = emptySet()) }
        load(side)
    }

    fun up(side: PaneSide) {
        val uri = pane(side).uri
        // 压缩包内部：回到压缩包所在目录
        if (uri.scheme == "archive") {
            val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(uri.path)
            if (inner.isEmpty() || !inner.contains('/')) {
                val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(uri.path)
                val host = encoded?.let { runCatching { VfsUri.parse(VfsUri.decodeHost(it)) }.getOrNull() }
                val parent = host?.parent
                if (parent != null) {
                    open(side, parent, pane(side).tab.connectionId, pane(side).tab.label)
                    return
                }
            }
        }
        val parent = uri.parent ?: return
        open(side, parent, pane(side).tab.connectionId, pane(side).tab.label)
    }

    // ------------------------------------------------------------------ 标签页

    fun newTab(side: PaneSide) {
        val pane = pane(side)
        val tab = pane.tab
        val tabs = pane.tabs + PaneTab(uri = tab.uri, connectionId = tab.connectionId, label = tab.label)
        updatePane(side) { it.copy(tabs = tabs, activeTab = tabs.lastIndex, selection = emptySet()) }
    }

    fun switchTab(side: PaneSide, index: Int) {
        val pane = pane(side)
        if (index !in pane.tabs.indices || index == pane.activeTab) return
        updatePane(side) { it.copy(activeTab = index, selection = emptySet()) }
        load(side)
    }

    fun closeTab(side: PaneSide, index: Int) {
        val pane = pane(side)
        if (pane.tabs.size <= 1) return
        val tabs = pane.tabs.toMutableList().also { it.removeAt(index) }
        val newActive = (pane.activeTab.coerceAtMost(tabs.lastIndex)).let { if (index <= pane.activeTab) (it - 1).coerceAtLeast(0) else it }
        updatePane(side) { it.copy(tabs = tabs, activeTab = newActive, selection = emptySet()) }
        load(side)
    }

    // ------------------------------------------------------------------ 选择 / 排序

    fun toggleSelection(side: PaneSide, uri: VfsUri) {
        updatePane(side) { pane ->
            val key = uri.toString()
            val sel = if (pane.selection.contains(key)) pane.selection - key else pane.selection + key
            pane.copy(selection = sel)
        }
    }

    fun selectAll(side: PaneSide) = updatePane(side) { it.copy(selection = it.items.map { item -> item.uri.toString() }.toSet()) }

    fun clearSelection(side: PaneSide) = updatePane(side) { it.copy(selection = emptySet()) }

    fun setSearch(side: PaneSide, query: String) {
        updatePane(side) { it.copy(search = query) }
    }

    /** MT 的「过滤」：按类型筛选当前目录（客户端过滤，立即生效） */
    fun setFilter(side: PaneSide, kind: String?) {
        updatePane(side) { it.copy(filterKind = kind) }
    }

    // ------------------------------------------------------------------ 书签 / 首页

    fun bookmarks(): List<com.u707t.panelfm.core.data.Bookmark> =
        runCatching { container.bookmarkDao.all() }.getOrDefault(emptyList())

    fun addBookmark(side: PaneSide) {
        val pane = pane(side)
        val name = pane.uri.name.ifEmpty { pane.tab.label }
        runCatching {
            container.bookmarkDao.add(
                com.u707t.panelfm.core.data.Bookmark(
                    id = 0,
                    connectionId = pane.tab.connectionId,
                    uri = pane.uri,
                    name = name,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        showStatus("已添加书签：${pane.uri.displayPath}")
    }

    fun removeBookmark(id: Long) {
        runCatching { container.bookmarkDao.delete(id) }
        showStatus("已删除书签")
    }

    /** 打开书签：网络路径若未挂载则自动重连 */
    fun openBookmark(bookmark: com.u707t.panelfm.core.data.Bookmark) {
        val side = _state.value.focused
        container.scope.launch {
            try {
                val uri = bookmark.uri
                if (uri.scheme != "local" && container.locator.find(uri) == null) {
                    val config = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                        ?: container.connectionByAuthority(uri.scheme, uri.authority)
                        ?: throw VfsException.Unsupported("找不到该连接：${uri.authority}")
                    container.openConnection(config)
                    open(side, uri, config.id, config.name)
                } else {
                    open(side, uri, bookmark.connectionId, bookmark.name)
                }
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "打开书签失败：${e.message}")
            }
        }
    }

    /** MT 的「设为首页」 */
    fun setAsHome(side: PaneSide) {
        val uri = pane(side).uri.toString()
        container.scope.launch { container.prefs.setHomePath(uri) }
        showStatus("已设为首页：${pane(side).uri.displayPath}")
    }

    /** 打开应用启动时进入的目录 */
    fun openHomeIfConfigured() {
        val home = container.settings.value.homePath ?: return
        val uri = runCatching { VfsUri.parse(home) }.getOrNull() ?: return
        if (container.locator.find(uri) != null) open(PaneSide.LEFT, uri)
    }

    fun setSort(side: PaneSide, sort: com.u707t.panelfm.core.model.SortSpec) {
        updatePane(side) { it.copy(sort = sort) }
        load(side)
    }

    fun toggleHidden(side: PaneSide) {
        updatePane(side) { it.copy(showHidden = !it.showHidden) }
        load(side)
    }

    fun enterSelectionMode(side: PaneSide, first: FileMetadata) {
        focus(side)
        updatePane(side) { it.copy(selection = setOf(first.uri.toString())) }
    }

    // ------------------------------------------------------------------ 单窗格内操作

    private fun targetSources(side: PaneSide): List<VfsUri> {
        val pane = pane(side)
        return if (pane.hasSelection) pane.selectedItems.map { it.uri } else pane.items.map { it.uri }
    }

    fun deleteSelected(side: PaneSide) {
        val pane = pane(side)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("没有可删除的项")
            return
        }
        container.scope.launch {
            try {
                val vfs = container.locator.find(pane.uri) ?: throw VfsException.Unsupported("会话不可用")
                withContext(container.dispatchers.vfs) { vfs.delete(sources) }
                showStatus("已删除 ${sources.size} 项")
                clearSelection(side)
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "删除失败：${e.message}")
            }
        }
    }

    fun rename(uri: VfsUri, newName: String, side: PaneSide) {
        container.scope.launch {
            try {
                val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("会话不可用")
                val target = uri.parent?.child(newName) ?: throw VfsException.ProtocolError("无法重命名根目录")
                val ok = withContext(container.dispatchers.vfs) { vfs.rename(uri, target) }
                if (!ok) throw VfsException.ProtocolError("服务器拒绝重命名（可能需要服务端复制）")
                showStatus("已重命名为 $newName")
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
            }
        }
    }

    fun createFolder(side: PaneSide, name: String) {
        val dir = pane(side).uri
        container.scope.launch {
            try {
                val vfs = container.locator.find(dir) ?: throw VfsException.Unsupported("会话不可用")
                withContext(container.dispatchers.vfs) { vfs.mkdir(dir.child(name), parents = true) }
                showStatus("已创建文件夹 $name")
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "创建失败：${e.message}")
            }
        }
    }

    fun createFile(side: PaneSide, name: String) {
        val dir = pane(side).uri
        container.scope.launch {
            try {
                val vfs = container.locator.find(dir) ?: throw VfsException.Unsupported("会话不可用")
                withContext(container.dispatchers.vfs) { vfs.touch(dir.child(name)) }
                showStatus("已创建文件 $name")
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "创建失败：${e.message}")
            }
        }
    }

    fun showProperties(item: FileMetadata) = update { it.copy(property = item) }

    fun dismissProperties() = update { it.copy(property = null) }

    // ------------------------------------------------------------------ 跨窗格操作

    fun swapPanes() {
        val st = _state.value
        update { it.copy(left = st.right, right = st.left) }
    }

    /** 同步按钮：把非激活窗格跳到激活窗格相同路径（压缩包内部则定位到压缩包所在目录） */
    fun syncPath() {
        val st = _state.value
        val from = st.focusedPane
        val toSide = st.focused.other
        val archiveHost = parseArchiveHost(from.uri)
        val target = when {
            archiveHost != null -> archiveHost.parent ?: archiveHost
            from.uri.isRoot -> from.uri
            else -> from.uri
        }
        val connId = if (target.scheme == "local") null else from.tab.connectionId
        if (target.scheme != "local" && container.locator.find(target) == null) {
            showStatus("对侧没有相同协议的存储，无法同步到 ${target.displayPath}")
            return
        }
        if (target.scheme == "local") {
            open(toSide, target, null, "内部存储")
        } else {
            open(toSide, target, connId, from.tab.label)
        }
        showStatus("已同步路径 → ${target.displayPath}")
    }

    private fun parseArchiveHost(uri: VfsUri): VfsUri? {
        if (uri.scheme != "archive") return null
        val raw = uri.path.trimStart('/').substringBefore("!/")
        if (raw.isEmpty()) return null
        return runCatching { VfsUri.parse(VfsUri.decodeHost(raw)) }.getOrNull()
    }

    fun copyToOther(side: PaneSide = _state.value.focused) = startCrossPane(side, TransferOp.COPY)

    fun moveToOther(side: PaneSide = _state.value.focused) {
        val st = _state.value
        val srcPane = st.pane(side)
        val dstPane = st.pane(side.other)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录为空")
            return
        }
        val count = if (srcPane.hasSelection) srcPane.selection.size else srcPane.items.size
        val bytes = if (srcPane.hasSelection) srcPane.selectedItems.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0) }
        else srcPane.items.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0) }
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

    fun confirmMove() {
        val pending = _state.value.pendingMove ?: return
        update { it.copy(pendingMove = null) }
        startCrossPane(
            side = _state.value.focused,
            op = TransferOp.MOVE,
            overrideSources = pending.sources,
            overrideDest = pending.destDir,
        )
    }

    fun cancelMove() = update { it.copy(pendingMove = null) }

    private fun startCrossPane(
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

        // ① 两侧路径高亮 + 中央文案（操作前可见性）
        update { it.copy(highlight = true, status = "$opText ${sources.size} 项 → ${destDir.displayPath}") }
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

    private fun observeTasks() {
        container.scope.launch {
            container.engine.tasks.collect { tasks ->
                val active = tasks.filter {
                    val s = it.state.value
                    s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
                }
                val snapshots = tasks.take(8).map { it.snapshot() }
                val waitingConflict = tasks.firstOrNull { it.state.value is TaskState.WaitingConflict }
                val conflict = (waitingConflict?.state?.value as? TaskState.WaitingConflict)?.info
                update { it.copy(tasks = snapshots, conflict = conflict) }
                // 任务完成后刷新两个窗格（外部改动可能改变列表）
                if (tasks.any { it.state.value is TaskState.Done }) {
                    if (active.isEmpty()) {
                        load(PaneSide.LEFT)
                        load(PaneSide.RIGHT)
                    }
                }
            }
        }
    }

    fun resolveConflict(policy: ConflictPolicy, applyAll: Boolean) {
        val task = container.engine.tasks.value.firstOrNull { it.state.value is TaskState.WaitingConflict } ?: return
        container.scope.launch { task.resolveConflict(ConflictDecision(policy, applyAll)) }
        update { it.copy(conflict = null) }
    }

    fun pauseTask(id: String) {
        container.engine.tasks.value.firstOrNull { it.id == id }?.pause()
    }

    fun resumeTask(id: String) {
        container.engine.tasks.value.firstOrNull { it.id == id }?.resume()
    }

    fun cancelTask(id: String) {
        container.engine.tasks.value.firstOrNull { it.id == id }?.cancel()
    }

    fun clearFinishedTasks() = container.engine.clearFinished()

    fun allTasks(): List<TransferTask> = container.engine.tasks.value

    private fun TransferTask.snapshot(): TransferTaskSnapshot {
        val s = state.value
        val subtitle = when (s) {
            is TaskState.Running -> listOf(
                s.currentName,
                Fmt.transferred(s.doneBytes, s.totalBytes),
                Fmt.speed(s.speedBps),
                if (s.etaSeconds > 0) "剩 ${Fmt.eta(s.etaSeconds)}" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            is TaskState.Done -> "完成 ${s.ok} 项" + if (s.failed > 0) "，失败 ${s.failed}" else ""
            is TaskState.Failed -> s.message
            is TaskState.Cancelled -> "已取消"
            is TaskState.Paused -> "已暂停"
            is TaskState.WaitingConflict -> "等待冲突选择"
            TaskState.Queued -> "排队中"
        }
        return TransferTaskSnapshot(id = id, title = title, subtitle = subtitle, state = s, op = request.op)
    }

    companion object {
        const val HISTORY_LIMIT = 100
    }
}

/** 预览用的辅助：判断文件是否适合内置预览 */
fun FileMetadata.previewKind(): String = com.u707t.panelfm.core.common.MimeTypes.kindOf(extension).name

/** 便于 UI 展示的目录统计 */
fun PaneState.summary(): String = buildString {
    append("文件夹: ").append(dirCount).append("  文件: ").append(fileCount)
    space?.let { append("   ").append(Fmt.size(it.total - it.free)).append("/").append(Fmt.size(it.total)) }
}

/** 由 VFS 类型给出的人类可读名 */
fun VirtualFileSystem.kindLabel(): String = when (scheme) {
    "local" -> "本地"
    "dav" -> "WebDAV"
    "ftp" -> "FTP"
    "ftps" -> "FTPS"
    "sftp" -> "SFTP"
    "smb" -> "SMB"
    "s3" -> "对象存储"
    else -> scheme.uppercase()
}

/** 供计划器复用的工具 */
object BrowserOps {
    fun plannerOf(container: AppContainer): FileOperationPlanner = container.planner
}
