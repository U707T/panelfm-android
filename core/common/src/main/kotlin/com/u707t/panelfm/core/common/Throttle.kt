package com.u707t.panelfm.core.common

/** 进度节流：UI 只关心 ~5Hz 的更新，避免高频重组与数据库写入。 */
class Throttle(private val intervalMs: Long = 200L) {
    private var last = 0L

    /** 返回 true 表示本次应当上报 */
    fun shouldReport(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - last >= intervalMs) {
            last = nowMs
            return true
        }
        return false
    }

    fun reset() { last = 0L }
}
