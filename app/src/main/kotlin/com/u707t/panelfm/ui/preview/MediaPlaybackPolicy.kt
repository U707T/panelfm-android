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
     * 自动恢复动作的最短等待：`READY` 却没帧，先等这么久再 `pause()→play()`。
     *
     * 比 [StallTimeoutMs] 短很多是**故意的**：这里的动作是「无害地重新申请一次音频焦点」，
     * 早一点试、用户几乎无感；而 [StallTimeoutMs] 到了才弹提示面板（那才是打扰）。
     * 0.9 秒足以排除 seek 后正常的瞬时 READY→(下一个) 帧切换。
     */
    const val AutoRecoverDelayMs = 900L

    /**
     * 自动恢复的最大次数。
     *
     * 为什么不能无限重试：某些机型上音频输出会持续不可用（如蓝牙耳机断了又"半连着"），
     * 无限 `pause()/play()` 会变成每秒一次的抖动，比不恢复更糟。
     * 3 次仍未出帧 → 交给状态条 + 用户决定（手动 seek / 外放 / 外置应用）。
     */
    const val AutoRecoverLimit = 3

    /**
     * 是否需要**自动**重新申请音频焦点（[MediaScreen] 的看门狗据此动作）。
     *
     * 三种状态要分清：
     *  - `BUFFERING`：在等数据 —— 重试只会打断正常起播，**不能**动；
     *  - `READY && isPlaying`：正常出帧；
     *  - `READY && !isPlaying`：renderer 一个缓冲都没消费 —— 这才是"卡死"，
     *    且几乎只能来自音频焦点 / 音频路由被抑制（ExoPlayer **不会**自己重试申请）。
     *
     * @param stalledMs 已经「有意图但没进展」的累计时长
     * @param retries 已自动重试次数
     */
    fun shouldAutoRecover(
        playbackState: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        stalledMs: Long,
        retries: Int,
    ): Boolean =
        playWhenReady &&
            playbackState == Player.STATE_READY &&
            !isPlaying &&
            stalledMs >= AutoRecoverDelayMs &&
            retries < AutoRecoverLimit

    /**
     * 是否需要**手动**接管（状态条 + 「继续播放」按钮）。
     *
     * 自动重试用尽了还没出帧，才把决定权交给用户。
     */
    fun needsManualRecovery(
        playbackState: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        stalledMs: Long,
        retries: Int,
    ): Boolean =
        isStalled(playWhenReady, isPlaying, stalledMs) &&
            (playbackState != Player.STATE_READY || retries >= AutoRecoverLimit)

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
     * 卡死判定：**有播放意图、没在出帧**，且持续超过 [stallMs]。
     *
     * ⚠️ 判据只有「出没出帧」，**不看缓冲**。上一版错误地要求「位置和缓冲都不前进」，
     * 于是「缓冲在涨、位置不动」（正是用户报的现象）被判成"有进展" —— 永远不会触发。
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
            "音频被其他应用占用（已自动重试），点「继续播放」再试一次"
        // UNSUITABLE_AUDIO_ROUTE (=2) 在 Media3 1.10 已废弃，由 UNSUITABLE_AUDIO_OUTPUT (=3) 取代；
        // 旧设备上仍可能上报 2，两个值分别保留一条文案（数值不同，不会重复分支）
        SUPPRESSION_UNSUITABLE_AUDIO_ROUTE_LEGACY ->
            "当前音频输出不可用（耳机/蓝牙断开或未就绪），点「继续播放」重试"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT ->
            "音频输出被系统抑制（静音或输出设备异常），点「继续播放」重试"
        Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING ->
            "拖动预览中"
        else -> "播放被系统暂停，点「继续播放」重试"
    }

    /** `PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE` 的数值（Media3 1.10 标记废弃，值仍为 2） */
    private const val SUPPRESSION_UNSUITABLE_AUDIO_ROUTE_LEGACY = 2

    /**
     * 卡死时的可诊断文案：**带上真实状态码**。
     *
     * 这类问题（"能缓冲、能拖看画面、就是不播"）在代码里只有三种可能，
     * 光看截图分不出来 —— 把 `state` / `suppression` 直接显示出来，
     * 用户截一张图就能定位，不用再靠猜。
     */
    fun describeStall(playbackState: Int, suppressionReason: Int, retries: Int, aheadPercent: Int?): String {
        val state = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "STATE_$playbackState"
        }
        return buildString {
            append("播放卡住（state=").append(state)
            append(" / suppression=").append(suppressionReason)
            if (retries > 0) append(" / 已重试 ").append(retries)
            if (aheadPercent != null) append(" / 剩余可播 ").append(aheadPercent).append('%')
            append('）')
        }
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
