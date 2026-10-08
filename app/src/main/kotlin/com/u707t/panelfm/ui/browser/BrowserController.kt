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

    /** settings 上一次发射的「文件夹排序规则」快照（检测到变化时补一次重载，DataStore 写入是异步的） */
    private var lastFolderSorts: Map<String, String>? = null

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

    fun showProperties(item: FileMetadata) = update { it.copy(property = item) }

    fun dismissProperties() = update { it.copy(property = null) }

    // ------------------------------------------------------------------ 跨窗格操作

    // ------------------------------------------------------------------ 分隔条 / 路径

    /** 拖动分隔条 */
    fun setSplitRatio(ratio: Float) = update { it.copy(splitRatio = ratio.coerceIn(0.25f, 0.75f)) }

    /** 剪贴板是否为空（UI 决定 FAB 是否显示） */
    val hasClipboard: Boolean get() = clipboard.isNotEmpty()

    /** 应用内剪贴板（源 URI 列表）。放控制器而不是系统剪贴板：能表达「多项 + 移动语义」 */
    @Volatile
    internal var clipboard: List<VfsUri> = emptyList()

    companion object {
        const val HISTORY_LIMIT = 100
    }
}

