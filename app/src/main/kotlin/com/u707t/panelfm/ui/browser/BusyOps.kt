package com.u707t.panelfm.ui.browser

/**
 * 长操作（压缩 / 完整性测试 / 校验值 / 目录对比 / 远程压缩包下载）的进度上报器（审计 U4）。
 *
 * 两个职责：
 * 1. **协作式取消**：[report] / [note] 每次都会检查协程是否已被取消（`ensureActive`），
 *    所以「取消」对按块循环的长任务能在一次循环内生效（归档压缩器、校验、下载循环都已接入）；
 * 2. **节流上报**：进度写 UI 状态最多 ~10Hz，长循环里可以每块都调 [report] 而不抖动。
 *
 * [nowMs] / [throttleMs] 可注入，便于单测用假时钟验证节流。
 */
class BusyReporter(
    private val ensureActive: () -> Unit,
    private val sink: (progress: Float?, detail: String?) -> Unit,
    private val throttleMs: Long = 100L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastEmitAt = Long.MIN_VALUE
    private var lastProgress: Float? = null

    /**
     * 上报进度。[total] <= 0 = 总量未知（进度条转圈，只更新 [detail]）。
     *
     * @param force true = 忽略节流立即上报（阶段切换用，如「读取左侧目录…」）
     */
    fun report(done: Long, total: Long, detail: String? = null, force: Boolean = false) {
        ensureActive()
        val frac = busyFraction(done, total)
        val now = nowMs()
        // 注意：lastEmitAt 初值 Long.MIN_VALUE，`now - it` 会溢出成负数 → 必须先判初值
        if (!force && lastEmitAt != Long.MIN_VALUE && now - lastEmitAt < throttleMs) return
        lastEmitAt = now
        if (frac != null) lastProgress = frac
        sink(frac ?: lastProgress, detail)
    }

    /** 只更新细节行（进度维持此前值）；一定立即上报。 */
    fun note(detail: String?) {
        ensureActive()
        lastEmitAt = nowMs()
        sink(lastProgress, detail)
    }
}

/**
 * 长操作进度条的比例（纯函数，便于单测）：
 * total 未知（<= 0）→ null（不确定进度）；否则 0..1，并在 done 越界时夹紧。
 */
internal fun busyFraction(done: Long, total: Long): Float? {
    if (total <= 0) return null
    if (done <= 0) return 0f
    if (done >= total) return 1f
    return (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}
