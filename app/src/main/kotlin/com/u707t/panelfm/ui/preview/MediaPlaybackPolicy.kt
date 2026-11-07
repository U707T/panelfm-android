package com.u707t.panelfm.ui.preview

import androidx.media3.common.Player

/**
 * 播放状态机策略（**纯函数**，`MediaPlaybackPolicyTest` 直接锁行为）。
 *
 * ## 为什么单独抽出来
 *
 * 「视频点开不播、但缓冲条在涨、拖动进度条还能看到画面」这一组现象，在 Media3 里
 * **只有一个代码级来源**：`Player.isPlaying` 的语义是
 *
 * ```
 * isPlaying = playbackState == READY && playWhenReady && playbackSuppressionReason == NONE
 * ```
 *
 * （`BasePlayer.isPlaying()` 字节码已核对，Media3 1.10.1）
 *
 * 位置推进要求 `isPlaying` 为真，而 seek / 缓冲 / 解码都不要求。于是只要第三项被置位，
 * 就必然是「能缓冲、能拖出画面、时钟不走」。而 `playbackSuppressionReason` 只有三个来源：
 *
 * | 值 | 来源 | 由谁决定 |
 * |---|---|---|
 * | 1 | 音频焦点被占（`AudioFocusManager`） | `setAudioAttributes(attrs, handleAudioFocus)` |
 * | 3 | 音频输出「不合适」（`SuitableOutputChecker`） | `setSuppressPlaybackOnUnsuitableOutput` |
 * | 4 | scrubbing（本项目未启用） | — |
 *
 * **结论：这两个开关必须在构造播放器时就关掉**（见 `MediaScreen` 的播放器构造）。
 * 焦点改由 [MediaAudioFocus] 自己管，且只影响**音量**，永远不影响播放。
 * 这样 `isPlaying` 就恒等于 `READY && playWhenReady`，「有画面不播」在结构上不可能出现。
 *
 * 剩下的「真卡死」（位置不动）交给看门狗 [watchdogAction]，动作是**原地重新装载**，
 * 绝不 `pause()` —— 见该函数 KDoc 里 v2.2.3 的事故复盘。
 */
internal object MediaPlaybackPolicy {

    /**
     * 「有播放意图却迟迟没有进展」的判定阈值。
     *
     * 低于 8 秒会误伤正常的网络起播（SMB / WebDAV 首帧常见 2–4 秒）；
     * 高于 15 秒用户已经以为卡死并退出了。
     */
    const val StallTimeoutMs = 8_000L

    /**
     * **已经出过帧之后**再次冻结的恢复窗口：比 [StallTimeoutMs] 短很多是故意的 ——
     * 能播的视频突然停住，用户 1 秒内就能察觉，早点自救体验最好；
     * 而 [StallTimeoutMs] 到了才弹提示（那才是打扰）。
     */
    const val AutoRecoverDelayMs = 1_200L

    /**
     * 自动恢复的最大次数。
     *
     * 不能无限重试：真正的解码器停摆 `seekTo(当前)` 也救不回来，无限重试会变成
     * 每 1.2 秒一次的抖动，比不恢复更糟。用尽后交给状态条 + 用户。
     */
    const val AutoRecoverLimit = 2

    // ------------------------------------------------------------------ 播放键

    /** 播放键的计划：先执行什么、最后把 `playWhenReady` 置成什么。 */
    internal data class TogglePlan(
        /** 目标 `playWhenReady` */ val play: Boolean,
        /** 播放器还在 `STATE_IDLE`（首次 / 出错后）→ 必须先 `prepare()`，否则 `play()` 不生效 */
        val prepareFirst: Boolean,
        /** 处于播放抑制（`playbackSuppressionReason != NONE`）→ 先重新申请一次音频焦点 */
        val reacquireFocus: Boolean,
        /** `STATE_ENDED`：从头再放一遍（而不是"保持结束态"） */ val restartFromStart: Boolean,
        /** 非 null = 先 seek 到该位置再播（卡死恢复用：原地重新发起装载） */
        val reseekToMs: Long? = null,
    )

    /**
     * 播放键语义：**按「意图」而不是「实况」切换**。
     *
     * - 抑制中 → 当作「没在播」，重新申请焦点后继续播；
     * - `STATE_IDLE` → 先 prepare（首次装载前点播放、或出错重试后的第一次点击）；
     * - `STATE_ENDED` → 从 0 重新开始（**不管 `playWhenReady` 是什么**：播完后它仍是 true，
     *   只认它就会落到取反分支变成 `pause()`，表现为「按一下没反应、按两下才重播」）；
     * - **有意图 + `READY` + 没在出帧** → 原地重新装载（[planStallRecovery]），
     *   **绝不能取反成 `pause()`** —— 那正是「按了播放反而停住」的来源；
     * - 其余 → 取反 `playWhenReady`。
     */
    fun planToggle(
        playWhenReady: Boolean,
        playbackState: Int,
        suppressionReason: Int,
        /** 这一帧到底有没有在走（`Player.isPlaying`）。默认 true 表示「没给出实况就别改语义」 */
        isPlaying: Boolean = true,
        currentPositionMs: Long = 0L,
    ): TogglePlan {
        if (suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            return TogglePlan(play = true, prepareFirst = false, reacquireFocus = true, restartFromStart = false)
        }
        if (playbackState == Player.STATE_IDLE) {
            return TogglePlan(play = true, prepareFirst = true, reacquireFocus = false, restartFromStart = false)
        }
        // 结束态：**无论 playWhenReady 是什么**都要从头重播。
        // ExoPlayer 播完不会把 playWhenReady 复位（仍是 true），旧条件 `!playWhenReady` 因此永远不成立，
        // 于是落到最后的取反分支 → pause()：用户看到 ▶、按下去没反应（要按两下才重播）。
        if (playbackState == Player.STATE_ENDED) {
            return TogglePlan(play = true, prepareFirst = false, reacquireFocus = false, restartFromStart = true)
        }
        if (playWhenReady && playbackState == Player.STATE_READY && !isPlaying) {
            // 「有意图、READY、却不出帧」= 卡在起播/解码上。按钮此时显示的是暂停，
            // 但语义必须是「继续播放」—— 用户按它是想让画面动，不是想让它停。
            return planStallRecovery(currentPositionMs)
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
     * `seekTo(当前位置)` + `play()` 会让 Media3 丢弃旧的 SampleQueue 重新读，
     * 是卡在缓冲 / 网络抖动后最有效的自救，而且**不会打断播放意图**
     * （`pause()` 才是打断：它会撕掉 renderer 的就绪状态，让起播重新排队）。
     */
    fun planStallRecovery(currentPositionMs: Long): TogglePlan =
        TogglePlan(
            play = true,
            prepareFirst = false,
            reacquireFocus = false,
            restartFromStart = false,
            reseekToMs = currentPositionMs.coerceAtLeast(0),
        )

    // ------------------------------------------------------------------ 看门狗

    /** 看门狗这一拍的结论：要么原地自救一次，要么交给用户。 */
    internal data class WatchdogAction(
        /** 原地重新装载一次（`seekTo(当前位置)`），不打断播放意图 */
        val recoverInPlace: Boolean = false,
        /** 自救用尽 / 非 READY 状态久等无进展 → 状态条 + 「继续播放」 */
        val giveUp: Boolean = false,
    ) {
        companion object {
            val None = WatchdogAction()
        }
    }

    /**
     * 卡死看门狗的判定（纯函数）。
     *
     * ## 判据
     *
     * **只看「播放位置有没有前进」**，不看 `isPlaying`，也不看缓冲：
     *  - `isPlaying` 在抑制态本身就是 false —— 用它当判据等于「用病症诊断病症」；
     *  - 缓冲在涨不代表在播（用户报的正是「缓冲在涨、位置不动」）。
     *
     * ## 为什么分两档窗口
     *
     * 从没出过帧（`everAdvanced == false`）时，第一帧可能要解码 1–3 秒（4K / HEVC /
     * 网络存储），这期间**不能动**：一 `seek` 就把正在解码的首帧丢掉，重新排队，
     * 慢片源会被越救越慢。所以首帧窗口用 [StallTimeoutMs]（8 秒）。
     * 已经出过帧之后再冻结，1 秒多就能确认是卡死，用 [AutoRecoverDelayMs]。
     *
     * ## v2.2.3 的事故复盘（为什么这里不再 `pause()`）
     *
     * v2.2.3 的看门狗写成 `LaunchedEffect(player, playWhenReady)` + 自愈动作
     * `pause()` → `play()`。自愈本身会改 `playWhenReady` → **effect 被取消并重启**
     * → 局部变量 `retries` / `noProgressMs` 一起清零 → 「最多 3 次」的上限形同虚设，
     * 实际是**每 0.9 秒 pause 一次的死循环**：起播稍慢的视频永远起不来。
     *
     * @param playWhenReady 播放意图（用户暂停时必须完全不动作）
     * @param playbackState `Player.playbackState`
     * @param frozenMs 位置没有前进的累计时长
     * @param retries 已自救次数
     * @param everAdvanced 本次装载是否**曾经**推进过（区分「首帧慢」与「中途冻住」）
     */
    fun watchdogAction(
        playWhenReady: Boolean,
        playbackState: Int,
        frozenMs: Long,
        retries: Int,
        everAdvanced: Boolean,
    ): WatchdogAction {
        if (!playWhenReady) return WatchdogAction.None
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return WatchdogAction.None
        val window = if (everAdvanced) AutoRecoverDelayMs else StallTimeoutMs
        if (frozenMs < window) return WatchdogAction.None
        if (playbackState == Player.STATE_READY && retries < AutoRecoverLimit) {
            return WatchdogAction(recoverInPlace = true)
        }
        if (frozenMs >= StallTimeoutMs && (playbackState != Player.STATE_READY || retries >= AutoRecoverLimit)) {
            return WatchdogAction(giveUp = true)
        }
        return WatchdogAction.None
    }

    // ------------------------------------------------------------------ 播放键图标 / 进度刷新

    /**
     * 播放键图标该显示「暂停」还是「播放」。
     *
     * **必须跟着播放意图走**，不能跟 `isPlaying`：缓冲中时 `isPlaying == false`，
     * 按 `isPlaying` 会显示成「播放」，而这时按下去没有效果 ——
     * 这就是「点了播放没反应」的直接来源。
     *
     * 三个例外：
     *  - `STATE_ENDED` 恒显示「播放」（此时按下是重播，不是暂停）；
     *  - `playWhenReady=false`（用户主动暂停）也显示「播放」；
     *  - **`READY` + 有意图 + 没在出帧**（真卡住）显示「播放」：此时 [planToggle]
     *    走的是「原地重装」而不是暂停，图标必须和动作一致。
     */
    fun showPauseIcon(playWhenReady: Boolean, playbackState: Int, isPlaying: Boolean): Boolean {
        if (playbackState == Player.STATE_ENDED) return false
        if (playWhenReady && playbackState == Player.STATE_READY && !isPlaying) return false
        return isPlaying || playWhenReady
    }

    /**
     * 进度/缓冲是否该刷新（500ms 轮询的开关）。
     *
     * 旧实现只看 `isPlaying`，于是**缓冲中**两条进度都不动 ——
     * 恰恰是「看起来卡死」的状态。现在只要还有播放意图（或还在装载）就刷新。
     */
    fun shouldRefreshProgress(playWhenReady: Boolean, playbackState: Int, isPlaying: Boolean): Boolean =
        isPlaying || playWhenReady || playbackState == Player.STATE_BUFFERING

    // ------------------------------------------------------------------ 文案

    /**
     * 播放抑制的可执行文案；`NONE` 返回 null（不需要提示）。
     *
     * 关闭两个抑制开关后这里理论上不会再出现 1 / 3，但保留文案作为兜底：
     * 真出现就说明设备/框架层另有来源，文案能直接指认。
     */
    fun describeSuppression(reason: Int): String? = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> null
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
            "音频被其他应用占用（已改为静音播放），点「继续播放」重新申请"
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
     * 卡死时的可诊断文案：**带上真实状态**。
     *
     * 这类问题（"能缓冲、能拖看画面、就是不播"）光看截图分不出来，
     * 把 `state` / 意图 / 实况 / 抑制原因 / 重试次数直接显示出来，
     * 用户截一张图就能定位，不用再靠猜。
     */
    fun describeStall(
        playbackState: Int,
        suppressionReason: Int,
        retries: Int,
        aheadPercent: Int?,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false,
        /** 音频焦点状态（[volumeForFocus] 的入参）；null = 不显示 */
        focus: Int? = null,
    ): String {
        val state = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "STATE_$playbackState"
        }
        return buildString {
            append("播放卡住（state=").append(state)
            append(" · 意图=").append(if (playWhenReady) "播" else "停")
            append(" · 实况=").append(if (isPlaying) "播" else "停")
            append(" · suppression=").append(suppressionReason)
            if (focus != null) append(" · focus=").append(focus)
            if (retries > 0) append(" · 已重试 ").append(retries)
            if (aheadPercent != null) append(" · 剩余可播 ").append(aheadPercent).append('%')
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

    // ------------------------------------------------------------------ 音频焦点（只影响音量）

    /** 还没申请 / 已放弃焦点 */
    const val FocusNone = 0

    /** 拿到焦点 → 正常出声 */
    const val FocusGranted = 1

    /** 延迟授予（别的应用暂时占着）→ 先静音播，拿到 `AUDIOFOCUS_GAIN` 自动恢复 */
    const val FocusDelayed = 2

    /** 被永久抢走（别的应用开始放音）→ 静音继续播，不打断画面 */
    const val FocusLost = 3

    /** 暂时被占（通话 / 语音）→ 静音继续播 */
    const val FocusLostTransient = 4

    /** 系统要求压低（导航播报等）→ 20% 音量 */
    const val FocusDuck = 5

    /** 申请被拒（多为请求本身被系统驳回，不代表「不许出声」）→ 照常出声 */
    const val FocusDenied = 6

    /**
     * 音频焦点 → 音量的映射。
     *
     * **焦点永远只影响音量，不影响播放**：拿不到焦点就静音出画面，而不是整段不播。
     * 这是本项目对「视频预览」的取舍 —— 没有声音可以接受，没有画面不行。
     */
    fun volumeForFocus(focus: Int, muted: Boolean): Float {
        if (muted) return 0f
        return when (focus) {
            FocusGranted, FocusDenied -> 1f
            FocusDuck -> 0.2f
            FocusNone, FocusDelayed, FocusLost, FocusLostTransient -> 0f
            // 未知状态按「有焦点」处理：宁可出声，也不要出现「画面在走但没声音」的哑剧
            else -> 1f
        }
    }

    /**
     * 「为什么没声音」的解释文案；不需要解释时返回 null。
     *
     * 静音播放是刻意取舍，但用户不知道就会以为是 bug —— 明确告诉他画面不受影响。
     */
    fun describeFocusAudio(focus: Int, muted: Boolean): String? = when {
        muted -> null // 用户自己按的静音，不需要解释
        focus == FocusDelayed || focus == FocusLost || focus == FocusLostTransient ->
            "音频被其他应用占用，正在静音播放（画面不受影响）"
        else -> null
    }
}
