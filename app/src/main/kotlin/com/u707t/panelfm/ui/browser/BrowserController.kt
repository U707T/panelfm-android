package com.u707t.panelfm.ui.browser

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

    /** 长操作（审计 U4）：同一时间至多一个；进度状态条 + 取消。 */
    private val busyIdGen = AtomicLong(0)
    private var busyJob: Job? = null
    private val loadGeneration = mutableMapOf(
        PaneSide.LEFT to AtomicLong(0),
        PaneSide.RIGHT to AtomicLong(0),
    )

    /**
     * 每个窗格各自的「目录 → 滚动位置」记忆（复刻 MT：进子目录再返回，列表停在原地）。
     *
     * 为什么放在控制器（而不是 Composable 里 `remember`）：窗格会因单/双列切换、
     * 抽屉开关等原因重组甚至重建，位置必须活到控制器这一层才稳。
     */
    private var scrollMemory = mapOf(
        PaneSide.LEFT to com.u707t.panelfm.core.common.ScrollMemory(),
        PaneSide.RIGHT to com.u707t.panelfm.core.common.ScrollMemory(),
    )

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
                    // 读回持久化的浏览模式；老数据没有该键时退回 useSingleColumn 派生值
                    val savedMode = runCatching { BrowseMode.valueOf(s.browseMode) }.getOrNull()
                        ?: if (s.useSingleColumn) BrowseMode.SINGLE else BrowseMode.DUAL
                    update {
                        it.copy(
                            splitRatio = s.splitRatio,
                            browseMode = savedMode,
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
            try {
                // ⚠️ 必须等 DataStore 的**第一次真发射**再读设置：`settings` StateFlow 的初值是
                // 默认值（构造时构造），直接读会拿到 startAtHome=false —— 用户勾了「启动时进入
                // 首页」也会被忽略。（顺带修掉旧实现的这一处。）
                val s0 = container.prefs.settings.first()
                // MT「启动路径 - 左/右窗口」：勾了「首页」就不恢复上次路径，改由 homePath 决定
                if (s0.startAtHome) {
                    val home = s0.homePath?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
                    if (home != null && ensureReachable(home)) {
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
                // 网络路径冷启动时还没连接 → 自动重连（旧实现只查 locator.find，
                // 网络窗格必然查不到 → 恢复被静默跳过，看起来就像「没记住位置」）
                val leftOk = leftUri != null && ensureReachable(leftUri)
                val rightOk = rightUri != null && ensureReachable(rightUri)
                if (!leftOk && !rightOk) return@launch
                update { st ->
                    st.copy(
                        left = if (leftOk && st.left.uri.isRoot)
                            st.left.copy(tabs = listOf(PaneTab(leftUri!!, label = leftUri.authority)), sort = st.left.sort)
                        else st.left,
                        right = if (rightOk && st.right.uri.isRoot)
                            st.right.copy(tabs = listOf(PaneTab(rightUri!!, label = rightUri.authority)), sort = st.right.sort)
                        else st.right,
                    )
                }
                if (leftOk) load(PaneSide.LEFT)
                if (rightOk) load(PaneSide.RIGHT)
            } finally {
                // ⚠️ 恢复流程结束前**禁止自动保存**：启动最初的两次 `load()`（默认路径）若先落盘，
                // 会把「上次路径」冲掉，恢复就永远读到默认值 —— 这正是「看起来没记住」的另一半原因。
                startupRestoreDone = true
            }
        }
    }

    /**
     * 路径可达性：本地直接查；网络路径若未挂载则**按连接配置自动重连**
     * （与「打开书签」同一口径 —— 否则冷启动恢复网络窗格永远失败）；
     * 压缩包内路径则先备好宿主、再重新挂载压缩包。
     */
    private suspend fun ensureReachable(uri: VfsUri): Boolean {
        if (container.locator.find(uri) != null) return true
        return when (uri.scheme) {
            "local" -> false
            "archive" -> {
                val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(uri.path) ?: return false
                val host = VfsUri.decodeHost(encoded)?.let { runCatching { VfsUri.parse(it) }.getOrNull() } ?: return false
                if (!ensureReachable(host)) return false
                runCatching { container.openArchive(host) }.isSuccess
            }
            else -> {
                val config = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                    ?: container.connectionByAuthority(uri.scheme, uri.authority)
                    ?: return false
                runCatching { container.openConnection(config) }.isSuccess
            }
        }
    }

    /** 退出前保存双列路径（MT：记忆上次路径） */
    fun persistPaths() {
        if (!startupRestoreDone) return
        if (!container.settings.value.rememberLastPath) return
        val st = _state.value
        container.scope.launch {
            container.prefs.saveLastPaths(st.left.uri.toString(), st.right.uri.toString())
        }
    }

    /** 启动恢复完成前禁止自动保存（否则默认路径会把「上次路径」覆盖掉，恢复永远读到默认值） */
    @Volatile
    private var startupRestoreDone = false

    /** 防抖保存的挂起标记（container.scope 跑在 IO 线程池上，用原子量避免重复排程） */
    private val persistScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 导航后的**防抖保存**（v1.3.7）。
     *
     * 旧实现只在 `AppRoot` 的 `onDispose` 里保存 —— 而默认退出方式是 `moveTaskToBack`
     * （Activity 不销毁），加上「划掉任务 / 杀进程」都不会触发 onDispose，
     * 结果就是**从来没存上**：用户反馈「没有固定上次退出时的两个窗格位置」。
     *
     * 现在每次 [load]（= 每次导航 / 切标签）后都排一次保存：300ms 内连续操作只写一次，
     * 无论用哪种方式退出，磁盘里都是最新位置。另有 `MainActivity.onStop` 兜底。
     */
    fun schedulePersistPaths() {
        if (!startupRestoreDone) return
        if (!container.settings.value.rememberLastPath) return
        if (!persistScheduled.compareAndSet(false, true)) return
        container.scope.launch {
            kotlinx.coroutines.delay(300)
            persistScheduled.set(false)
            persistPaths()
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

    /** 单/双列快捷切换（保留给旧入口；等价于在 SINGLE / DUAL 之间切换） */
    fun toggleSinglePane() = update {
        it.copy(browseMode = if (it.effectiveBrowseMode == BrowseMode.SINGLE) BrowseMode.DUAL else BrowseMode.SINGLE)
    }

    /**
     * 设置浏览模式三档（MT「单列 / 双列 / 自动切换」）。
     *
     * 必须持久化：旧实现只改内存状态，重启就回落到 `useSingleColumn` 派生值，
     * 「自动切换」永远留不住（见审计报告 R4）。
     */
    fun setBrowseMode(mode: BrowseMode) {
        update { it.copy(browseMode = mode) }
        container.scope.launch { container.prefs.setBrowseMode(mode.name) }
    }

    /** 界面按屏宽写入（供 [BrowseMode.AUTO] 判定） */
    fun setWideEnough(wide: Boolean) = update { if (it.wideEnough == wide) it else it.copy(wideEnough = wide) }

    // ------------------------------------------------------------------ MT「选择当前目录」模式（0x7f0c0025）

    /** 进入「选择当前目录」模式：底栏上方浮出「选择当前目录」按钮 */
    fun startPickDir(purpose: PickDirPurpose) = update { it.copy(pickDirFor = purpose) }

    /** 取消「选择当前目录」模式 */
    fun cancelPickDir() {
        pendingArchiveExtract = null
        update { it.copy(pickDirFor = null) }
    }

    /**
     * 确认「选择当前目录」：把活动窗格当前路径交给对应流程。
     * 返回选中的目录（UI 侧据此调用具体动作），并退出该模式。
     */
    fun confirmPickDir(): Pair<PickDirPurpose, VfsUri>? {
        val st = _state.value
        val purpose = st.pickDirFor ?: return null
        val dir = st.focusedPane.uri
        update { it.copy(pickDirFor = null) }
        return purpose to dir
    }

    /**
     * 状态消息**队列**（审计 U9）：消息排队显示，不再互相顶掉；
     * 错误类消息多停一倍时间（[statusDurationMs]），UI 每条消费完才轮到下一条。
     */
    private val _statusQueue = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val statusQueue: kotlinx.coroutines.flow.StateFlow<List<String>> get() = _statusQueue

    fun consumeStatus() {
        update { it.copy(status = null) }
        _statusQueue.update { it.drop(1) }
    }

    fun dismissPreviewRequest() { _previewRequest.value = null }

    fun showStatus(message: String) {
        update { it.copy(status = message) }
        _statusQueue.update { (it + message).takeLast(8) }
    }

    /** 一条状态提示显示多久（纯函数在 [statusDurationMs]，便于单测） */
    fun statusDurationMs(message: String): Long = com.u707t.panelfm.ui.browser.statusDurationMs(message)

    // ------------------------------------------------------------------ 长操作（审计 U4）

    /**
     * 统一的「长操作」执行器：同一时间只允许一个长操作在跑，期间页面底部显示
     * 一条**不可消失**的状态条（进度 + 取消）。
     *
     * [block] 通过 [BusyReporter] 上报进度；每次上报都会做取消检查 —— 压缩 / 校验 /
     * 下载等按块循环的长任务在一次循环内就能响应「取消」。
     */
    private fun launchBusy(title: String, block: suspend (BusyReporter) -> Unit) {
        if (busyJob?.isActive == true) {
            showStatus("已有操作进行中（${_state.value.busy?.title ?: "请稍候"}），完成或取消后再试")
            return
        }
        val id = busyIdGen.incrementAndGet()
        update { it.copy(busy = BusyOp(id = id, title = title)) }
        busyJob = container.scope.launch {
            val ctxJob = kotlin.coroutines.coroutineContext[Job]
            val reporter = BusyReporter(
                ensureActive = { if (ctxJob?.isActive != true) throw CancellationException("长操作已取消") },
                sink = { progress, detail ->
                    update { st ->
                        if (st.busy?.id == id) st.copy(busy = st.busy.copy(progress = progress, detail = detail))
                        else st
                    }
                },
            )
            var announced = false
            try {
                block(reporter)
            } catch (e: CancellationException) {
                // 用户取消不是失败（审计 U5 同类教训），原样上抛给协程框架
                announced = true
                showStatus("已取消：$title")
                throw e
            } catch (e: Exception) {
                announced = true
                Logx.w("Browser", "长操作失败：$title", e)
                showStatus((e as? VfsException)?.userMessage ?: "$title 失败：${e.message ?: e::class.simpleName}")
            } finally {
                update { st -> if (st.busy?.id == id) st.copy(busy = null) else st }
                if (ctxJob?.isActive == false && !announced) showStatus("已取消：$title")
            }
        }
    }

    /** 「取消」：立即反馈「正在取消…」，作业在下一个取消点收尾（见 [launchBusy]）。 */
    fun cancelBusy() {
        val op = _state.value.busy ?: return
        if (!op.cancellable) return
        val job = busyJob ?: run {
            update { it.copy(busy = null) }
            return
        }
        update { st ->
            if (st.busy?.id == op.id) st.copy(busy = st.busy.copy(cancellable = false, detail = "正在取消…"))
            else st
        }
        job.cancel()
    }

    // ------------------------------------------------------------------ 列表加载

    private fun isCurrentLoad(side: PaneSide, generation: Long, uri: VfsUri): Boolean =
        loadGeneration.getValue(side).get() == generation && pane(side).uri == uri

    private fun updateLoad(
        side: PaneSide,
        generation: Long,
        uri: VfsUri,
        transform: (PaneState) -> PaneState,
    ) {
        if (isCurrentLoad(side, generation, uri)) updatePane(side, transform)
    }

    fun load(side: PaneSide) {
        loadJobs[side]?.cancel()
        // 每次导航（open / 返回 / 上级 / 切标签 / 刷新）都排一次「上次路径」保存
        schedulePersistPaths()
        val generation = loadGeneration.getValue(side).incrementAndGet()
        val pane = pane(side)
        val uri = pane.uri
        // MT「仅应用于此文件夹」：该路径有记忆排序 → 覆盖当前窗格排序
        val ruleSort = container.settings.value.folderSorts[VfsUris.stripped(uri).toString()]?.let { decodeSortSpec(it) }
        val effSort = ruleSort ?: pane.sort
        if (effSort != pane.sort) updatePane(side) { it.copy(sort = effSort) }
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
    fun cancelLoad(side: PaneSide) {
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
    fun refresh(side: PaneSide) = load(side)

    fun refreshAll() {
        load(PaneSide.LEFT)
        load(PaneSide.RIGHT)
    }

    // ------------------------------------------------------------------ 导航

    fun open(side: PaneSide, uri: VfsUri, connectionId: Long? = null, label: String? = null, pushHistory: Boolean = true) {
        val pane = pane(side)
        val tab = pane.tab
        // 连接号必须落在 URI 上（`?c=`），否则同主机多账号会被 VfsUri.sameMount 判成同一挂载点：
        // `FileOperationPlanner.isInside` 会误报「目标在源内部」直接拒绝操作，
        // `isSameOrDescendant` 会让 KEEP_BOTH / SKIP 的子树映射判错。
        // 旧实现只在「从主页/抽屉打开连接」时手工拼 c=，书签、最近路径、同步、返回上级都漏了；
        // 这里统一补上，让所有入口一致（已经是同一个连接的 URI 则原样保留）。
        val effectiveConnId = connectionId ?: tab.connectionId
        val stamped = if (effectiveConnId != null && uri.scheme != "local" && uri.scheme != "archive") {
            VfsUris.withConnection(uri, effectiveConnId)
        } else {
            uri
        }
        if (tab.uri == stamped) {
            load(side)
            return
        }
        return openStamped(side, stamped, effectiveConnId, label, pushHistory)
    }

    private fun openStamped(
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
        val newBack = if (pushHistory) (tab.back + tab.uri).takeLast(HISTORY_LIMIT) else tab.back
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
    fun openItem(side: PaneSide, item: FileMetadata) {
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

    /** 同上，但明确跑在 IO 上（供 Compose 用，避免组合期查库卡帧）。 */
    suspend fun openModesSuspend(): List<Pair<String, String>> = withContext(container.dispatchers.io) {
        runCatching { container.previewPrefDao.all() }.getOrDefault(emptyList())
    }

    fun defaultOpenMode(item: FileMetadata): com.u707t.panelfm.ui.preview.PreviewMode? =
        com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(runCatching { container.previewPrefDao.get(item.extension) }.getOrNull())

    suspend fun defaultOpenModeSuspend(item: FileMetadata): com.u707t.panelfm.ui.preview.PreviewMode? =
        withContext(container.dispatchers.io) {
            com.u707t.panelfm.ui.preview.PreviewMode.ofHandler(
                runCatching { container.previewPrefDao.get(item.extension) }.getOrNull(),
            )
        }

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

    /** 把压缩包挂载成只读 VFS 并在当前窗格进入（MT 的「进入压缩包」体验）。远程包先下载：可见进度、可取消（审计 U4）。 */
    fun openArchiveInPane(side: PaneSide, item: FileMetadata) {
        launchBusy("打开压缩包 ${item.name}") { report ->
            val archive = container.openArchive(item.uri) { done, total ->
                report.report(done, total, "下载中 " + Fmt.transferred(done, total))
            }
            val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(item.uri, archive.kind)
            open(side, inner, connectionId = null, label = "${item.name} · ${archive.kind.label}")
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
        /** 显式目标（长按菜单按「这一项」压缩时传入）；null = 当前选择集 / 当前目录 */
        overrideSources: List<VfsUri>? = null,
    ) {
        val st = _state.value
        val dstPane = st.pane(side.other)
        // F7：显式目标必须端到端生效 —— 长按「这一项压缩」时不得退化成选择集 / 整个目录
        val plan = planCompressTargets(overrideSources, targetSources(side), fileName, format.ext)
        if (plan == null) {
            showStatus("当前目录没有可压缩的项")
            return
        }
        val sources = plan.sources
        val name = plan.name
        val dest = dstPane.uri.child(name)
        update { it.copy(highlight = true, status = "压缩 ${sources.size} 项 → ${dest.displayPath}") }
        container.scope.launch {
            kotlinx.coroutines.delay(1200)
            update { it.copy(highlight = false) }
        }
        launchBusy("压缩 ${sources.size} 项 → $name") { report ->
            // 目标是否是「本次新建」：取消时据此决定是否清理半成品，绝不碰用户原有文件
            val existedBefore = runCatching { container.locator.find(dest)?.stat(dest) != null }.getOrDefault(false)
            try {
                com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
                    .compress(
                        sources, dest, format,
                        onProgress = { done, total ->
                            report.report(done, total, "已处理 " + Fmt.transferred(done, total))
                        },
                        level = level,
                        password = password,
                        encryptNames = encryptNames,
                    )
            } catch (e: CancellationException) {
                // 取消后目标只剩半成品：仅当目标是「本次新建」时才清理，绝不碰用户原有文件
                if (!existedBefore) runCatching { container.locator.find(dest)?.delete(listOf(dest)) }
                throw e
            }
            showStatus("已压缩为 $name")
            load(side.other)
        }
    }

    fun back(side: PaneSide) {
        val tab = pane(side).tab
        val prev = tab.back.lastOrNull() ?: return
        flushScroll(side)
        val newTab = tab.copy(back = tab.back.dropLast(1), forward = (tab.forward + tab.uri).takeLast(HISTORY_LIMIT), uri = prev)
        updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }).withSelectionCleared() }
        load(side)
    }

    fun forward(side: PaneSide) {
        val tab = pane(side).tab
        val next = tab.forward.lastOrNull() ?: return
        flushScroll(side)
        val newTab = tab.copy(forward = tab.forward.dropLast(1), back = (tab.back + tab.uri).takeLast(HISTORY_LIMIT), uri = next)
        updatePane(side) { it.copy(tabs = it.tabs.toMutableList().also { list -> list[it.activeTab] = newTab }).withSelectionCleared() }
        load(side)
    }

    fun up(side: PaneSide) {
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

    fun newTab(side: PaneSide) {
        val pane = pane(side)
        val tab = pane.tab
        val tabs = pane.tabs + PaneTab(uri = tab.uri, connectionId = tab.connectionId, label = tab.label)
        updatePane(side) { it.copy(tabs = tabs, activeTab = tabs.lastIndex).withSelectionCleared() }
    }

    fun switchTab(side: PaneSide, index: Int) {
        val pane = pane(side)
        if (index !in pane.tabs.indices || index == pane.activeTab) return
        updatePane(side) { it.copy(activeTab = index).withSelectionCleared() }
        load(side)
    }

    fun closeTab(side: PaneSide, index: Int) {
        val pane = pane(side)
        if (pane.tabs.size <= 1) return
        val tabs = pane.tabs.toMutableList().also { it.removeAt(index) }
        val newActive = (pane.activeTab.coerceAtMost(tabs.lastIndex)).let { if (index <= pane.activeTab) (it - 1).coerceAtLeast(0) else it }
        updatePane(side) { it.copy(tabs = tabs, activeTab = newActive).withSelectionCleared() }
        load(side)
    }

    // ------------------------------------------------------------------ 选择 / 多选
    //
    // 语义全部收敛在 core.common.MtSelection（纯逻辑 + 单测），这里只负责「把当前列表的 key
    // 喂进去 / 把结果写回状态」。四类入口：
    //   1. 滑动  swipeSelect（MT 0x7f1106f3「左右滑动文件可直接选择」/ 0x7f11062f「滑动选择两个文件连选」）
    //   2. 长按  **不动选择**（只弹该项二级菜单，见 PaneView.handleRowLongPress）
    //   3. 点击  tapSelect / toggleSelection（MT 0x7f110630；点击会清掉滑动锚点）
    //   4. 底栏  全选 / 反选 / 类选（MT 0x7f11062b/632/633）

    /** 当前列表的全部 key（键的闭区间运算都基于它） */
    private fun keysOf(pane: PaneState): List<String> = pane.items.map { it.uri.toString() }

    /** 多选态单击 = 切换单项；同时清掉滑动锚点（点击是「加/减选」，不该让它变成之后的连选端点） */
    fun toggleSelection(side: PaneSide, uri: VfsUri) {
        updatePane(side) { pane ->
            pane.copy(
                selection = MtSelection.toggle(pane.selection, uri.toString()),
                selectionAnchor = null,
            )
        }
    }

    fun selectAll(side: PaneSide) = updatePane(side) { pane ->
        pane.copy(selection = MtSelection.all(keysOf(pane)), selectionAnchor = null)
    }

    /** MT 的「反选」 */
    fun invertSelection(side: PaneSide) = updatePane(side) { pane ->
        pane.copy(selection = MtSelection.invert(pane.selection, keysOf(pane)), selectionAnchor = null)
    }

    /** MT 的「类选」：与当前选中项同类型（同扩展名分类）的全部选中 */
    fun selectSameType(side: PaneSide) = updatePane(side) { pane ->
        val sample = pane.selectedItems.firstOrNull() ?: return@updatePane pane
        val kind = if (sample.isDirectory) "dir" else com.u707t.panelfm.core.common.MimeTypes.kindOf(sample.extension).name
        val same = pane.items.filter {
            if (kind == "dir") it.isDirectory
            else !it.isDirectory && com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kind
        }.map { it.uri.toString() }.toSet()
        pane.copy(selection = pane.selection + same, selectionAnchor = null)
    }

    // ---- 滑动选中（MT `0x7f1106f3` 左右滑动文件可直接选择 / `0x7f11062f` 滑动选择两个文件连选）

    /**
     * 左右滑动一项 = 选中它；**再滑动另一项** = 两项之间的闭区间全部追加进选择。
     *
     * 语义在 [MtSelection.swipe]（纯逻辑 + 单测）：
     *  - 没有滑动锚点（或锚点就是这一项）→ 只选它，锚点 = 它；
     *  - 有锚点且是另一项 → 闭区间追加，锚点挪到这一项（第三次滑动可继续延伸）。
     *
     * 滑动是**离散动作**（用户实机确认的 MT 行为）：没有「按住一路刷」的跟手扫选。
     */
    fun swipeSelect(side: PaneSide, item: FileMetadata) {
        focus(side)
        updatePane(side) { pane ->
            if (pane.items.none { it.uri == item.uri }) return@updatePane pane
            val key = item.uri.toString()
            val (selection, anchor) = MtSelection.swipe(pane.selection, keysOf(pane), key, pane.selectionAnchor)
            if (selection == pane.selection && anchor == pane.selectionAnchor) pane
            else pane.copy(selection = selection, selectionAnchor = anchor)
        }
    }

    fun clearSelection(side: PaneSide) {
        updatePane(side) { it.withSelectionCleared() }
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

    /** 书签拖动排序（MT 0x7f110140「长按后拖动排序」） */
    fun reorderBookmarks(orderedIds: List<Long>) {
        runCatching { container.bookmarkDao.reorder(orderedIds) }
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

    /**
     * MT 0x7f110630「开启后点击列表中任意两个项，将会自动选择它们中间所有的项。」
     *
     * 多选态下点击（不是长按）第二项 = 区间选择（追加）；未开启该设置时退回普通的加/减选。
     * 返回 true 表示已按「点击连选」处理（调用方不需要再走 toggle 分支）。
     */
    fun tapSelect(side: PaneSide, item: FileMetadata): Boolean {
        if (!container.settings.value.tapRangeSelect) return false
        val pane = pane(side)
        if (pane.items.none { it.uri == item.uri }) return false
        val key = item.uri.toString()
        val anchor = pane.tapAnchor
        if (anchor != null && anchor != key) {
            // 第二击：从锚点连到这一项，并把锚点挪过来（可继续延伸：点 3 项 = 连选这 3 项之间）
            updatePane(side) {
                it.copy(
                    selection = MtSelection.unionRange(it.selection, keysOf(it), anchor, key),
                    tapAnchor = key,
                    // 点击连选是「点击」这一路：清掉滑动锚点，避免之后一次滑动把区间接到滑动那一路去
                    selectionAnchor = null,
                )
            }
            return true
        }
        updatePane(side) {
            val next = MtSelection.toggle(it.selection, key)
            // 取消选中时不再留锚点：从一项「没被选中」的项开始连选没有意义
            it.copy(
                selection = next,
                tapAnchor = if (next.contains(key)) key else null,
                selectionAnchor = null,
            )
        }
        return true
    }

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

    fun deleteSelected(
        side: PaneSide,
        fastDelete: Boolean = false,
        /** 显式目标（长按菜单按「这一项」删除时传入）；null = 当前选择集 / 当前目录 */
        overrideSources: List<VfsUri>? = null,
    ) {
        val pane = pane(side)
        val sources = overrideSources ?: targetSources(side)
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

    /**
     * 解压**文件列表里的压缩包文件**（MT 长按菜单的「解压到当前目录 / 解压到单独的文件夹」）。
     *
     * 与 [extractTo] 的区别：那个是在**压缩包内部**选中若干条目再解压；
     * 这里是在文件列表里直接对 `.zip` 文件本身解压 —— 整包展开到目标目录。
     *
     * @param ownFolder true = 先在 [destDir] 下建一个与压缩包同名的目录再解压进去
     *                  （MT `0x7f110252`「解压到单独的文件夹」，重名自动加 (1)）
     */
    fun extractArchiveTo(item: FileMetadata, destDir: VfsUri, ownFolder: Boolean = false) {
        val archiveName = item.name.substringBeforeLast('.', item.name)
        val kind = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name)
        if (kind == null) {
            showStatus("不支持的压缩格式：${item.name}")
            return
        }
        // 整段包在 launchBusy 里（有兜底 catch）——这条链路以前有「未挂载 + 未捕获异常 → 直接闪退」
        // 的问题；远程包下载期间状态条可见、可取消（审计 U4）。
        launchBusy("打开压缩包 ${item.name}") { report ->
            try {
                val target = if (ownFolder) {
                    val dir = container.uniqueChild(destDir, archiveName)
                    try {
                        container.locator.find(destDir)?.mkdir(dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showStatus("创建目录失败：${e.message}")
                        return@launchBusy
                    }
                    dir
                } else {
                    destDir
                }
                // ⚠️ 必须先**挂载**压缩包：引擎按 URI 找会话（SessionLocator 对 archive:// 只查已挂载的
                // `archiveOf`），旧实现直接构造 archive:// 根 URI 就入队 → 计划阶段报
                // 「源位置不可用」/在某些路径上直接抛异常（用户报的「解压闪退/报错」）。
                val vfs = try {
                    container.openArchive(item.uri) { done, total ->
                        report.report(done, total, "下载中 " + Fmt.transferred(done, total))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showStatus("打开压缩包失败：${e.message ?: "未知错误"}")
                    return@launchBusy
                }
                if (vfs.kind != kind) {
                    // 挂载出来的类型与后缀推断不一致（少见：改名 / 伪装），以实际挂载结果为准继续
                    Logx.w("Browser", "extract: kind mismatch ${item.name} ${vfs.kind} != $kind")
                }
                // 压缩包挂载的**根 URI**（整包内容都在它下面）
                val root = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(item.uri, vfs.kind)
                update { it.copy(status = "解压 ${item.name} → ${target.displayPath}") }
                container.engine.enqueue(
                    TransferRequest(
                        sources = listOf(root),
                        destDir = target,
                        op = TransferOp.COPY,
                        conflict = ConflictPolicy.ASK,
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logx.e("Browser", "extract failed ${item.name}: ${e.message}", e)
                showStatus("解压失败：${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** 「解压到文件夹…」：进入选择目录模式，确认后解压该压缩包（供 [extractArchiveTo] 用） */
    fun startPickArchiveExtract(item: FileMetadata) {
        pendingArchiveExtract = item
        startPickDir(PickDirPurpose.EXTRACT)
    }

    /** 清除待解压的压缩包（选择目录流程结束后调用，避免串到下一次解压） */
    fun clearPendingArchiveExtract() {
        pendingArchiveExtract = null
    }

    /** 待解压的压缩包（配合「解压到文件夹…」的选择目录模式） */
    var pendingArchiveExtract: FileMetadata? = null
        private set

    /** 压缩包完整性测试（ZIP：逐条读取校验 CRC）—— 状态条显示进度、可取消（审计 U4） */
    fun testArchive(side: PaneSide) {
        val pane = pane(side)
        launchBusy("测试压缩包完整性") { report ->
            val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path)
            val host = encoded?.let { runCatching { VfsUri.parse(VfsUri.decodeHost(it)) }.getOrNull() }
            if (host == null) {
                showStatus("当前不在压缩包内")
                return@launchBusy
            }
            report.note("正在列出压缩包内容…")
            val vfs = container.openArchive(host)
            val all = withContext(container.dispatchers.vfs) {
                listRecursive(vfs, com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(host, vfs.kind))
            }
            val files = all.filter { !it.isDirectory }
            val totalBytes = files.sumOf { it.size.coerceAtLeast(0) }
            var doneBytes = 0L
            var ok = 0
            var bad = 0
            files.forEachIndexed { index, item ->
                val label = "第 ${index + 1}/${files.size} 项 · " + Fmt.transferred(doneBytes, totalBytes)
                report.report(doneBytes, totalBytes, label)
                try {
                    val reader = vfs.openRead(item.uri)
                    val buf = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val n = reader.read(buf, 0, buf.size)
                            if (n < 0) break
                            doneBytes += n.coerceAtLeast(0)
                            // 逐块上报：单条大文件中途也能取消
                            report.report(doneBytes, totalBytes, label)
                        }
                    } finally {
                        runCatching { reader.close() }
                    }
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    bad++
                }
            }
            showStatus(if (bad == 0) "压缩包完整性检查通过（$ok 个文件）" else "压缩包有 $bad 个文件损坏（共 $ok 正常）")
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
     *
     * **MT 的三条反馈文案（0x7f110430 / 0x7f110620 / 0x7f110686）**：
     *  - 到达 [confirmEvery] 条时回调 [onAskContinue]：返回 false = 停止（"搜索结果数量过多，已停止搜索"）
     *  - [isCancelled] 每轮检查一次 → 用户点「停止搜索」后立即中断
     *  - 搜索会实时把中间结果交给 [onPartial]（界面可边搜边显示，与 MT 的「搜索结果(%d)」一致）
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
        /** 达到该条数时询问是否继续（返回 false = 停止）；null = 不询问 */
        confirmEvery: Int? = null,
        onAskContinue: (suspend (Int) -> Boolean)? = null,
        isCancelled: () -> Boolean = { false },
        onPartial: ((List<FileMetadata>) -> Unit)? = null,
    ): SearchOutcome {
        val root = pane(side).uri
        val vfs = container.locator.find(root) ?: throw VfsException.Unsupported("会话不可用")
        val out = ArrayList<FileMetadata>()
        var scanned = 0
        var stopped = false
        var askedAt = 0

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
                        var emptyReads = 0
                        while (total < 512 * 1024) {
                            val n = reader.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) {
                                if (++emptyReads >= 3) break
                                continue
                            }
                            emptyReads = 0
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
            if (out.size >= limit || scanned >= scanCap || depth > 12 || stopped) return
            if (isCancelled()) { stopped = true; return }
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
                    // MT 0x7f110430「已搜索到 %s 个结果，你确定继续搜索？」：到阈值先问再继续
                    if (confirmEvery != null && onAskContinue != null &&
                        out.size >= confirmEvery && out.size > askedAt
                    ) {
                        askedAt = out.size
                        onPartial?.invoke(out.toList())
                        val go = runCatching { onAskContinue(out.size) }.getOrDefault(false)
                        if (!go) { stopped = true; return }
                    }
                    // 边搜边显示（MT 的「搜索结果(%d)」实时更新）
                    if (out.size % 20 == 0) onPartial?.invoke(out.toList())
                }
            }
            if (recursive) {
                for (item in items) {
                    if (out.size >= limit || scanned >= scanCap || stopped) return
                    if (item.isDirectory && !item.isHidden) walk(item.uri, depth + 1)
                }
            }
        }
        walk(root, 0)
        onPartial?.invoke(out.toList())
        return SearchOutcome(
            items = out,
            stopped = stopped || scanned >= scanCap,
            scanned = scanned,
        )
    }

    /** 跳到该项所在目录并选中它（MT：搜索结果点击 = 定位到文件） */
    fun reveal(side: PaneSide, uri: VfsUri) {
        val parent = uri.parent ?: return
        val tab = pane(side).tab
        open(side, parent, tab.connectionId, tab.label)
        // 选中 + 滚动到该项（MT：搜索结果点进去直接定位，不靠用户自己找）
        updatePane(side) { it.copy(selection = setOf(uri.toString()), scrollToUri = uri.toString()) }
    }

    /** PaneView 消费完滚动请求后清空（避免重复滚动） */
    fun consumeScrollTo(side: PaneSide) = updatePane(side) { if (it.scrollToUri == null) it else it.copy(scrollToUri = null) }

    // ------------------------------------------------------------------ 列表滚动位置记忆
    //  复刻 MT：进入子文件夹再返回上一级时，列表停在刚才的位置（原地不动），
    //  而不是跳回顶部重新加载。实现 = 每个窗格一张「目录 → (首个可见项, 像素偏移)」表。

    /**
     * 界面注册的「立刻保存滚动位置」回调（由 PaneView 在进入组合时注册）。
     *
     * 为什么要这个：底栏的 ← → ↑、返回键、⋮ 菜单里的跳转都**直接调控制器**，
     * 不经过 PaneView 的点击处理，控制器这边需要一条通路在切目录前把位置落盘。
     */
    private val scrollSavers = mutableMapOf<PaneSide, () -> Unit>()

    /** PaneView 进入组合时注册（DisposableEffect 里注销） */
    fun registerScrollSaver(side: PaneSide, saver: () -> Unit) {
        scrollSavers[side] = saver
    }

    fun unregisterScrollSaver(side: PaneSide) {
        scrollSavers.remove(side)
    }

    /** 切目录前把所有窗格的滚动位置落盘（导航方法统一调用） */
    private fun flushScroll(side: PaneSide) {
        runCatching { scrollSavers[side]?.invoke() }
    }

    /** 滚动位置的 key：用「去连接参数的 uri 字符串」，避免 query 抖动、并隔离不同连接 */
    private fun scrollKey(uri: VfsUri): String = VfsUris.stripped(uri).toString()

    /**
     * 界面在列表滚动 / 离开目录时调用，记下该目录的滚动位置。
     *
     * @param listIndex 含 `..` 行的列表下标（调用方用 [ScrollMemory.toListIndex] 换算）
     */
    fun rememberScroll(side: PaneSide, uri: VfsUri, listIndex: Int, offset: Int) {
        scrollMemory[side]?.remember(scrollKey(uri), listIndex, offset)
    }

    /** 取某个目录上次的滚动位置；没有则 null（= 停在顶部） */
    fun recallScroll(side: PaneSide, uri: VfsUri): com.u707t.panelfm.core.common.ScrollMemory.Entry? =
        scrollMemory[side]?.recall(scrollKey(uri))

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

    fun copyToOther(
        side: PaneSide = _state.value.focused,
        /** 显式目标（长按菜单按「这一项」复制时传入）；null = 当前选择集 / 当前目录 */
        overrideSources: List<VfsUri>? = null,
    ) = startCrossPane(side, TransferOp.COPY, overrideSources = overrideSources)

    /**
     * MT「选择当前目录」模式的落地动作：把活动窗格的选中项复制到指定目录（[startCrossPane] 的
     * `overrideDest` 通道，与「复制到对面」共用同一条经过验证的链路）。
     */
    fun copyTo(side: PaneSide, dest: VfsUri) = startCrossPane(side, TransferOp.COPY, overrideDest = dest)

    /** MT「选择当前目录」模式的落地动作：移动到指定目录 */
    fun moveTo(side: PaneSide, dest: VfsUri) = startCrossPane(side, TransferOp.MOVE, overrideDest = dest)

    fun moveToOther(
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
        // F14：旧实现在对面**没有选中项**时静默改成「对面整个目录」，与菜单文案「添加对面选中项」不符。
        // 现在语义与文案一致：没有选中项就提示先选（要整目录请先「全选」）。
        val sources = other.selectedItems.map { it.uri }
        if (sources.isEmpty()) {
            showStatus(
                if (other.items.isEmpty()) "对面窗格为空"
                else "请先在对面窗格选中要添加的项（把整个目录加进压缩包请先「全选」）"
            )
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

    // ------------------------------------------------------------------ 目录对比（M9）

    fun compareDirectories() {
        val st = _state.value
        launchBusy("对比两个目录") { report ->
            val result = FolderDiffEngine.compare(container, st.left.uri, st.right.uri, report)
            update { it.copy(diff = result) }
            showStatus("对比完成：相同 ${result.identical} / 不同 ${result.different}")
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
     *
     * F15：没有选择时**不再静默把整个目录放进剪贴板**（旧实现经 `targetSources()` 回退），
     * 与菜单文案保持一致 —— 提示用户先选中。
     */
    fun copySelectionToClipboard(side: PaneSide, overrideSources: List<VfsUri>? = null) {
        val pane = _state.value.pane(side)
        val sources = overrideSources ?: if (pane.hasSelection) pane.selectedItems.map { it.uri } else emptyList()
        if (sources.isEmpty()) {
            showStatus("请先选中要复制到剪贴板的项（无选择时不取整个目录）")
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

    fun copyWithinPane(side: PaneSide, destDir: VfsUri, overrideSources: List<VfsUri>? = null) =
        enqueueWithinPane(side, TransferOp.COPY, destDir, overrideSources)

    fun moveWithinPane(side: PaneSide, destDir: VfsUri, overrideSources: List<VfsUri>? = null) =
        enqueueWithinPane(side, TransferOp.MOVE, destDir, overrideSources)

    /**
     * 单窗口复制 / 移动。
     *
     * [overrideSources] = 长按菜单「长按复制/移动 ->」传入的显式目标（F8 修复：旧入口不带目标，
     * `targetSources()` 在没有选择时回退成**整个目录**，会把整个目录搬走）。
     */
    private fun enqueueWithinPane(side: PaneSide, op: TransferOp, destDir: VfsUri, overrideSources: List<VfsUri>? = null) {
        val sources = explicitTargets(overrideSources) { targetSources(side) }
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
        /** 显式目标（长按菜单按「这一项」压缩时传入）；null = 当前选择集 / 当前目录 */
        overrideSources: List<VfsUri>? = null,
    ) {
        val st = _state.value
        val pane = st.pane(side)
        // F7：显式目标必须端到端生效（长按「这一项」压缩时不能退化成选择集/整个目录）
        val plan = planCompressTargets(overrideSources, targetSources(side), fileName, format.ext)
        if (plan == null) {
            showStatus("当前目录没有可压缩的项")
            return
        }
        val sources = plan.sources
        val name = plan.name
        val dest = pane.uri.child(name)
        launchBusy("压缩 ${sources.size} 项 → $name") { report ->
            // 目标是否是「本次新建」：取消时据此决定是否清理半成品，绝不碰用户原有文件
            val existedBefore = runCatching { container.locator.find(dest)?.stat(dest) != null }.getOrDefault(false)
            try {
                com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
                    .compress(
                        sources, dest, format,
                        onProgress = { done, total ->
                            report.report(done, total, "已处理 " + Fmt.transferred(done, total))
                        },
                        level = level,
                        password = password,
                        encryptNames = encryptNames,
                    )
            } catch (e: CancellationException) {
                // 取消后目标只剩半成品：仅当目标是「本次新建」时才清理，绝不碰用户原有文件
                if (!existedBefore) runCatching { container.locator.find(dest)?.delete(listOf(dest)) }
                throw e
            }
            showStatus("已压缩为 $name")
            clearSelection(side)
            load(side)
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

    /** 校验值（MD5/SHA-256）：本地与网络都能算；状态条显示读取进度、可取消（审计 U4） */
    fun checksum(uri: VfsUri, algorithm: String, onResult: (String?) -> Unit) {
        launchBusy("计算 $algorithm") { report ->
            val vfs = container.locator.find(uri)
            if (vfs == null) {
                onResult(null)
                return@launchBusy
            }
            val total = runCatching { withContext(container.dispatchers.vfs) { vfs.stat(uri).size } }.getOrDefault(-1L)
            onResult(
                checksumNow(uri, algorithm, total) { done, effectiveTotal ->
                    report.report(done, effectiveTotal, "已读取 " + Fmt.transferred(done, effectiveTotal))
                }
            )
        }
    }

    /**
     * 校验值的挂起实现（APK 信息页等复用；算法：CRC32 / MD5 / SHA-1 / SHA-256）。
     *
     * @param totalHint 总字节数（-1 = 未知，只报已读量）
     * @param onProgress 按块上报（本地/网络逐块回调；调用方负责节流）
     */
    suspend fun checksumNow(
        uri: VfsUri,
        algorithm: String,
        totalHint: Long = -1L,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): String? {
        val vfs = container.locator.find(uri) ?: return null
        return withContext(container.dispatchers.vfs) {
            val reader = vfs.openRead(uri)
            try {
                // MT 的校验值清单（0x7f030009）：CRC32 / MD5 / SHA1 / SHA256
                // CRC32 不是 MessageDigest，单独走 java.util.zip.CRC32
                val crc = if (algorithm == "CRC32") java.util.zip.CRC32() else null
                val digest = if (crc == null) java.security.MessageDigest.getInstance(algorithm) else null
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) break
                    crc?.update(buf, 0, n)
                    digest?.update(buf, 0, n)
                    done += n.coerceAtLeast(0)
                    onProgress?.invoke(done, totalHint)
                }
                // Locale.ROOT：校验值必须是固定 ASCII 十六进制（本地化数字会让「比对校验值」失去意义）
                crc?.let { "%08x".format(java.util.Locale.ROOT, it.value) }
                    ?: digest!!.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
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

    /** 从列表移除任务（未结束的先取消）：任务页 / 任务条的「移除」。 */
    fun removeTask(id: String) {
        container.engine.findTask(id)?.let { container.engine.remove(it) }
    }

    fun clearFinishedTasks() = container.engine.clearFinished()

    companion object {
        const val HISTORY_LIMIT = 100
    }
}

/** 递归 chmod 的安全上限（防超大目录把界面拖死） */
private const val MAX_CHMOD_ITEMS = 20_000
private const val MAX_CHMOD_DEPTH = 32

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
