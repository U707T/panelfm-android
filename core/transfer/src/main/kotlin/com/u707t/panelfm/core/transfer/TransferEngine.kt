package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.PanelDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 传输引擎：任务队列 + 并发控制。
 *  - 默认 2 个任务并行（可在设置里改 1–4）
 *  - 任务内部单文件串行；服务端可直连时零中转
 *  - [tasks] 只反映「列表增删」；任务自身的进度 / 冲突 / 完成状态在 [taskEvents] / [snapshots] 里，
 *    UI 必须观察后者，否则进度与冲突对话框永远不会刷新。
 *  - 任务结束后自动收场：完成 / 已取消保留 [finishedRetentionMs] 供用户确认结果后自动移除
 *    （「任务条/列表不消失」的修法）；**失败不自动移除** —— 错误必须留痕，由用户手动处理。
 */
class TransferEngine(
    private val planner: FileOperationPlanner,
    private val locator: com.u707t.panelfm.core.vfs.VfsLocator,
    private val resumeStore: ResumeStore,
    private val dispatchers: PanelDispatchers,
    private val scope: CoroutineScope,
    maxConcurrent: Int = 2,
    /** 完成 / 已取消的任务在列表里的保留时长（毫秒）；测试可调小。 */
    private val finishedRetentionMs: Long = DEFAULT_FINISHED_RETENTION_MS,
) {

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks

    /**
     * 任务事件流：任务增删、以及**任一任务的状态变化**（进度 / 等待冲突 / 完成 / 取消…）时都会发射。
     * 注意：不能用 StateFlow（去重会吞掉「同一个任务对象状态变了」的发射），UI 侧只 collect。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val taskEvents: Flow<List<TransferTask>> = _tasks.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(list)
        else combine(list.map { it.state }) { _ -> list }
    }

    /** 任务状态快照流（内容随状态变化，可直接 collectAsState 渲染）。 */
    val snapshots: Flow<List<TransferTaskSnapshot>> =
        taskEvents.map { list -> list.map { it.toSnapshot() } }

    /** 按 id 找任务（UI 操作暂停 / 继续 / 取消 / 冲突处理用） */
    fun findTask(id: String): TransferTask? = _tasks.value.firstOrNull { it.id == id }

    private val queue = Channel<TransferTask>(Channel.UNLIMITED)
    private val workers = java.util.concurrent.atomic.AtomicInteger(0)

    /** 正在运行的任务数（严格按 [maxConcurrent] 限流，支持运行中调整） */
    private val runningCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var maxConcurrent: Int = maxConcurrent.coerceIn(1, 4)

    /**
     * MT「保留文件时间」全局默认：设置页写入，enqueue 时补进 request。
     * 放在引擎上而不是每个调用点，避免漏掉新增的入队路径。
     */
    @Volatile
    var preserveModifiedTime: Boolean = true

    init {
        repeat(this.maxConcurrent) { startWorker() }
    }

    private fun startWorker() {
        workers.incrementAndGet()
        scope.launch {
            for (task in queue) {
                // 并发限流：原子占额度（支持运行中下调；避免 4→1 时旧 worker 仍并发跑 4 个任务）
                while (true) {
                    val cur = runningCount.get()
                    if (cur < maxConcurrent) {
                        if (runningCount.compareAndSet(cur, cur + 1)) break
                    } else {
                        kotlinx.coroutines.delay(25)
                    }
                }
                try {
                    runCatching { task.run() }
                        .onFailure { Logx.e("TransferEngine", "worker crashed: ${it.message}", it) }
                } finally {
                    runningCount.decrementAndGet()
                }
                // 并发下调：多余 worker 在完成手头任务后退出
                if (workers.get() > maxConcurrent) {
                    workers.decrementAndGet()
                    return@launch
                }
            }
        }
    }

    /** 调整并发：通过增减 worker 实现（运行中亦可；下调时多余 worker 空闲后退出） */
    fun updateConcurrency(n: Int) {
        val target = n.coerceIn(1, 4)
        maxConcurrent = target
        while (workers.get() < target) startWorker()
    }

    fun enqueue(request: TransferRequest): TransferTask {
        val task = TransferTask(
            request = if (request.preserveModifiedTime == preserveModifiedTime) request
            else request.copy(preserveModifiedTime = preserveModifiedTime),
            planner = planner,
            locator = locator,
            resumeStore = resumeStore,
        )
        _tasks.update { it + task }
        queue.trySend(task)
        scope.launch { watchAutoDismiss(task) }
        Logx.i("TransferEngine", "enqueue ${task.title} (${task.id.take(8)})")
        return task
    }

    fun pauseAll() { _tasks.value.forEach { it.pause() } }

    fun resumeAll() { _tasks.value.forEach { it.resume() } }

    /** 清空所有已结束的任务（含失败）——用户手动确认过才走这里。 */
    fun clearFinished() {
        _tasks.update { list -> list.filter { !it.state.value.isFinished } }
    }

    /**
     * 从列表移除任务；未结束的先取消（取消的传输会保留 .part 与断点记录，下次可续传）。
     *
     * 列表一律用 [update] 做 CAS 更新：worker、自动收场协程与 UI 线程都可能并发改列表，
     * 旧的 `_tasks.value = _tasks.value ± task` 是读-改-写，会丢更新。
     */
    fun remove(task: TransferTask) {
        task.cancel()
        _tasks.update { it - task }
    }

    /**
     * 任务结束后的自动收场：
     *  - 完成 / 已取消：保留 [finishedRetentionMs] 让用户看到结果，然后自动移除 ——
     *    这是「传输任务 ui 不消失」问题的根治点；
     *  - 失败：**不自动移除**（错误必须留痕），由用户「移除 / 清空」处理。
     * 只移除「仍是同一个终态」的任务：保留期内被手动移除 / 清空过的不会重复处理。
     */
    private suspend fun watchAutoDismiss(task: TransferTask) {
        val finished = task.state.first { it.isFinished }
        if (!finished.dismissesAutomatically) return
        delay(finishedRetentionMs)
        if (task.state.value == finished) {
            _tasks.update { it - task }
            Logx.i("TransferEngine", "auto-dismiss ${task.id.take(8)} (${finished.javaClass.simpleName})")
        }
    }

    companion object {
        /** 完成 / 已取消的默认保留时长：8 秒——够看完一行结果摘要，又不至于常驻。 */
        const val DEFAULT_FINISHED_RETENTION_MS = 8_000L
    }
}
