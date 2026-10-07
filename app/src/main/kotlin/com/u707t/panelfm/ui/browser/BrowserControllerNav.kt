package com.u707t.panelfm.ui.browser

// ================================================================================================
// BrowserController 拆分（extension）：列表加载 / 导航 / 标签页 / 滚动位置记忆
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

// ------------------------------------------------------------------ 列表加载

private fun BrowserController.isCurrentLoad(side: PaneSide, generation: Long, uri: VfsUri): Boolean =
    loadGeneration.getValue(side).get() == generation && pane(side).uri == uri

private fun BrowserController.updateLoad(
    side: PaneSide,
    generation: Long,
    uri: VfsUri,
    transform: (PaneState) -> PaneState,
) {
    if (isCurrentLoad(side, generation, uri)) updatePane(side, transform)
}

fun BrowserController.load(side: PaneSide) {
    loadJobs[side]?.cancel()
    // 每次导航（open / 返回 / 上级 / 切标签 / 刷新）都排一次「上次路径」保存
    schedulePersistPaths()
    val generation = loadGeneration.getValue(side).incrementAndGet()
    val pane = pane(side)
    val uri = pane.uri
    // MT「仅应用于此文件夹」：该路径有记忆排序 → 覆盖当前窗格排序
    // 生效排序 = 该目录的专属规则（若有）> 本窗格排序。
    // ⚠️ 不能把规则回写到 pane.sort：否则离开该目录后，规则会「泄漏」到所有无规则目录。
    val effSort = sortSpecFor(side)
    // MT 加载遮罩（0x7f0c0033 的 09020D/09020E）：连接 → 枚举 → 过滤 → 完成 分阶段上报百分比
    updateLoad(side, generation, uri) { it.copy(loading = true, loadProgress = 0.05f, error = null) }
    loadJobs[side] = container.scope.launch {
        try {
            val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("未连接：${uri.authority}（请先在主页添加/打开该存储）")
            vfs.connect()
            updateLoad(side, generation, uri) { it.copy(loadProgress = 0.3f) }
            val options = ListOptions(
                sort = effSort,
                showHidden = pane.showHidden,
                filter = null,      // 关键字过滤统一在客户端做（支持 /regex、!/regex、!text）
            )
            val listed = withContext(container.dispatchers.vfs) { vfs.list(uri, options) }
            updateLoad(side, generation, uri) { it.copy(loadProgress = 0.75f) }
            val bySearch = listed.filter { matchesSearch(it.name, pane.search) }
            val items = pane.filterKind?.let { kindName ->
                bySearch.filter {
                    it.isDirectory || it.extension.isEmpty() ||
                        com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kindName
                }
            } ?: bySearch
            updateLoad(side, generation, uri) { it.copy(loadProgress = 0.9f) }
            val space = runCatching { withContext(container.dispatchers.vfs) { vfs.space(uri) } }.getOrNull()
            // loadedUri = 「这批 items 属于哪个目录」：滚动位置记忆按它来记，避免串目录
            updateLoad(side, generation, uri) {
                it.copy(
                    items = items,
                    loading = false,
                    loadProgress = null,
                    error = null,
                    space = space,
                    loadedUri = VfsUris.stripped(uri).toString(),
                    // 内容换了一批 → 滑动锚点失效（否则下一次滑动会连着旧列表的项算区间）
                    selectionAnchor = null,
                    tapAnchor = null,
                )
            }
            if (isCurrentLoad(side, generation, uri)) {
                runCatching { container.bookmarkDao.recordVisit(uri, pane.tab.connectionId) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户点了遮罩上的「取消」：不是错误，保持当前列表
            throw e
        } catch (e: Exception) {
            Logx.w("Browser", "list failed ${uri}: ${e.message}", e)
            updateLoad(side, generation, uri) {
                it.copy(
                    loading = false,
                    loadProgress = null,
                    error = (e as? VfsException)?.userMessage ?: (e.message ?: "加载失败"),
                    items = emptyList(),
                    loadedUri = null,
                    selectionAnchor = null,
                    tapAnchor = null,
                )
            }
        }
    }
}

/** 取消当前加载（MT 加载遮罩的「取消」，0x7f0c0033 09020F/090210） */
fun BrowserController.cancelLoad(side: PaneSide) {
    loadGeneration.getValue(side).incrementAndGet()
    val job = loadJobs.remove(side) ?: return
    job.cancel()
    updatePane(side) { it.copy(loading = false, loadProgress = null) }
    showStatus("已取消加载")
}

/**
 * 刷新当前目录。
 *
 * **保持滚动位置**（与「列表别跳回顶部重新加载」一致）：刷新只换内容，
 * 位置由 PaneView 的恢复逻辑按记忆位置还原；若目录变短了会自动夹到有效范围。
 */
fun BrowserController.refresh(side: PaneSide) = load(side)

fun BrowserController.refreshAll() {
    load(PaneSide.LEFT)
    load(PaneSide.RIGHT)
}
// ------------------------------------------------------------------ 导航

fun BrowserController.open(side: PaneSide, uri: VfsUri, connectionId: Long? = null, label: String? = null, pushHistory: Boolean = true) {
    val pane = pane(side)
    val tab = pane.tab
    // 连接号必须落在 URI 上（`?c=`），否则同主机多账号会被 VfsUri.sameMount 判成同一挂载点：
    // `FileOperationPlanner.isInside` 会误报「目标在源内部」直接拒绝操作，
    // `isSameOrDescendant` 会让 KEEP_BOTH / SKIP 的子树映射判错。
    // 旧实现只在「从主页/抽屉打开连接」时手工拼 c=，书签、最近路径、同步、返回上级都漏了；
    // 这里统一补上，让所有入口一致（已经是同一个连接的 URI 则原样保留）。
    //
    // 跨协议跳转（如在 SFTP 窗格里输入 s3://…）不能沿用当前 tab 的连接号：
    // 那会把连接号盖到别的协议 URI 上，SessionLocator 会先按连接号命中错误的会话。
    // 跨协议时按目标 URI 自己反查连接（connectionForUri 含 S3 bucket 兜底）。
    val effectiveConnId = when {
        uri.scheme == "local" || uri.scheme == "archive" -> null
        uri.scheme == tab.uri.scheme -> connectionId ?: tab.connectionId
        else -> container.connectionForUri(uri)?.id
    }
    val stamped = if (effectiveConnId != null) VfsUris.withConnection(uri, effectiveConnId) else uri
    if (tab.uri == stamped) {
        load(side)
        return
    }
    return openStamped(side, stamped, effectiveConnId, label, pushHistory)
}

private fun BrowserController.openStamped(
    side: PaneSide,
    uri: VfsUri,
    connectionId: Long?,
    label: String?,
    pushHistory: Boolean,
) {
    val pane = pane(side)
    val tab = pane.tab
    // 离开当前目录前先把滚动位置存下来（返回时才能停在原地）
    flushScroll(side)
    val newBack = if (pushHistory) (tab.back + tab.uri).takeLast(BrowserController.HISTORY_LIMIT) else tab.back
    val newTab = tab.copy(
        uri = uri,
        connectionId = connectionId,
        label = label ?: tab.label,
        back = newBack,
        forward = if (pushHistory) emptyList() else tab.forward,
    )
    updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }).withSelectionCleared() }
    load(side)
}

/**
 * 点击列表项（对齐 MT 的「打开方式」逻辑）：
 *  - 目录 → 进入
 *  - 该扩展名设置了默认打开方式 → 直接用它
 *  - 压缩包（未设置默认）→ 进入压缩包内部浏览
 *  - 其它 → 预览（自动识别）
 */
/**
 * 点击列表项（对齐 MT 的「打开方式」逻辑）：
 *  - 目录 → 进入
 *  - 该扩展名设置了默认打开方式 → 直接用它
 *  - 压缩包（未设置默认）→ 进入压缩包内部浏览
 *  - 其它 → 预览（自动识别）
 *
 * 注意：查「默认打开方式」要读数据库。这是**每次点击**都会走的路径，
 * 绝不能在主线程做（旧实现在点击回调里同步查 SQLite → 列表点起来发涩）。
 */
fun BrowserController.openItem(side: PaneSide, item: FileMetadata) {
    if (item.isDirectory) {
        open(side, item.uri)
        return
    }
    container.scope.launch {
        val pref = runCatching { container.previewPrefDao.get(item.extension) }.getOrNull()
        val mode = com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(pref)
        when {
            mode == com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE -> openArchiveInPane(side, item)
            mode != null -> openWith(item, mode)
            else -> {
                val isArchive = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind
                    .ofFileName(item.name) != null
                if (isArchive) {
                    openArchiveInPane(side, item)
                } else {
                    _previewRequest.value = com.u707t.panelfm.ui.preview.PreviewRequest(
                        item.uri, com.u707t.panelfm.ui.preview.PreviewMode.AUTO,
                    )
                }
            }
        }
    }
}

/** 用指定方式打开（打开方式对话框 / 默认值都走这里） */
fun BrowserController.openWith(item: FileMetadata, mode: com.u707t.panelfm.ui.preview.PreviewMode) {
    when (mode) {
        com.u707t.panelfm.ui.preview.PreviewMode.ARCHIVE -> openArchiveInPane(_state.value.focused, item)
        else -> _previewRequest.value = com.u707t.panelfm.ui.preview.PreviewRequest(item.uri, mode)
    }
}

fun BrowserController.setDefaultOpenMode(item: FileMetadata, mode: com.u707t.panelfm.ui.preview.PreviewMode) {
    if (item.extension.isBlank()) {
        showStatus("没有后缀的文件不能设置默认打开方式")
        return
    }
    container.scope.launch {
        container.previewPrefDao.set(item.extension, mode.handlerId)
        showStatus(".${item.extension} 的默认打开方式已设为「${mode.label}」")
    }
}

fun BrowserController.clearOpenMode(ext: String) {
    container.scope.launch {
        container.previewPrefDao.clear(ext)
        showStatus("已删除 .$ext 的默认打开方式")
    }
}

/** 默认打开方式清单（明确跑在 IO 上：供 Compose 用，避免组合期查库卡帧）。 */
suspend fun BrowserController.openModesSuspend(): List<Pair<String, String>> = withContext(container.dispatchers.io) {
    runCatching { container.previewPrefDao.all() }.getOrDefault(emptyList())
}

fun BrowserController.defaultOpenMode(item: FileMetadata): com.u707t.panelfm.ui.preview.PreviewMode? =
    com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(runCatching { container.previewPrefDao.get(item.extension) }.getOrNull())

suspend fun BrowserController.defaultOpenModeSuspend(item: FileMetadata): com.u707t.panelfm.ui.preview.PreviewMode? =
    withContext(container.dispatchers.io) {
        com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(
            runCatching { container.previewPrefDao.get(item.extension) }.getOrNull(),
        )
    }

/** 文件对比（对齐 MT：两个文件才能在长按菜单里对比） */
fun BrowserController.startFileDiff(side: PaneSide) {
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

fun BrowserController.back(side: PaneSide) {
    val tab = pane(side).tab
    val prev = tab.back.lastOrNull() ?: return
    flushScroll(side)
    val newTab = tab.copy(back = tab.back.dropLast(1), forward = (tab.forward + tab.uri).takeLast(BrowserController.HISTORY_LIMIT), uri = prev)
    updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }).withSelectionCleared() }
    load(side)
}

fun BrowserController.forward(side: PaneSide) {
    val tab = pane(side).tab
    val next = tab.forward.lastOrNull() ?: return
    flushScroll(side)
    val newTab = tab.copy(forward = tab.forward.dropLast(1), back = (tab.back + tab.uri).takeLast(BrowserController.HISTORY_LIMIT), uri = next)
    updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }).withSelectionCleared() }
    load(side)
}

fun BrowserController.up(side: PaneSide) {
    val uri = pane(side).uri
    flushScroll(side)
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

fun BrowserController.newTab(side: PaneSide) {
    val pane = pane(side)
    val tab = pane.tab
    val tabs = pane.tabs + PaneTab(uri = tab.uri, connectionId = tab.connectionId, label = tab.label)
    updatePane(side) { it.copy(tabs = tabs, activeTab = tabs.lastIndex).withSelectionCleared() }
}

fun BrowserController.switchTab(side: PaneSide, index: Int) {
    val pane = pane(side)
    if (index !in pane.tabs.indices || index == pane.activeTab) return
    updatePane(side) { it.copy(activeTab = index).withSelectionCleared() }
    load(side)
}

fun BrowserController.closeTab(side: PaneSide, index: Int) {
    val pane = pane(side)
    if (pane.tabs.size <= 1) return
    val tabs = pane.tabs.toMutableList().also { it.removeAt(index) }
    val newActive = closeTabNewActive(pane.activeTab, index, tabs.size)
    updatePane(side) { it.copy(tabs = tabs, activeTab = newActive).withSelectionCleared() }
    load(side)
}

/** 跳到该项所在目录并选中它（MT：搜索结果点击 = 定位到文件） */
fun BrowserController.reveal(side: PaneSide, uri: VfsUri) {
    val parent = uri.parent ?: return
    val tab = pane(side).tab
    open(side, parent, tab.connectionId, tab.label)
    // 选中 + 滚动到该项（MT：搜索结果点进去直接定位，不靠用户自己找）
    updatePane(side) { it.copy(selection = setOf(uri.toString()), scrollToUri = uri.toString()) }
}

/** PaneView 消费完滚动请求后清空（避免重复滚动） */
fun BrowserController.consumeScrollTo(side: PaneSide) = updatePane(side) { if (it.scrollToUri == null) it else it.copy(scrollToUri = null) }

/** PaneView 进入组合时注册（DisposableEffect 里注销） */
fun BrowserController.registerScrollSaver(side: PaneSide, saver: () -> Unit) {
    scrollSavers[side] = saver
}

fun BrowserController.unregisterScrollSaver(side: PaneSide) {
    scrollSavers.remove(side)
}

/** 切目录前把所有窗格的滚动位置落盘（导航方法统一调用） */
private fun BrowserController.flushScroll(side: PaneSide) {
    runCatching { scrollSavers[side]?.invoke() }
}

/** 滚动位置的 key：用「去连接参数的 uri 字符串」，避免 query 抖动、并隔离不同连接 */
private fun BrowserController.scrollKey(uri: VfsUri): String = VfsUris.stripped(uri).toString()

/**
 * 界面在列表滚动 / 离开目录时调用，记下该目录的滚动位置。
 *
 * @param listIndex 含 `..` 行的列表下标（调用方用 [ScrollMemory.toListIndex] 换算）
 */
@Synchronized
fun BrowserController.rememberScroll(side: PaneSide, uri: VfsUri, listIndex: Int, offset: Int) {
    scrollMemory[side]?.remember(scrollKey(uri), listIndex, offset)
}

/** 取某个目录上次的滚动位置；没有则 null（= 停在顶部） */
@Synchronized
fun BrowserController.recallScroll(side: PaneSide, uri: VfsUri): com.u707t.panelfm.core.common.ScrollMemory.Entry? =
    scrollMemory[side]?.recall(scrollKey(uri))

@Synchronized
fun BrowserController.swapPanes() {
    val st = _state.value
    // 先落盘两侧位置，再把记忆跟着内容一起换边（否则交换后位置记忆留在原侧 = 丢失）
    flushScroll(PaneSide.LEFT)
    flushScroll(PaneSide.RIGHT)
    scrollMemory = mapOf(
        PaneSide.LEFT to scrollMemory.getValue(PaneSide.RIGHT),
        PaneSide.RIGHT to scrollMemory.getValue(PaneSide.LEFT),
    )
    update { it.copy(left = st.right, right = st.left) }
}

/** 同步按钮：把非激活窗格跳到激活窗格相同路径（压缩包内部则定位到压缩包所在目录） */
fun BrowserController.syncPath() {
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

private fun BrowserController.parseArchiveHost(uri: VfsUri): VfsUri? {
    if (uri.scheme != "archive") return null
    val raw = uri.path.trimStart('/').substringBefore("!/")
    if (raw.isEmpty()) return null
    return runCatching { VfsUri.parse(VfsUri.decodeHost(raw)) }.getOrNull()
}


