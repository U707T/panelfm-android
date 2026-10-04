package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.PanelDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 传输引擎：任务队列 + 并发控制。
 *  - 默认 2 个任务并行（可在设置里改 1–4）
 *  - 任务内部单文件串行；服务端可直连时零中转
 *  - [tasks] 只反映「列表增删」；任务自身的进度 / 冲突 / 完成状态在 [taskEvents] / [snapshots] 里，
 *    UI 必须观察后者，否则进度与冲突对话框永远不会刷新。
 */
class TransferEngine(
    private val planner: FileOperationPlanner,
    private val locator: com.u707t.panelfm.core.vfs.VfsLocator,
    private val resumeStore: ResumeStore,
    private val dispatchers: PanelDispatchers,
    private val scope: CoroutineScope,
    maxConcurrent: Int = 2,
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
        _tasks.value = _tasks.value + task
        queue.trySend(task)
        Logx.i("TransferEngine", "enqueue ${task.title} (${task.id.take(8)})")
        return task
    }

    fun activeTasks(): List<TransferTask> =
        _tasks.value.filter { it.state.value !is TaskState.Done && it.state.value !is TaskState.Cancelled && it.state.value !is TaskState.Failed }

    fun hasRunning(): Boolean = activeTasks().isNotEmpty()

    fun pauseAll() { _tasks.value.forEach { it.pause() } }

    fun resumeAll() { _tasks.value.forEach { it.resume() } }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter {
            val s = it.state.value
            s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
        }
    }

    fun remove(task: TransferTask) {
        task.cancel()
        _tasks.value = _tasks.value - task
    }
}
