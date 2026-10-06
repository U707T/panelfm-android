package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 暂停 / 取消闸门：传输循环与目录扫描里每个条目 / chunk 前调用 [checkpoint]。
 *  - 暂停 → 挂起等待（占用协程，不消耗 CPU）
 *  - 取消 → 抛 [VfsException.Cancelled]
 *
 * ⚠️ 取消判定必须**在暂停等待里也生效**：旧实现先 `while (paused)` 再查取消标志，
 * 「先暂停、再取消」（例如暂停后点清空/移除）会永远挂在暂停循环里 —— 任务卡死、
 * 前台服务退不出去。现在取消是第一优先级。
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
        if (cancelled.get()) throw VfsException.Cancelled()
        while (_paused.value) {
            if (cancelled.get()) throw VfsException.Cancelled()
            coroutineContext.ensureActive()
            delay(80)
        }
        // 暂停循环可能正是被 cancel()（会把 paused 置回 false）打断的，退出后必须再查一次，
        // 否则「暂停中取消」会从循环里正常溜出去、任务没有真正停下。
        if (cancelled.get()) throw VfsException.Cancelled()
        coroutineContext.ensureActive()
    }
}
