package com.u707t.panelfm.ui.preview

import androidx.media3.common.Player

/**
 * 播放状态机策略（**纯函数**，`MediaPlaybackPolicyTest` 直接锁行为）。
 *
 * ## 为什么单独抽出来
 *
 * 「视频点开不播、但缓冲条在涨、拖动进度条还能看到画面」这一组现象，
 * 在本项目的代码里只有一条成因链：**播放器的「意图」（`playWhenReady`）与「实况」
 * （`isPlaying`）被混成了一个 `isPlaying`**。于是下面三种状态在界面上长得一模一样：
 *
 * | 真实状态 | `playWhenReady` | `isPlaying` | 界面（旧实现） |
 * |---|---|---|---|
 * | 正在播 | true | true | 暂停键 ✓ |
 * | 缓冲中（还没出帧） | true | false | 播放键；点它 `play()` 是空操作 |
 * | 被音频焦点/输出设备抑制 | true | false | 播放键；点它 `play()` 仍是空操作 ✗ |
 * | 用户手动暂停 | false | false | 播放键 ✓ |
 *
 * 后三种里只有最后一种能靠播放键救回来 —— 前两种就是用户说的「无法播放」。
 * 这里把判定收成纯函数，UI 只负责按计划执行，行为可测。
 */
internal object MediaPlaybackPolicy {

    /**
     * 「有播放意图却迟迟没有进展」的判定阈值。
     *
     * 低于 8 秒会误伤正常的网络起播（SMB / WebDAV 首帧常见 2–4 秒）；
     * 高于 15 秒用户已经以为卡死并退出了。
     */
    const val StallTimeoutMs = 8_000L

    /** 播放键的计划：先执行什么、最后把 `playWhenReady` 置成什么。 */
    internal data class TogglePlan(
        /** 目标 `playWhenReady` */
        val play: Boolean,
        /** 播放器还在 `STATE_IDLE`（首次 / 出错后）→ 必须先 `prepare()`，否则 `play()` 不生效 */
        val prepareFirst: Boolean,
        /**
         * 处于**播放抑制**（`playbackSuppressionReason != NONE`）：
         * 此时 `playWhenReady` 已经是 true，直接 `play()` 是空操作，
         * 必须 `pause()` → `play()` 重新申请一次音频焦点才能恢复。
         */
        val reacquireFocus: Boolean,
        /** `STATE_ENDED`：从头再放一遍（而不是"保持结束态"） */
        val restartFromStart: Boolean,
        /** 非 null = 先 seek 到该位置再播（卡死恢复用：原地重新发起装载） */
        val reseekToMs: Long? = null,
    )

    /**
     * 播放键语义：**按「意图」而不是「实况」切换**。
     *
     * - 抑制中（音频焦点被别的 App 占住 / 输出设备不可用）→ 当作「没在播」，
     *   但要用 pause→play 重新申请焦点（直接 play() 无效）；
     * - `STATE_IDLE` → 先 prepare（首次装载前点播放、或出错重试后的第一次点击）；
     * - `STATE_ENDED` → 从 0 重新开始；
     * - 其余 → 取反 `playWhenReady`。
     */
    fun planToggle(
        playWhenReady: Boolean,
        playbackState: Int,
        suppressionReason: Int,
    ): TogglePlan {
        if (suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            return TogglePlan(play = true, prepareFirst = false, reacquireFocus = true, restartFromStart = false)
        }
        if (playbackState == Player.STATE_IDLE) {
            return TogglePlan(play = true, prepareFirst = true, reacquireFocus = false, restartFromStart = false)
        }
        if (!playWhenReady && playbackState == Player.STATE_ENDED) {
            return TogglePlan(play = true, prepareFirst = false, reacquireFocus = false, restartFromStart = true)
        }
        return TogglePlan(
            play = !playWhenReady,
            prepareFirst = false,
            reacquireFocus = false,
            restartFromStart = false,
        )
    }

    /**
     * 卡死时「继续播放」的真实动作（**不是**播放键的取反）。
     *
     * 卡死态的 `playWhenReady` 本来就是 true（用户没暂停），走 [planToggle] 会得到
     * "pause" —— 于是「继续播放」反而停住，按钮名与行为对不上。
     * 正确做法是**在当前位置重新发起一次装载**：`seekTo(currentPosition)` 会让
     * Media3 丢掉旧的 SampleQueue 重新读，是卡在缓冲 / 网络抖动后最有效的自救。
     */
    fun planStallRecovery(currentPositionMs: Long): TogglePlan =
        TogglePlan(
            play = true,
            prepareFirst = false,
            reacquireFocus = false,
            restartFromStart = false,
            reseekToMs = currentPositionMs.coerceAtLeast(0),
        )

    /**
     * 播放键图标该显示「暂停」还是「播放」。
     *
     * **必须跟着播放意图走**，不能跟 `isPlaying`：缓冲中与被抑制时 `isPlaying == false`，
     * 按 `isPlaying` 会显示成「播放」，而这时按下去（旧实现 `play()`）没有任何效果 ——
     * 这就是「点了播放没反应」的直接来源。
     *
     * 两个例外：
     *  - `STATE_ENDED` 恒显示「播放」（此时按下是重播，不是暂停）；
     *  - `playWhenReady=false`（用户主动暂停，哪怕仍在后台缓冲）也显示「播放」。
     */
    fun showPauseIcon(playWhenReady: Boolean, playbackState: Int, isPlaying: Boolean): Boolean {
        if (playbackState == Player.STATE_ENDED) return false
        return isPlaying || playWhenReady
    }

    /**
     * 进度/缓冲是否该刷新（500ms 轮询的开关）。
     *
     * 旧实现只看 `isPlaying`，于是**缓冲中与被抑制时两条进度都不动** ——
     * 恰恰是「看起来卡死」的两个状态。现在只要还有播放意图（或还在装载）就刷新，
     * 用户能看见缓冲条在涨，也就不会误判成「播不动」。
     */
    fun shouldRefreshProgress(playWhenReady: Boolean, playbackState: Int, isPlaying: Boolean): Boolean =
        isPlaying || playWhenReady || playbackState == Player.STATE_BUFFERING

    /**
     * 卡死判定：有播放意图、没在出帧，且**位置与缓冲都没前进**超过 [stallMs]。
     *
     * 只看「没在播」不够：正常起播的缓冲期也满足，必须叠加「无进展时长」。
     */
    fun isStalled(playWhenReady: Boolean, isPlaying: Boolean, stallMs: Long): Boolean =
        playWhenReady && !isPlaying && stallMs >= StallTimeoutMs

    /**
     * 播放抑制 / 异常态的可执行文案；`NONE` 返回 null（不需要提示）。
     *
     * 这些状态原来**完全没有 UI**：用户只看到"点了播放没反应"，没有任何线索。
     */
    fun describeSuppression(reason: Int): String? = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> null
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
            "音频被其他应用占用（已暂停），点「继续播放」重试"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE ->
            "当前音频输出不可用（耳机/蓝牙断开或未就绪）"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT ->
            "音频输出被系统抑制（静音或输出设备异常）"
        Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING ->
            "拖动预览中"
        else -> "播放被系统暂停，点「继续播放」重试"
    }

    /**
     * 可播放余量百分比（0–100）：`(buffered - position) / duration`。
     *
     * 注意语义是**播放头之后还有多少可播**（不是"整条缓冲占总长多少"）——
     * 卡死提示里要回答的问题正是"我还能往前看多久"。总长未知时返回 null。
     */
    fun aheadPercent(positionMs: Long, bufferedMs: Long, durationMs: Long): Int? {
        if (durationMs <= 0) return null
        val ahead = (bufferedMs - positionMs).coerceAtLeast(0)
        return ((ahead.toDouble() / durationMs) * 100).toInt().coerceIn(0, 100)
    }
}
