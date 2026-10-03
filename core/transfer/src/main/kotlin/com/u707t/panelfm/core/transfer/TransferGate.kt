package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 暂停 / 取消闸门：传输循环里每个 chunk 前调用 [checkpoint]。
 *  - 暂停 → 挂起等待（占用协程，不消耗 CPU）
 *  - 取消 → 抛 [VfsException.Cancelled]
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
        while (_paused.value) {
            coroutineContext.ensureActive()
            delay(80)
        }
        if (cancelled.get()) throw VfsException.Cancelled()
        coroutineContext.ensureActive()
    }
}
