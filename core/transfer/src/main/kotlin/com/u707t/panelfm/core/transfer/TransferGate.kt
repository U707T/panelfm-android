package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 暂停信号：`checkpoint()` 发现「用户要暂停」时抛出。
 *
 * 为什么不是「挂在闸门里等」：worker 是在 `run()` 返回后才释放并发位的，挂起等待等于
 * **暂停的任务一直占着传输位** —— 并发 2 时暂停两个任务，后续任务永远「排队中」。
 * 抛出信号 → [TransferTask.run] 收尾并返回 → 槽位释放；恢复时由引擎重新入队。
 * 用独立类型（而不是 CancellationException）是为了不被 `runCatching` / 取消处理误吞。
 */
internal class PauseSignal : Exception("paused")

/**
 * 暂停 / 取消闸门：传输循环与目录扫描里每个条目 / chunk 前调用 [checkpoint]。
 *  - 暂停 → 抛 [PauseSignal]，任务**退出 run() 让出并发位**（v2.2.5）
 *  - 取消 → 抛 [VfsException.Cancelled]
 *
 * ⚠️ 取消是**第一优先级**：暂停中取消也必须是「已取消」，不能停在暂停态。
 * ⚠️ 旧实现是 `while (paused) delay(80)` 原地挂起 —— 协程不占 CPU，但**占着 worker 槽位**，
 *    并发 2 时暂停两个任务就把整个队列锁死（第 4 批审计 🟡3 的「轻量修法」）。
 */
class TransferGate {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private val cancelled = AtomicBoolean(false)

    fun pause() { _paused.value = true }

    fun resume() { _paused.value = false }

    fun cancel() {
        cancelled.set(true)
        _paused.value = false
    }

    val isCancelled: Boolean get() = cancelled.get()

    suspend fun checkpoint() {
        // 取消优先于暂停：暂停中取消必须是「已取消」（旧实现先查暂停、再查取消，
        // 「先暂停再取消」会停在暂停循环里，任务卡死、前台服务退不出去）。
        if (cancelled.get()) throw VfsException.Cancelled()
        if (_paused.value) throw PauseSignal()
        coroutineContext.ensureActive()
    }
}
