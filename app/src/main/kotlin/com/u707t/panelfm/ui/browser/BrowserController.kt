package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
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
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
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

    /** 请求打开预览（由 AppRoot 观察后跳转），带「打开方式」模式 */
    private val _previewRequest = MutableStateFlow<com.u707t.panelfm.ui.preview.PreviewRequest?>(null)
    val previewRequest: StateFlow<com.u707t.panelfm.ui.preview.PreviewRequest?> = _previewRequest

    /** 文件对比请求（左/右两个文件） */
    private val _diffRequest = MutableStateFlow<Pair<VfsUri, VfsUri>?>(null)
    val diffRequest: StateFlow<Pair<VfsUri, VfsUri>?> = _diffRequest

    fun dismissDiffRequest() { _diffRequest.value = null }

    private val loadJobs = mutableMapOf<PaneSide, Job>()

    init {
        // 注意：构造时 container.settings 尚未从 DataStore 加载（异步），此处拿到的是默认值；
        // 持久化设置要等 settings 首次发射后再应用（见下），否则「默认隐藏文件 / 默认单列 /
        // 全局排序 / 分隔比例」等设置在启动时全部失效。
        load(PaneSide.LEFT)
        load(PaneSide.RIGHT)
        observeTasks()
        container.scope.launch {
            var startupApplied = false
            container.settings.collect { s ->
                com.u707t.panelfm.core.common.Fmt.showSeconds = s.showSeconds
                // MT「保留文件时间」：引擎侧统一补进每个 TransferRequest（含解压 / 差异复制等旁路）
                container.engine.preserveModifiedTime = s.preserveModifiedTime
                if (!startupApplied) {
                    startupApplied = true
                    val defaultSort = SortSpec(s.sortBy, s.sortAscending, s.dirsFirst)
                    update {
                        it.copy(
                            splitRatio = s.splitRatio,
                            singlePane = s.useSingleColumn,
                            left = it.left.copy(sort = defaultSort, showHidden = s.showHidden),
                            right = it.right.copy(sort = defaultSort, showHidden = s.showHidden),
                        )
                    }
                    load(PaneSide.LEFT)
                    load(PaneSide.RIGHT)
                }
            }
        }
        // 记忆路径异步恢复（不在主线程 runBlocking —— 冷启动不卡首帧）
        container.scope.launch {
            // MT「启动路径 - 左/右窗口」：勾了「首页」就不恢复上次路径，改由 homePath 决定
            val s0 = container.settings.value
            if (s0.startAtHome) {
                val home = s0.homePath?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                if (home != null && container.locator.find(home) != null) {
                    update { st ->
                        st.copy(
                            left = st.left.copy(tabs = listOf(PaneTab(home, label = home.authority)), sort = st.left.sort),
                            right = st.right.copy(tabs = listOf(PaneTab(home, label = home.authority)), sort = st.right.sort),
                        )
                    }
                    load(PaneSide.LEFT)
                    load(PaneSide.RIGHT)
                }
                return@launch
            }
            if (!s0.rememberLastPath) return@launch
            val (lastLeft, lastRight) = container.prefs.lastPathsSuspend()
            val leftUri = lastLeft?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
            val rightUri = lastRight?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
            if (leftUri == null && rightUri == null) return@launch
            update { st ->
                st.copy(
                    left = if (leftUri != null && st.left.uri.isRoot && container.locator.find(leftUri) != null)
                        st.left.copy(tabs = listOf(PaneTab(leftUri, label = leftUri.authority)), sort = st.left.sort)
                    else st.left,
                    right = if (rightUri != null && st.right.uri.isRoot && container.locator.find(rightUri) != null)
                        st.right.copy(tabs = listOf(PaneTab(rightUri, label = rightUri.authority)), sort = st.right.sort)
                    else st.right,
                )
            }
            if (leftUri != null) load(PaneSide.LEFT)
            if (rightUri != null) load(PaneSide.RIGHT)
        }
    }

    /** 退出前保存双列路径（MT：记忆上次路径） */
    fun persistPaths() {
        if (!container.settings.value.rememberLastPath) return
        val st = _state.value
        container.scope.launch {
            container.prefs.saveLastPaths(st.left.uri.toString(), st.right.uri.toString())
        }
    }

    fun persistSplitRatio() {
        val ratio = _state.value.splitRatio
        container.scope.launch { container.prefs.setSplitRatio(ratio) }
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

    fun focus(side: PaneSide) = update { if (it.focused == side) it else it.copy(focused = side) }

    fun toggleSinglePane() = update { it.copy(singlePane = !it.singlePane) }

    fun consumeStatus() = update { it.copy(status = null) }

    fun dismissPreviewRequest() { _previewRequest.value = null }

    fun showStatus(message: String) = update { it.copy(status = message) }

    // ------------------------------------------------------------------ 列表加载

    fun load(side: PaneSide) {
        loadJobs[side]?.cancel()
        val pane = pane(side)
        val uri = pane.uri
        // MT「仅应用于此文件夹」：该路径有记忆排序 → 覆盖当前窗格排序
        val ruleSort = container.settings.value.folderSorts[VfsUris.stripped(uri).toString()]?.let { decodeSortSpec(it) }
        val effSort = ruleSort ?: pane.sort
        if (effSort != pane.sort) updatePane(side) { it.copy(sort = effSort) }
        // MT 加载遮罩（0x7f0c0033 的 09020D/09020E）：连接 → 枚举 → 过滤 → 完成 分阶段上报百分比
        updatePane(side) { it.copy(loading = true, loadProgress = 0.05f, error = null) }
        loadJobs[side] = container.scope.launch {
            try {
                val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("未连接：${uri.authority}（请先在主页添加/打开该存储）")
                vfs.connect()
                updatePane(side) { it.copy(loadProgress = 0.3f) }
                val options = ListOptions(
                    sort = effSort,
                    showHidden = pane.showHidden,
                    filter = null,      // 关键字过滤统一在客户端做（支持 /regex、!/regex、!text）
                )
                val listed = withContext(container.dispatchers.vfs) { vfs.list(uri, options) }
                updatePane(side) { it.copy(loadProgress = 0.75f) }
                val bySearch = listed.filter { matchesSearch(it.name, pane.search) }
                val items = pane.filterKind?.let { kindName ->
                    bySearch.filter {
                        it.isDirectory || it.extension.isEmpty() ||
                            com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kindName
                    }
                } ?: bySearch
                updatePane(side) { it.copy(loadProgress = 0.9f) }
                val space = runCatching { withContext(container.dispatchers.vfs) { vfs.space(uri) } }.getOrNull()
                updatePane(side) { it.copy(items = items, loading = false, loadProgress = null, error = null, space = space) }
                runCatching { container.bookmarkDao.recordVisit(uri, pane.tab.connectionId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了遮罩上的「取消」：不是错误，保持当前列表
                throw e
            } catch (e: Exception) {
                Logx.w("Browser", "list failed ${uri}: ${e.message}", e)
                updatePane(side) {
                    it.copy(
                        loading = false,
                        loadProgress = null,
                        error = (e as? VfsException)?.userMessage ?: (e.message ?: "加载失败"),
                        items = emptyList(),
                    )
                }
            }
        }
    }

    /** 取消当前加载（MT 加载遮罩的「取消」，0x7f0c0033 09020F/090210） */
    fun cancelLoad(side: PaneSide) {
        val job = loadJobs.remove(side) ?: return
        job.cancel()
        updatePane(side) { it.copy(loading = false, loadProgress = null) }
        showStatus("已取消加载")
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

    /**
     * 点击列表项（对齐 MT 的「打开方式」逻辑）：
     *  - 目录 → 进入
     *  - 该扩展名设置了默认打开方式 → 直接用它
     *  - 压缩包（未设置默认）→ 进入压缩包内部浏览
     *  - 其它 → 预览（自动识别）
     */
    fun openItem(side: PaneSide, item: FileMetadata) {
        if (item.isDirectory) {
            open(side, item.uri)
            return
        }
        val pref = runCatching { container.previewPrefDao.get(item.extension) }.getOrNull()
        val mode = com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(pref)
        when {
            mode == com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE -> {
                openArchiveInPane(side, item)
                return
            }
            mode != null -> {
                openWith(item, mode)
                return
            }
        }
        val isArchive = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name) != null
        if (isArchive) {
            openArchiveInPane(side, item)
            return
        }
        _previewRequest.value = com.u707t.panelfm.ui.preview.PreviewRequest(item.uri, com.u707t.panelfm.ui.preview.PreviewMode.AUTO)
    }

    /** 用指定方式打开（打开方式对话框 / 默认值都走这里） */
    fun openWith(item: FileMetadata, mode: com.u707t.panelfm.ui.preview.PreviewMode) {
        when (mode) {
            com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE -> openArchiveInPane(_state.value.focused, item)
            else -> _previewRequest.value = com.u707t.panelfm.ui.preview.PreviewRequest(item.uri, mode)
        }
    }

    fun setDefaultOpenMode(item: FileMetadata, mode: com.u707t.panelfm.ui.preview.PreviewMode) {
        if (item.extension.isBlank()) {
            showStatus("没有后缀的文件不能设置默认打开方式")
            return
        }
        container.scope.launch {
            container.previewPrefDao.set(item.extension, mode.handlerId)
            showStatus(".${item.extension} 的默认打开方式已设为「${mode.label}」")
        }
    }

    fun clearOpenMode(ext: String) {
        container.scope.launch {
            container.previewPrefDao.clear(ext)
            showStatus("已删除 .$ext 的默认打开方式")
        }
    }

    fun openModes(): List<Pair<String, String>> = runCatching { container.previewPrefDao.all() }.getOrDefault(emptyList())

    fun defaultOpenMode(item: FileMetadata): com.u707t.panelfm.ui.preview.PreviewMode? =
        com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(runCatching { container.previewPrefDao.get(item.extension) }.getOrNull())

    /** 文件对比（对齐 MT：两个文件才能在长按菜单里对比） */
    fun startFileDiff(side: PaneSide) {
        val st = _state.value
        val mine = st.pane(side).selectedItems
        val other = st.pane(side.other).selectedItems
        val candidates = mine + other
        if (candidates.size != 2) {
            showStatus("请分别在两个窗格各选中 1 个文件（或同一窗格选中恰好 2 个）")
            return
        }
        _diffRequest.value = candidates[0].uri to candidates[1].uri
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
    fun compressToOther(
        side: PaneSide = _state.value.focused,
        format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format =
            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP,
        fileName: String? = null,
        level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level =
            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
        password: String? = null,
        encryptNames: Boolean = false,
    ) {
        val st = _state.value
        val srcPane = st.pane(side)
        val dstPane = st.pane(side.other)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可压缩的项")
            return
        }
        val base = fileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.zipNameFor(sources).removeSuffix(".zip")
        val name = if (base.endsWith(".${format.ext}")) base else "$base.${format.ext}"
        val dest = dstPane.uri.child(name)
        update { it.copy(highlight = true, status = "压缩 ${sources.size} 项 → ${dest.displayPath}") }
        container.scope.launch {
            kotlinx.coroutines.delay(1200)
            update { it.copy(highlight = false) }
            try {
                com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
                    .compress(
                        sources, dest, format,
                        onProgress = { _, _ ->
                            // 进度节流由 UI 侧省略；这里只在结束时提示
                        },
                        level = level,
                        password = password,
                        encryptNames = encryptNames,
                    )
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

    /** MT 的「反选」 */
    fun invertSelection(side: PaneSide) = updatePane(side) { pane ->
        val all = pane.items.map { it.uri.toString() }.toSet()
        pane.copy(selection = all - pane.selection)
    }

    /** MT 的「类选」：与当前选中项同类型（同扩展名分类）的全部选中 */
    fun selectSameType(side: PaneSide) = updatePane(side) { pane ->
        val sample = pane.selectedItems.firstOrNull() ?: return@updatePane pane
        val kind = if (sample.isDirectory) "dir" else com.u707t.panelfm.core.common.MimeTypes.kindOf(sample.extension).name
        val same = pane.items.filter {
            if (kind == "dir") it.isDirectory
            else !it.isDirectory && com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kind
        }.map { it.uri.toString() }.toSet()
        pane.copy(selection = pane.selection + same)
    }

    /** 区间选择（替换语义）：手指从锚点滑到当前行，选中这段连续区间（MT 手册） */
    fun setSelectionRange(side: PaneSide, anchorIndex: Int, currentIndex: Int) = updatePane(side) { pane ->
        val lo = minOf(anchorIndex, currentIndex).coerceAtLeast(0)
        val hi = maxOf(anchorIndex, currentIndex).coerceAtMost(pane.items.lastIndex)
        if (hi < lo) pane else pane.copy(selection = pane.items.subList(lo, hi + 1).map { it.uri.toString() }.toSet())
    }

    /** 左右滑动进入多选：先选中该行（区间选择的锚点；继续滑过行间 → setSelectionRange） */
    fun startSelectionDrag(side: PaneSide, index: Int) = updatePane(side) { pane ->
        if (index !in pane.items.indices) pane
        else pane.copy(selection = setOf(pane.items[index].uri.toString()))
    }

    /** 已有多选时右滑该行 = 加选该行（已选中则保持不变） */
    fun addToSelection(side: PaneSide, uri: VfsUri) = updatePane(side) {
        it.copy(selection = it.selection + uri.toString())
    }

    /** 选中区间（MT：从第一个滑到最后一个即连续选中） */
    fun selectRange(side: PaneSide, fromIndex: Int, toIndex: Int) = updatePane(side) { pane ->
        val lo = minOf(fromIndex, toIndex).coerceAtLeast(0)
        val hi = maxOf(fromIndex, toIndex).coerceAtMost(pane.items.lastIndex)
        val range = pane.items.subList(lo, hi + 1).map { it.uri.toString() }.toSet()
        pane.copy(selection = pane.selection + range)
    }

    fun clearSelection(side: PaneSide) {
        longPressAnchor = null
        updatePane(side) { it.copy(selection = emptySet()) }
    }

    fun setSearch(side: PaneSide, query: String) {
        updatePane(side) { it.copy(search = query) }
        load(side)
    }

    /**
     * MT 的过滤语法：
     *  - 普通文本：包含匹配
     *  - 以 `!` 开头：否定匹配（不含该文本）
     *  - 以 `/` 开头：正则匹配
     *  - 以 `!/` 开头：正则否定匹配
     */
    fun matchesSearch(name: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return when {
            q.startsWith("!/") -> runCatching { !Regex(q.removePrefix("!/")).containsMatchIn(name) }.getOrDefault(true)
            q.startsWith("/") -> runCatching { Regex(q.removePrefix("/")).containsMatchIn(name) }.getOrDefault(true)
            q.startsWith("!") -> !name.contains(q.removePrefix("!"), ignoreCase = true)
            else -> name.contains(q, ignoreCase = true)
        }
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

    /**
     * MT「已设置为该网络存储的初始路径」（0x7f1104ab）：把**当前窗格路径**写回该连接的「初始路径」，
     * 下次从侧栏/主页打开该存储时直达这里。
     *
     * 初始路径的语义与 [ConnectionConfig.openPath] 相反（见 Connection.kt）：
     *  - WebDAV：basePath 是服务挂载点，初始路径是**虚拟根下**的相对子路径；
     *  - 其余协议：初始路径相对 basePath 追加。
     */
    fun setAsConnectionInitialPath(side: PaneSide) {
        val pane = pane(side)
        val uri = pane.uri
        if (uri.scheme == "local" || uri.scheme == "archive") {
            showStatus("当前窗口不是网络存储，无法设置初始路径")
            return
        }
        val config = container.connectionOf(pane.tab.connectionId)
            ?: container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
            ?: container.connectionByAuthority(uri.scheme, uri.authority)
        if (config == null) {
            showStatus("找不到该网络存储的连接配置（可先在侧栏重新打开一次）")
            return
        }
        // 初始路径是「相对连接根」的，路径必须落在连接根之内；上溯到根之外无法用相对路径表达（MT 同限制）
        val initial = initialPathFor(config, uri.path)
        if (initial == null) {
            showStatus("当前路径不在该连接的根目录下，无法设置为初始路径")
            return
        }
        val options = config.options.toMutableMap().apply {
            if (initial.isEmpty()) remove(ConnectionConfig.OPT_INITIAL_PATH)
            else put(ConnectionConfig.OPT_INITIAL_PATH, initial)
        }
        container.scope.launch {
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.connectionDao.update(config.copy(options = options))
                }
                container.reloadConnections()
            }.onFailure {
                showStatus("写入初始路径失败：${it.message}")
                return@launch
            }
            showStatus("已设置为该网络存储的初始路径")
        }
    }

    /** 打开应用启动时进入的目录 */
    fun openHomeIfConfigured() {
        val home = container.settings.value.homePath ?: return
        val uri = runCatching { VfsUri.parse(home) }.getOrNull() ?: return
        if (container.locator.find(uri) != null) open(PaneSide.LEFT, uri)
    }

    fun setSort(side: PaneSide, sort: SortSpec) {
        updatePane(side) { it.copy(sort = sort) }
        load(side)
    }

    /**
     * MT 排序对话框「确定」：
     *  - folderOnly = 「仅应用于此文件夹」→ 只给当前路径记一条规则；
     *  - 否则写全局默认排序，并清掉该文件夹的专属规则。
     */
    fun applySort(side: PaneSide, spec: SortSpec, folderOnly: Boolean) {
        val key = VfsUris.stripped(pane(side).uri).toString()
        updatePane(side) { it.copy(sort = spec) }
        load(side)
        container.scope.launch {
            if (folderOnly) {
                container.prefs.setFolderSort(key, encodeSortSpec(spec))
            } else {
                container.prefs.setFolderSort(key, null)
                container.prefs.setSort(spec.by, spec.ascending)
                container.prefs.setDirsFirst(spec.dirsFirst)
            }
        }
    }

    /** 当前目录是否已有「仅应用于此文件夹」的排序规则 */
    fun hasFolderSortRule(uri: VfsUri): Boolean =
        container.settings.value.folderSorts.containsKey(VfsUris.stripped(uri).toString())

    /** 已记忆的全部文件夹排序（排序管理对话框用） */
    fun folderSortRules(): Map<String, String> = container.settings.value.folderSorts

    fun clearFolderSorts() = container.scope.launch { container.prefs.clearFolderSorts() }

    fun toggleHidden(side: PaneSide) {
        updatePane(side) { it.copy(showHidden = !it.showHidden) }
        load(side)
    }

    fun enterSelectionMode(side: PaneSide, first: FileMetadata) {
        focus(side)
        updatePane(side) { it.copy(selection = setOf(first.uri.toString())) }
    }

    /**
     * MT「可通过分别长按两个项目来进行连选」（0x7f110631）：
     * 第一次长按 = 设锚点（只选它）；第二次长按另一项 = 选中两者之间的**全部**（含两端）。
     * 再长按第三次则重新设锚点（与 MT 一致：连选是「两两成对」的操作）。
     */
    fun longPressSelect(side: PaneSide, item: FileMetadata) {
        focus(side)
        val pane = pane(side)
        val index = pane.items.indexOfFirst { it.uri == item.uri }
        if (index < 0) return
        val key = item.uri.toString()
        // 已有锚点且锚点 != 当前项 → 连选区间
        if (longPressAnchor != null && longPressAnchor != key && pane.hasSelection) {
            val anchorIndex = pane.items.indexOfFirst { it.uri.toString() == longPressAnchor }
            if (anchorIndex >= 0) {
                setSelectionRange(side, anchorIndex, index)
                longPressAnchor = null
                return
            }
        }
        // 否则设锚点（只选当前项）
        longPressAnchor = key
        updatePane(side) { it.copy(selection = setOf(key)) }
    }

    /** 当前窗格长按锚点（连选用；切换窗格/清空选择时重置） */
    private var longPressAnchor: String? = null

    // ------------------------------------------------------------------ 单窗格内操作

    private fun targetSources(side: PaneSide): List<VfsUri> {
        val pane = pane(side)
        return if (pane.hasSelection) pane.selectedItems.map { it.uri } else pane.items.map { it.uri }
    }

    /** 统计本地目录内的条目数（用于「极速删除」提示，最多数到 cap 就返回） */
    suspend fun countLocalEntries(uri: VfsUri, cap: Int = 1200): Int {
        if (uri.scheme != "local") return 0
        var count = 0
        suspend fun walk(dir: java.io.File) {
            if (count >= cap) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                count++
                if (count >= cap) return
                if (child.isDirectory) walk(child)
            }
        }
        return withContext(container.dispatchers.io) {
            walk(java.io.File(container.localVfs.absolutePath(uri)))
            count
        }
    }

    /** 交换两个选中项的文件名（MT 的「交换文件名」，本地与网络都支持） */
    fun swapSelectedNames(side: PaneSide) {
        val picked = pane(side).selectedItems
        if (picked.size != 2) {
            showStatus("请正好选中 2 个文件再交换文件名")
            return
        }
        val a = picked[0]
        val b = picked[1]
        if (!a.uri.sameMount(b.uri)) {
            showStatus("两个文件不在同一位置，无法交换文件名")
            return
        }
        container.scope.launch {
            try {
                val vfs = container.locator.find(a.uri) ?: throw VfsException.Unsupported("会话不可用")
                val tmpName = ".panelfm.swap.${System.currentTimeMillis()}"
                val tmp = a.uri.parent?.child(tmpName) ?: throw VfsException.ProtocolError("无法交换")
                val targetA = a.uri.parent?.child(b.name) ?: throw VfsException.ProtocolError("无法交换")
                val targetB = b.uri.parent?.child(a.name) ?: throw VfsException.ProtocolError("无法交换")
                withContext(container.dispatchers.vfs) {
                    // 三步交换；中途失败则尽力回滚，避免「a 变成临时名、b 丢失」的坏状态
                    vfs.rename(a.uri, tmp)
                    try {
                        vfs.rename(b.uri, targetA)
                        try {
                            vfs.rename(tmp, targetB)
                        } catch (e: Exception) {
                            runCatching { vfs.rename(targetA, b.uri) } // 回滚 b
                            runCatching { vfs.rename(tmp, a.uri) }     // 回滚 a
                            throw e
                        }
                    } catch (e: Exception) {
                        runCatching { vfs.rename(tmp, a.uri) }         // 回滚 a
                        throw e
                    }
                }
                showStatus("已交换「${a.name}」与「${b.name}」")
                clearSelection(side)
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "交换失败：${e.message}")
            }
        }
    }

    fun deleteSelected(side: PaneSide, fastDelete: Boolean = false) {
        val pane = pane(side)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("没有可删除的项")
            return
        }
        container.scope.launch {
            try {
                // 本地文件优先进回收站（可还原）；网络位置或「极速删除」直接删除
                val localOnly = if (fastDelete) emptyList() else sources.filter { it.scheme == "local" }
                val remoteOnly = if (fastDelete) sources else sources.filter { it.scheme != "local" }
                var trashed = 0
                if (localOnly.isNotEmpty()) trashed = container.trash.moveToTrash(localOnly)
                if (remoteOnly.isNotEmpty()) {
                    // 按 VFS 会话分组删除（选中项可能来自不同会话，旧实现只用第一个的会话 → 其余报错）
                    val bySession = remoteOnly.groupBy { uri ->
                        container.locator.find(uri)?.let { System.identityHashCode(it) } ?: -1
                    }
                    for ((_, group) in bySession) {
                        val vfs = container.locator.find(group.first())
                            ?: throw VfsException.Unsupported("会话不可用：${group.first().authority}")
                        withContext(container.dispatchers.vfs) { vfs.delete(group) }
                    }
                }
                showStatus(
                    when {
                        fastDelete -> "已极速删除 ${sources.size} 项"
                        trashed > 0 -> "已移入回收站 $trashed 项（可还原）"
                        else -> "已删除 ${remoteOnly.size} 项"
                    }
                )
                clearSelection(side)
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "删除失败：${e.message}")
            }
        }
    }

    fun rename(uri: VfsUri, newName: String, side: PaneSide) {
        if (!isValidChildName(newName)) {
            showStatus("名称不能包含 / 或 .. 等字符")
            return
        }
        container.scope.launch {
            try {
                val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("会话不可用")
                val target = uri.parent?.child(newName) ?: throw VfsException.ProtocolError("无法重命名根目录")
                // MT：目标已存在且都不是文件夹时，弹「交换 / 删除 / 备份」选择
                val exists = runCatching { withContext(container.dispatchers.vfs) { vfs.stat(target) } }.getOrNull()
                if (exists != null && !exists.isDirectory) {
                    update { it.copy(renameConflict = RenameConflict(uri, target, uri.name)) }
                    return@launch
                }
                val ok = withContext(container.dispatchers.vfs) { vfs.rename(uri, target) }
                if (!ok) throw VfsException.ProtocolError("服务器拒绝重命名（可能需要服务端复制）")
                showStatus("已重命名为 $newName")
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
            }
        }
    }

    /** 处理重命名冲突（MT 的三种处理） */
    fun resolveRenameConflict(action: String) {
        val conflict = _state.value.renameConflict ?: return
        update { it.copy(renameConflict = null) }
        container.scope.launch {
            try {
                val vfs = container.locator.find(conflict.from) ?: throw VfsException.Unsupported("会话不可用")
                val side = _state.value.focused
                when (action) {
                    "swap" -> {
                        // 交换文件名：目标先改到临时名，源改到目标名，临时名再改成源名（失败则尽力回滚）
                        val tmp = conflict.target.parent?.child(".panelfm.swap.${System.currentTimeMillis()}")
                        if (tmp == null) throw VfsException.ProtocolError("无法交换")
                        withContext(container.dispatchers.vfs) {
                            vfs.rename(conflict.target, tmp)
                            try {
                                vfs.rename(conflict.from, conflict.target)
                                try {
                                    vfs.rename(tmp, conflict.from)
                                } catch (e: Exception) {
                                    runCatching { vfs.rename(conflict.target, conflict.from) }
                                    runCatching { vfs.rename(tmp, conflict.target) }
                                    throw e
                                }
                            } catch (e: Exception) {
                                runCatching { vfs.rename(tmp, conflict.target) }
                                throw e
                            }
                        }
                        showStatus("已交换文件名")
                    }
                    "delete" -> {
                        withContext(container.dispatchers.vfs) {
                            vfs.delete(listOf(conflict.target))
                            vfs.rename(conflict.from, conflict.target)
                        }
                        showStatus("已删除同名文件并完成重命名")
                    }
                    "backup" -> {
                        withContext(container.dispatchers.vfs) {
                            vfs.rename(conflict.target, conflict.target.parent?.child(conflict.target.name + ".bak") ?: conflict.target)
                            vfs.rename(conflict.from, conflict.target)
                        }
                        showStatus("原文件已备份为 .bak")
                    }
                }
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
            }
        }
    }

    fun dismissRenameConflict() = update { it.copy(renameConflict = null) }

    /**
     * MT「解压到单独的文件夹」：在 [parentDir] 下按压缩包名建一个同名目录再解压进去。
     * 名字冲突时自动加 (1)(2)…（MT 的行为：不会直接覆盖已有目录）。
     */
    fun extractToOwnFolder(side: PaneSide, parentDir: VfsUri) {
        val pane = pane(side)
        val archiveName = pane.archiveHostName ?: run {
            showStatus("无法确定压缩包名称")
            return
        }
        val folderName = archiveName.substringBeforeLast('.', archiveName)
        container.scope.launch {
            val target = container.uniqueChild(parentDir, folderName)
            runCatching { container.locator.find(parentDir)?.mkdir(target) }
            extractTo(side, target)
        }
    }

    /** 解压：把当前（压缩包内）选中项复制到指定目录 */
    fun extractTo(side: PaneSide, destDir: VfsUri) {
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可解压的项")
            return
        }
        update { it.copy(status = "解压 ${sources.size} 项 → ${destDir.displayPath}") }
        container.engine.enqueue(
            TransferRequest(sources = sources, destDir = destDir, op = TransferOp.COPY, conflict = ConflictPolicy.ASK)
        )
        clearSelection(side)
    }

    /** 压缩包完整性测试（ZIP：逐条读取校验 CRC） */
    fun testArchive(side: PaneSide) {
        val pane = pane(side)
        container.scope.launch {
            try {
                val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path)
                val host = encoded?.let { runCatching { VfsUri.parse(VfsUri.decodeHost(it)) }.getOrNull() }
                if (host == null) {
                    showStatus("当前不在压缩包内")
                    return@launch
                }
                val vfs = container.openArchive(host)
                val all = withContext(container.dispatchers.vfs) { listRecursive(vfs, com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(host, vfs.kind)) }
                var ok = 0
                var bad = 0
                all.forEach { item ->
                    if (!item.isDirectory) {
                        runCatching {
                            val reader = vfs.openRead(item.uri)
                            val buf = ByteArray(64 * 1024)
                            try {
                                while (reader.read(buf, 0, buf.size) >= 0) { /* 逐条读取校验完整性 */ }
                            } finally {
                                runCatching { reader.close() }
                            }
                        }.onSuccess { ok++ }.onFailure { bad++ }
                    }
                }
                showStatus(if (bad == 0) "压缩包完整性检查通过（$ok 个文件）" else "压缩包有 $bad 个文件损坏（共 $ok 正常）")
            } catch (e: Exception) {
                showStatus("测试失败：${e.message}")
            }
        }
    }

    private suspend fun listRecursive(
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        dir: VfsUri,
        depth: Int = 0,
    ): List<FileMetadata> {
        if (depth > 16) return emptyList()
        val out = ArrayList<FileMetadata>()
        withContext(container.dispatchers.vfs) { vfs.list(dir) }.forEach { item ->
            out.add(item)
            if (item.isDirectory) out.addAll(listRecursive(vfs, item.uri, depth + 1))
        }
        return out
    }

    /**
     * MT 的「搜索」：文件名匹配（沿用过滤语法：普通文本 / `!否定` / `/正则` / `!/正则`），
     * 可递归子目录；高级条件：按内容（文本类且 ≤2MB，读前 512K）与文件大小范围。
     * 最多返回 [limit] 条，扫描超过 [scanCap] 个条目即停止，避免卡死大目录。
     */
    suspend fun searchTree(
        side: PaneSide,
        nameQuery: String,
        recursive: Boolean,
        contentQuery: String = "",
        minSize: Long = -1L,
        maxSize: Long = -1L,
        nameRegex: Boolean = false,
        limit: Int = 300,
        scanCap: Int = 8000,
    ): List<FileMetadata> {
        val root = pane(side).uri
        val vfs = container.locator.find(root) ?: throw VfsException.Unsupported("会话不可用")
        val out = ArrayList<FileMetadata>()
        var scanned = 0

        // MT 搜索类型：文件名（包含 / 正则）
        val nameOk: (FileMetadata) -> Boolean = when {
            nameQuery.isBlank() -> { _ -> true }
            nameRegex -> { item -> runCatching { Regex(nameQuery).containsMatchIn(item.name) }.getOrDefault(false) }
            else -> { item -> matchesSearch(item.name, nameQuery) }
        }

        fun sizeOk(item: FileMetadata): Boolean =
            (minSize < 0 || item.size >= minSize) && (maxSize < 0 || item.size <= maxSize)

        suspend fun contentOk(item: FileMetadata): Boolean {
            if (contentQuery.isBlank()) return true
            if (item.isDirectory || item.size < 0 || item.size > 2 * 1024 * 1024) return false
            val kind = com.u707t.panelfm.core.common.MimeTypes.kindOf(item.extension)
            if (kind != com.u707t.panelfm.core.common.MimeTypes.Kind.TEXT &&
                kind != com.u707t.panelfm.core.common.MimeTypes.Kind.CODE &&
                kind != com.u707t.panelfm.core.common.MimeTypes.Kind.OTHER
            ) return false
            return runCatching {
                withContext(container.dispatchers.vfs) {
                    val reader = vfs.openRead(item.uri, 0, 512 * 1024)
                    try {
                        // 先整体读字节再按编码识别解码（旧实现逐块 String(buf) 会用平台默认字符集：
                        // UTF-8 中文在块边界被截断、GBK 文件整段乱码，中文内容搜索几乎不可用）
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (total < 512 * 1024) {
                            val n = reader.read(buf, 0, buf.size)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        val text = com.u707t.panelfm.core.common.TextEncodings.decode(out.toByteArray()).text
                        text.contains(contentQuery, ignoreCase = true)
                    } finally {
                        runCatching { reader.close() }
                    }
                }
            }.getOrDefault(false)
        }

        suspend fun walk(dir: VfsUri, depth: Int) {
            if (out.size >= limit || scanned >= scanCap || depth > 12) return
            val items = runCatching { withContext(container.dispatchers.vfs) { vfs.list(dir) } }
                .getOrDefault(emptyList())
            scanned += items.size
            for (item in items) {
                if (out.size >= limit) return
                if (!item.isDirectory &&
                    nameOk(item) &&
                    sizeOk(item) &&
                    contentOk(item)
                ) {
                    out.add(item)
                }
            }
            if (recursive) {
                for (item in items) {
                    if (out.size >= limit || scanned >= scanCap) return
                    if (item.isDirectory && !item.isHidden) walk(item.uri, depth + 1)
                }
            }
        }
        walk(root, 0)
        return out
    }

    /** 跳到该项所在目录并选中它（MT：搜索结果点击 = 定位到文件） */
    fun reveal(side: PaneSide, uri: VfsUri) {
        val parent = uri.parent ?: return
        val tab = pane(side).tab
        open(side, parent, tab.connectionId, tab.label)
        updatePane(side) { it.copy(selection = setOf(uri.toString())) }
    }

    fun createFolder(side: PaneSide, name: String) {
        val dir = pane(side).uri
        if (!isValidChildName(name)) {
            showStatus("名称不能包含 / 或 .. 等字符")
            return
        }
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
        if (!isValidChildName(name)) {
            showStatus("名称不能包含 / 或 .. 等字符")
            return
        }
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

    /** 新建 / 重命名用的名称校验：拒绝路径分隔符与穿越，避免写到目录之外 */
    private fun isValidChildName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == "..") return false
        return !n.contains('/') && !n.contains('\\') && !n.contains('\u0000')
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

    // ------------------------------------------------------------------ 压缩包内写操作（MT：添加/删除/重命名）

    private suspend fun archiveEditor(side: PaneSide): com.u707t.panelfm.core.vfs.archive.ZipEditor? {
        val pane = pane(side)
        if (pane.uri.scheme != "archive") return null
        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path) ?: return null
        val host = runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)) }.getOrNull() ?: return null
        val vfs = container.openArchive(host)
        if (vfs.kind != com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ZIP) {
            showStatus("只有 ZIP 支持内部修改（7z/tar 为只读）")
            return null
        }
        return com.u707t.panelfm.core.vfs.archive.ZipEditor(vfs, container.locator)
    }

    private suspend fun refreshArchive(side: PaneSide) {
        val pane = pane(side)
        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path)
        if (encoded != null) {
            val host = runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)) }.getOrNull()
            if (host != null) {
                // 先丢弃旧挂载（旧索引），再**重新挂载**——否则 load() 里 locator.find(archive://…)
                // 找不到会话，面板会变成「未连接」（旧实现漏了重挂载这一步）。
                container.forgetArchive(host.toString())
                runCatching { container.openArchive(host) }
                    .onFailure { showStatus("重新打开压缩包失败：${it.message}") }
            }
        }
        load(side)
    }

    /** 删除压缩包内条目（整包重写） */
    fun deleteInsideArchive(side: PaneSide, items: List<FileMetadata>) {
        container.scope.launch {
            try {
                val editor = archiveEditor(side) ?: return@launch
                showStatus("正在重写压缩包（删除 ${items.size} 项）…")
                val remove = items.map { entryPathOf(side, it) }.toSet()
                editor.rewrite(remove = remove)
                showStatus("已从压缩包删除 ${items.size} 项")
                clearSelection(side)
                refreshArchive(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "修改压缩包失败：${e.message}")
            }
        }
    }

    /** 重命名压缩包内条目（完整路径，可改父目录 = 移动） */
    fun renameInsideArchive(side: PaneSide, item: FileMetadata, newFullPath: String) {
        container.scope.launch {
            try {
                val editor = archiveEditor(side) ?: return@launch
                val from = entryPathOf(side, item)
                showStatus("正在重写压缩包…")
                editor.rewrite(rename = mapOf(from to newFullPath.trimStart('/')))
                showStatus("已更新压缩包")
                refreshArchive(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
            }
        }
    }

    /** 把对面窗格选中的项添加到当前压缩包（MT：和复制文件一样） */
    fun addToArchive(side: PaneSide) {
        val st = _state.value
        val other = st.pane(side.other)
        val sources = other.selectedItems.map { it.uri }.ifEmpty { other.items.map { it.uri } }
        if (sources.isEmpty()) {
            showStatus("对面窗格没有可添加的项")
            return
        }
        container.scope.launch {
            try {
                val editor = archiveEditor(side) ?: return@launch
                val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(pane(side).uri.path)
                showStatus("正在添加 ${sources.size} 项到压缩包…")
                val additions = sources.map { uri ->
                    val name = (if (inner.isEmpty()) "" else "$inner/") + uri.name
                    name to uri
                }
                editor.rewrite(additions = additions)
                showStatus("已添加 ${sources.size} 项到压缩包")
                refreshArchive(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "添加失败：${e.message}")
            }
        }
    }

    private fun entryPathOf(side: PaneSide, item: FileMetadata): String {
        val base = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(pane(side).uri.path)
        val rel = item.name
        return (if (base.isEmpty()) rel else "$base/$rel").trimStart('/')
    }

    // ------------------------------------------------------------------ 分隔条 / 路径

    /** 拖动分隔条 */
    fun setSplitRatio(ratio: Float) = update { it.copy(splitRatio = ratio.coerceIn(0.25f, 0.75f)) }

    /** 复制当前路径（MT：长按路径栏） */
    fun copyPath(side: PaneSide) {
        val uri = pane(side).uri
        showStatus("路径：${uri.toString()}")
    }

    // ------------------------------------------------------------------ 目录对比（M9）

    fun compareDirectories() {
        val st = _state.value
        container.scope.launch {
            showStatus("正在对比两个目录…")
            runCatching { FolderDiffEngine.compare(container, st.left.uri, st.right.uri) }
                .onSuccess { result ->
                    update { it.copy(diff = result) }
                    showStatus("对比完成：相同 ${result.identical} / 不同 ${result.different}")
                }
                .onFailure { showStatus("对比失败：${it.message}") }
        }
    }

    fun dismissDiff() = update { it.copy(diff = null) }

    /** 仅把「只在左侧」的项复制到右侧 */
    fun copyDiffOnlyLeft() {
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
    fun copyDiffNewer() {
        val diff = _state.value.diff ?: return
        val sources = diff.newerOnLeft.mapNotNull { it.left?.uri }
        if (sources.isEmpty()) {
            showStatus("没有较新的项")
            return
        }
        container.engine.enqueue(TransferRequest(sources, diff.rightDir, TransferOp.COPY, ConflictPolicy.OVERWRITE))
        showStatus("已开始同步 ${sources.size} 个较新项")
    }

    /** 长按「复制/移动 ->」= 单窗口操作：目标仍在本窗格内 */
    // ------------------------------------------------------------------ 剪贴板（MT 每窗格 FAB）

    /**
     * 把选中项「复制到剪贴板」：记录源 URI 列表（应用内剪贴板，跨窗格 / 跨会话可用）。
     * MT 的剪贴板图标 FAB 是「粘贴」，对应的复制入口在动作菜单。
     */
    fun copySelectionToClipboard(side: PaneSide) {
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可复制的项")
            return
        }
        clipboard = sources
        showStatus("已复制 ${sources.size} 项到剪贴板（可到其他目录粘贴）")
    }

    /** 剪贴板是否为空（UI 决定 FAB 是否显示） */
    val hasClipboard: Boolean get() = clipboard.isNotEmpty()

    /**
     * 从剪贴板粘贴到当前窗格目录（MT 的剪贴板 FAB）。
     * 移动语义：粘贴后清空剪贴板（与 MT 的「剪切后粘贴」一致）。
     */
    fun pasteFromClipboard(side: PaneSide, move: Boolean = false) {
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
        update { it.copy(highlight = true, status = "$opText ${reachable.size} 项 → ${dest.displayPath}") }
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

    /** 应用内剪贴板（源 URI 列表）。放控制器而不是系统剪贴板：能表达「多项 + 移动语义」 */
    private var clipboard: List<VfsUri> = emptyList()

    fun copyWithinPane(side: PaneSide, destDir: VfsUri) = enqueueWithinPane(side, TransferOp.COPY, destDir)

    fun moveWithinPane(side: PaneSide, destDir: VfsUri) = enqueueWithinPane(side, TransferOp.MOVE, destDir)

    private fun enqueueWithinPane(side: PaneSide, op: TransferOp, destDir: VfsUri) {
        val st = _state.value
        val srcPane = st.pane(side)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可操作的项")
            return
        }
        val opText = if (op == TransferOp.COPY) "复制" else "移动"
        update { it.copy(status = "$opText ${sources.size} 项 → ${destDir.displayPath}") }
        container.engine.enqueue(
            TransferRequest(sources = sources, destDir = destDir, op = op, conflict = ConflictPolicy.ASK)
        )
        clearSelection(side)
    }

    /** 压缩到当前目录（MT 的「压缩」）：支持 zip / 7z / tar / tar.gz / tar.bz2 */
    fun compressHere(
        side: PaneSide,
        format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format =
            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP,
        fileName: String? = null,
        level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level =
            com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
        password: String? = null,
        encryptNames: Boolean = false,
    ) {
        val st = _state.value
        val pane = st.pane(side)
        val sources = targetSources(side)
        if (sources.isEmpty()) {
            showStatus("当前目录没有可压缩的项")
            return
        }
        val base = fileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.zipNameFor(sources).removeSuffix(".zip")
        val name = if (base.endsWith(".${format.ext}")) base else "$base.${format.ext}"
        val dest = pane.uri.child(name)
        container.scope.launch {
            showStatus("正在压缩为 ${format.label} → $name")
            try {
                com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
                    .compress(sources, dest, format, level = level, password = password, encryptNames = encryptNames)
                showStatus("已压缩为 $name")
                clearSelection(side)
                load(side)
            } catch (e: Exception) {
                showStatus((e as? VfsException)?.userMessage ?: "压缩失败：${e.message}")
            }
        }
    }

    /**
     * 修改权限（MT 0x7f0c0096）：可选递归应用到子文件 / 子文件夹。
     * 递归时用 BFS 遍历（有深度与数量上限，避免超大目录卡死）。
     */
    fun changePermissions(
        uri: VfsUri,
        mode: Int,
        recurseFiles: Boolean,
        recurseDirs: Boolean,
    ) {
        container.scope.launch {
            val vfs = container.locator.find(uri)
            if (vfs == null) {
                showStatus("会话不可用")
                return@launch
            }
            if (!vfs.capabilities.permissions) {
                showStatus("该位置不支持修改权限")
                return@launch
            }
            var ok = 0
            var failed = 0
            runCatching { vfs.setPermissions(uri, mode) }
                .onSuccess { ok++ }
                .onFailure { failed++ }

            if (recurseFiles || recurseDirs) {
                showStatus("正在递归修改权限…")
                val queue = ArrayDeque<Pair<VfsUri, Int>>()
                queue += uri to 0
                var visited = 0
                while (queue.isNotEmpty() && visited < MAX_CHMOD_ITEMS) {
                    val (dir, depth) = queue.removeFirst()
                    if (depth > MAX_CHMOD_DEPTH) continue
                    val children = runCatching { vfs.list(dir) }.getOrNull() ?: continue
                    for (child in children) {
                        visited++
                        if (visited > MAX_CHMOD_ITEMS) break
                        if (child.isDirectory) {
                            if (recurseDirs) {
                                runCatching { vfs.setPermissions(child.uri, mode) }
                                    .onSuccess { ok++ }
                                    .onFailure { failed++ }
                            }
                            queue += child.uri to (depth + 1)
                        } else if (recurseFiles) {
                            runCatching { vfs.setPermissions(child.uri, mode) }
                                .onSuccess { ok++ }
                                .onFailure { failed++ }
                        }
                    }
                }
            }
            val octal = Integer.toOctalString(mode and 0xFFF)
            showStatus(
                if (failed == 0) "权限已修改为 $octal（$ok 项）"
                else "权限已修改：成功 $ok 项，失败 $failed 项"
            )
            refreshAll()
        }
    }

    /** 校验值（MD5/SHA-256）：本地与网络都能算 */
    fun checksum(uri: VfsUri, algorithm: String, onResult: (String?) -> Unit) {
        container.scope.launch {
            onResult(runCatching { checksumNow(uri, algorithm) }.getOrNull())
        }
    }

    /** 校验值的挂起实现（APK 信息页等复用；算法：CRC32 / MD5 / SHA-1 / SHA-256） */
    suspend fun checksumNow(uri: VfsUri, algorithm: String): String? {
        val vfs = container.locator.find(uri) ?: return null
        return withContext(container.dispatchers.vfs) {
            val reader = vfs.openRead(uri)
            try {
                // MT 的校验值清单（0x7f030009）：CRC32 / MD5 / SHA1 / SHA256
                // CRC32 不是 MessageDigest，单独走 java.util.zip.CRC32
                val crc = if (algorithm == "CRC32") java.util.zip.CRC32() else null
                val digest = if (crc == null) java.security.MessageDigest.getInstance(algorithm) else null
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) break
                    crc?.update(buf, 0, n)
                    digest?.update(buf, 0, n)
                }
                crc?.let { "%08x".format(it.value) }
                    ?: digest!!.digest().joinToString("") { "%02x".format(it) }
            } finally {
                runCatching { reader.close() }
            }
        }
    }

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
        // MT：另一窗口未打开有效路径时，提示无法操作
        if (overrideDest == null && destDir.scheme != "local" && destDir.scheme != "archive" &&
            container.locator.find(destDir) == null
        ) {
            showStatus("另一窗口未打开有效路径（${destDir.authority} 未连接），无法$opText")
            return
        }

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
            // 关键：必须观察 taskEvents（任务状态变化也会发射），否则进度 / 冲突 / 完成都到不了 UI。
            // 旧实现用 engine.tasks（StateFlow<List>）——列表不增删时永远不发射，
            // 导致冲突弹窗永不出现（ASK 任务卡死）、进度不更新、任务完成后不刷新。
            container.engine.taskEvents.collect { tasks ->
                val active = tasks.filter {
                    val s = it.state.value
                    s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
                }
                val snapshots = tasks.take(8).map { it.toSnapshot() }
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
        container.engine.findTask(id)?.pause()
    }

    fun resumeTask(id: String) {
        container.engine.findTask(id)?.resume()
    }

    fun cancelTask(id: String) {
        container.engine.findTask(id)?.cancel()
    }

    fun clearFinishedTasks() = container.engine.clearFinished()

    fun allTasks(): List<TransferTask> = container.engine.tasks.value

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

/**
 * MT「文件列表显示」三档（0x7f110200/201/202）：
 *  - 0 不显示权限
 *  - 1 非存储目录下的文件显示「权限+大小」（默认）
 *  - 2 全部目录下的文件显示「时间+大小」
 * 这里作用于**底部统计行**：把原本固定显示的「文件夹/文件/已用」按档位调整。
 */
fun PaneState.summaryFor(mode: Int): String = when (mode) {
    0 -> "文件夹: $dirCount  文件: $fileCount"
    2 -> buildString {
        append("文件夹: ").append(dirCount).append("  文件: ").append(fileCount)
        space?.let { append("   已用 ").append(Fmt.size(it.total - it.free)).append(" / 可用 ").append(Fmt.size(it.free)) }
    }
    else -> buildString {
        append("文件夹: ").append(dirCount).append("  文件: ").append(fileCount)
        space?.let { append("   ").append(Fmt.size(it.total - it.free)).append("/").append(Fmt.size(it.total)) }
    }
}

/** 递归 chmod 的安全上限（防超大目录把界面拖死） */
private const val MAX_CHMOD_ITEMS = 20_000
private const val MAX_CHMOD_DEPTH = 32

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

// --------------------------------------------------------------------------- MT 排序规则编解码

/** "By|asc|dirs" ⇄ SortSpec（用于「仅应用于此文件夹」的记忆规则） */
internal fun encodeSortSpec(spec: com.u707t.panelfm.core.model.SortSpec): String =
    "${spec.by.name}|${spec.ascending}|${spec.dirsFirst}"

internal fun decodeSortSpec(line: String): com.u707t.panelfm.core.model.SortSpec? {
    val parts = line.split('|')
    if (parts.size != 3) return null
    val by = runCatching { com.u707t.panelfm.core.model.SortBy.valueOf(parts[0]) }.getOrNull() ?: return null
    return com.u707t.panelfm.core.model.SortSpec(
        by = by,
        ascending = parts[1].toBooleanStrictOrNull() ?: true,
        dirsFirst = parts[2].toBooleanStrictOrNull() ?: true,
    )
}

// --------------------------------------------------------------------------- 网络存储「初始路径」

/**
 * 把当前路径换算成连接的「初始路径」选项值（MT 0x7f1104ab 的写入逻辑）。
 *
 * 语义（与 [ConnectionConfig.openPath] 互为逆运算）：
 *  - WebDAV：basePath 是服务挂载点，初始路径相对**虚拟根**（去前导 `/`）；
 *  - 其余协议：初始路径相对 basePath（根目录 → 空串 = 清除选项）。
 *
 * @return 相对初始路径（可为空串 = 连接根）；**null = 当前路径不在连接根内**（无法表达）。
 */
internal fun initialPathFor(config: ConnectionConfig, currentPath: String): String? {
    val p = currentPath.trimEnd('/')
    return if (config.type == ConnectionType.WEBDAV) {
        p.trim('/')
    } else {
        val base = config.basePath.ifBlank { "/" }.trimEnd('/')
        when {
            base.isEmpty() -> p.trim('/')
            p == base -> ""
            p.startsWith("$base/") -> p.removePrefix(base).trim('/')
            else -> null
        }
    }
}
