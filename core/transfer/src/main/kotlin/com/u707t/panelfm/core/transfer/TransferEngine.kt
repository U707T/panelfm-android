package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.PanelDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 传输引擎：任务队列 + 并发控制。
 *  - 默认 2 个任务并行（可在设置里改 1–4）
 *  - 任务内部单文件串行；服务端可直连时零中转
 *  - 所有任务状态都在 [tasks] 里，UI 只读不写
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

    private val queue = Channel<TransferTask>(Channel.UNLIMITED)
    private var workers = 0
    var maxConcurrent: Int = maxConcurrent.coerceIn(1, 4)
        private set

    init {
        repeat(this.maxConcurrent) { startWorker() }
    }

    private fun startWorker() {
        workers++
        scope.launch {
            for (task in queue) {
                runCatching { task.run() }
                    .onFailure { Logx.e("TransferEngine", "worker crashed: ${it.message}", it) }
            }
        }
    }

    /** 调整并发：通过增减 worker 实现（运行中亦可） */
    fun updateConcurrency(n: Int) {
        val target = n.coerceIn(1, 4)
        while (workers < target) startWorker()
        maxConcurrent = target
    }

    fun enqueue(request: TransferRequest): TransferTask {
        val task = TransferTask(
            request = request,
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
