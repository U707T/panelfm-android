package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.transfer.TransferRequest
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 双列浏览控制器（应用级单例，转屏/切屏都不丢状态）。
 *  - 两个窗格完全独立：路径、历史栈、标签页、排序、隐藏文件、选择集
 *  - 跨窗格操作：目标恒为另一窗格当前目录（实时取值，不弹目标选择框）
 *  - 线程纪律：状态一律经 [update]（CAS）写入；共享可变字段必须并发安全
 *    （协程跑在 IO 池上，不是单线程；新增字段前先想清楚谁会并发碰它）。
 *  - 文件组织：方法按职责拆到 5 个 extension 文件（Nav / Select / FileOps / Archive / Transfers），
 *    本文件只留状态、启动/持久化、长操作执行器与状态提示；类仍是唯一状态持有者。
 */
class BrowserController(internal val container: AppContainer) {

    internal val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state

    /** 请求打开预览（由 AppRoot 观察后跳转），带「打开方式」模式 */
    internal val _previewRequest = MutableStateFlow<com.u707t.panelfm.ui.preview.PreviewRequest?>(null)
    val previewRequest: StateFlow<com.u707t.panelfm.ui.preview.PreviewRequest?> = _previewRequest

    /**
     * 单击了「没有内置查看器」的文件：请界面弹出该项的二级菜单，
     * 而不是直接丢进文本查看 / 编辑器。由 [DualPaneScreen] 消费。
     */
    internal val _openMenuRequest = MutableStateFlow<com.u707t.panelfm.core.vfs.FileMetadata?>(null)
    val openMenuRequest: StateFlow<com.u707t.panelfm.core.vfs.FileMetadata?> = _openMenuRequest

    /** 文件对比请求（左/右两个文件） */
    internal val _diffRequest = MutableStateFlow<Pair<VfsUri, VfsUri>?>(null)
    val diffRequest: StateFlow<Pair<VfsUri, VfsUri>?> = _diffRequest

    fun dismissDiffRequest() { _diffRequest.value = null }

    /** 每窗格的加载作业（UI 线程发起、IO 协程收尾 → 必须并发安全） */
    internal val loadJobs = java.util.concurrent.ConcurrentHashMap<PaneSide, Job>()

    /** 长操作（审计 U4）：同一时间至多一个；进度状态条 + 取消。 */
    private val busyIdGen = AtomicLong(0)
    /** UI 与作业收尾两侧都会读写 → volatile 保证可见性。 */
    @Volatile
    private var busyJob: Job? = null
    internal val loadGeneration = mutableMapOf(
        PaneSide.LEFT to AtomicLong(0),
        PaneSide.RIGHT to AtomicLong(0),
    )

    /**
     * 每个窗格各自的「目录 → 滚动位置」记忆（复刻 MT：进子目录再返回，列表停在原地）。
     *
     * 为什么放在控制器（而不是 Composable 里 `remember`）：窗格会因单/双列切换、
     * 抽屉开关等原因重组甚至重建，位置必须活到控制器这一层才稳。
     */
    @Volatile
    internal var scrollMemory = mapOf(
        PaneSide.LEFT to com.u707t.panelfm.core.common.ScrollMemory(),
        PaneSide.RIGHT to com.u707t.panelfm.core.common.ScrollMemory(),
    )

    /** Null until the initial paths have actually been read and restoration has settled. */
    @Volatile
    private var startupFallbacks: Pair<String?, String?>? = null
    private val pathSaveLock = Mutex()
    internal val conflictDecisionLock = Mutex()
    internal var answeredConflictToken: Long? = null
    private val persistScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

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
                // 「仅应用于此文件夹」规则变化 → 重载两窗格：DataStore 写入是异步的，
                // applySort 里那次 load 可能读不到新规则，等设置真正生效后补一次。
                if (lastFolderSorts == null) {
                    lastFolderSorts = s.folderSorts
                } else if (lastFolderSorts != s.folderSorts) {
                    lastFolderSorts = s.folderSorts
                    load(PaneSide.LEFT)
                    load(PaneSide.RIGHT)
                }
                if (!startupApplied) {
                    startupApplied = true
                    val defaultSort = SortSpec(s.sortBy, s.sortAscending, s.dirsFirst)
                    // 读回持久化的浏览模式（老数据的 single_column 迁移在 PrefsStore.settings 里完成）
                    val savedMode = runCatching { BrowseMode.valueOf(s.browseMode) }.getOrDefault(BrowseMode.DUAL)
                    update {
                        it.copy(
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
        // 记忆路径异步恢复，不让冷启动的默认列表覆盖尚未读出的历史。
        container.scope.launch {
            val settings = container.prefs.settings.first()
            val (lastLeft, lastRight) = container.prefs.lastPathsSuspend()
            val paths = if (settings.startAtHome) listOf(settings.homePath) else listOf(lastLeft, lastRight)
            // AppContainer's initial connection query runs in parallel with browser startup.
            // Join by reading explicitly before resolving a stored remote/archive location.
            if (paths.any { it != null && !it.startsWith("local://") } && container.connections.value.isEmpty()) {
                try {
                    container.reloadConnections()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logx.w("Browser", "cannot load connections for startup: ${e.message}")
                }
            }
            val leftTab = restoredTab(if (settings.startAtHome) settings.homePath else lastLeft)
            val rightTab = if (settings.startAtHome) leftTab else restoredTab(lastRight)
            update { state ->
                state.copy(
                    left = state.left.restoreStartupTab(leftTab),
                    right = state.right.restoreStartupTab(rightTab),
                )
            }
            startupFallbacks = (lastLeft.takeIf { leftTab == null } to lastRight.takeIf { rightTab == null })
            if (leftTab != null && !pane(PaneSide.LEFT).hasNavigated) load(PaneSide.LEFT)
            if (rightTab != null && !pane(PaneSide.RIGHT).hasNavigated) load(PaneSide.RIGHT)
            schedulePersistPaths()
        }
    }

    /** 确认目录实际存在；仅找到 VFS 对象不能证明目录可访问。 */
    private suspend fun restoredTab(raw: String?): PaneTab? {
        if (raw == null) return null
        return try {
            val uri = restoreReachableUri(VfsUri.parse(raw))
            val vfs = container.locator.find(uri) ?: return null
            if (!withContext(container.dispatchers.vfs) { vfs.stat(uri).isDirectory }) return null
            val label = if (uri.scheme == "local" && uri.authority == "emulated") "内部存储" else uri.authority
            PaneTab(uri, label = label).atHistoricalLocation(uri, container::connectionForUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logx.w("Browser", "startup path unavailable: ${e.message}")
            null
        }
    }

    private suspend fun restoreReachableUri(uri: VfsUri): VfsUri = when (uri.scheme) {
        "local" -> uri
        "archive" -> {
            val encoded = ArchiveVfs.parseEncodedHost(uri.path) ?: error("压缩包路径缺少宿主")
            val host = restoreReachableUri(VfsUri.parse(VfsUri.decodeHost(encoded)))
            container.openArchive(host)
            uri.copy(path = "/" + VfsUri.encodeHost(host.toString()) + "!/" + ArchiveVfs.parseInner(uri.path))
        }
        else -> {
            val config = connectionForStoredPath(uri) ?: error("找不到该连接")
            val stamped = storedPathWithConnection(uri, config)
            if (container.locator.find(stamped) == null) container.openConnection(config)
            stamped
        }
    }

    /** 退出/防抖共用：按窗格保留暂不可达的路径，直至用户真的换了位置。 */
    fun persistPaths() {
        val fallback = startupFallbacks ?: return
        container.scope.launch {
            pathSaveLock.withLock {
                val state = _state.value
                container.prefs.saveLastPaths(
                    state.left.pathToPersist(fallback.first),
                    state.right.pathToPersist(fallback.second),
                )
            }
        }
    }

    /** settings 上一次发射的「文件夹排序规则」快照（检测到变化时补一次重载，DataStore 写入是异步的） */
    private var lastFolderSorts: Map<String, String>? = null

    fun schedulePersistPaths() {
        if (startupFallbacks == null || !persistScheduled.compareAndSet(false, true)) return
        container.scope.launch {
            kotlinx.coroutines.delay(300)
            persistScheduled.set(false)
            persistPaths()
        }
    }

    // ------------------------------------------------------------------ 状态读写

    /**
     * 状态写入。
     *
     * ⚠️ 必须走 [MutableStateFlow.update] 的 CAS 循环：`container.scope` 跑在多线程 IO 池上，
     * UI 回调（主线程）与异步协程（IO）都会并发走到这里；手写 `value = transform(value)`
     * 是「非原子读-改-写」，并发时会丢更新（选中/状态条目会偶发地"消失"）。
     */
    internal fun update(transform: (BrowserUiState) -> BrowserUiState) {
        _state.update(transform)
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
     * 必须持久化：旧实现只改内存状态，重启就回落到默认值，
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
        _statusQueue.update { it.drop(1) }
    }

    fun dismissPreviewRequest() { _previewRequest.value = null }

    fun consumeOpenMenuRequest() { _openMenuRequest.value = null }

    fun showStatus(message: String) {
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
    internal fun launchBusy(title: String, block: suspend (BusyReporter) -> Unit) {
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

    /** 待解压的压缩包（配合「解压到文件夹…」的选择目录模式） */
    var pendingArchiveExtract: FileMetadata? = null
        internal set

    // ------------------------------------------------------------------ 压缩包口令（第 5 批 🔴1）

    /** 正在等待「输入压缩包口令」的协程（null = 没有在等） */
    private var archivePasswordWaiter: kotlinx.coroutines.CompletableDeferred<String?>? = null

    /**
     * 弹出「输入压缩包口令」对话框并**挂起**等待结果（与冲突对话框同一模式：
     * UI 的选择回传给正在等待的挂载协程）；返回 null = 用户取消。
     */
    internal suspend fun promptArchivePassword(name: String, wrong: Boolean = false): String? {
        val waiter = kotlinx.coroutines.CompletableDeferred<String?>()
        archivePasswordWaiter = waiter
        update { it.copy(archivePassword = ArchivePasswordAsk(name, wrong)) }
        return try {
            waiter.await()
        } finally {
            if (archivePasswordWaiter === waiter) archivePasswordWaiter = null
            update { it.copy(archivePassword = null) }
        }
    }

    /** 提交口令（UI 调用）；取消 = 传 null。 */
    fun submitArchivePassword(password: String?) {
        archivePasswordWaiter?.complete(password)
    }

    // ------------------------------------------------------------------ 列表滚动位置记忆
    //  复刻 MT：进入子文件夹再返回上一级时，列表停在刚才的位置（原地不动），
    //  而不是跳回顶部重新加载。实现 = 每个窗格一张「目录 → (首个可见项, 像素偏移)」表。

    /**
     * 界面注册的「立刻保存滚动位置」回调（由 PaneView 在进入组合时注册）。
     *
     * 为什么要这个：底栏的 ← → ↑、返回键、⋮ 菜单里的跳转都**直接调控制器**，
     * 不经过 PaneView 的点击处理，控制器这边需要一条通路在切目录前把位置落盘。
     */
    internal val scrollSavers = java.util.concurrent.ConcurrentHashMap<PaneSide, () -> Unit>()

    fun showProperties(item: FileMetadata) = update { it.copy(property = item, propertyMulti = null) }

    /** 多选属性：只汇总列表里已有的 metadata，不再逐项 stat（网络目录不能因此发请求） */
    fun showProperties(items: List<FileMetadata>) {
        if (items.size <= 1) {
            items.firstOrNull()?.let { showProperties(it) }
            return
        }
        update { it.copy(property = null, propertyMulti = items) }
    }

    fun dismissProperties() = update { it.copy(property = null, propertyMulti = null) }

    /**
     * 打开「APK 信息」对话框（单击 apk / 分包安装包，或长按菜单里的「APK 信息」）。
     *
     * 走 [BrowserUiState.apkInfo] 而不是 `_previewRequest`：这张卡片是**弹层**，
     * 不占路由栈 —— 关掉就回到原目录，不该在返回键历史里留一页。
     */
    fun showApkInfo(item: FileMetadata) = update { it.copy(apkInfo = item) }

    fun dismissApkInfo() = update { it.copy(apkInfo = null) }

    // ------------------------------------------------------------------ 空间预检队列

    /**
     * 待确认的「空间不足」请求队列。
     *
     * 不能只有一个槽位：两次「空间不足」的入队在 100 ms 内先后发生时，后一个会把前一个
     * 静默覆盖掉 —— 那笔传输从此不会发生，用户也看不到任何提示。这里保证「先到先问」。
     */
    private val spaceQueueLock = Any()
    private val spaceQueue = ArrayDeque<SpaceWarning>()

    /** 预检 + 入队串行化：否则两次操作可能倒序入队（先移 A 后移 B，实际 B 先跑） */
    internal val spaceGate = kotlinx.coroutines.sync.Mutex()

    internal fun pushSpaceWarning(warning: SpaceWarning) = synchronized(spaceQueueLock) {
        spaceQueue.addLast(warning)
        update { it.copy(spaceWarning = spaceQueue.firstOrNull()) }
    }

    /** 弹出队首（确认 / 取消都走这里），返回被弹出的请求 */
    internal fun popSpaceWarning(): SpaceWarning? = synchronized(spaceQueueLock) {
        val popped = spaceQueue.removeFirstOrNull()
        update { it.copy(spaceWarning = spaceQueue.firstOrNull()) }
        popped
    }

    // ------------------------------------------------------------------ 跨窗格操作

    // ------------------------------------------------------------------ 路径

    /**
     * 应用内剪贴板（源 URI 列表）。放控制器而不是系统剪贴板：能表达「多项 + 移动语义」。
     *
     * ⚠️ [BrowserClipboard] 通过 StateFlow 发布：界面用它决定
     * 「粘贴 FAB 是否出现」「⋮ 菜单里有没有粘贴项」。旧实现是普通字段，读它不产生订阅 ——
     * 复制到剪贴板后 FAB 不会出现，必须切目录 / 触发别的重组才「突然冒出来」；
     * 移动粘贴清空剪贴板后 FAB 也不消失。
     */
    internal val fileClipboard = BrowserClipboard()
    val clipboard: StateFlow<List<VfsUri>> = fileClipboard.sources

    internal fun setClipboard(sources: List<VfsUri>) = fileClipboard.set(sources)

    /** 剪贴板是否为空（UI 决定 FAB 是否显示 / 菜单是否给粘贴项） */
    val hasClipboard: Boolean get() = clipboard.value.isNotEmpty()

    companion object {
        const val HISTORY_LIMIT = 100
    }
}

