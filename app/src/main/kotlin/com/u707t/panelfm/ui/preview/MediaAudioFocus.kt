package com.u707t.panelfm.ui.preview

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 播放器的**音频焦点**：只决定「出不出声」，永远不决定「播不播」。
 *
 * ## 为什么不用 Media3 自带的那套
 *
 * `setAudioAttributes(attrs, handleAudioFocus = true)`（本项目 v0.6.0 起一直是 true）
 * 会把焦点交给 `androidx.media3.common.audio.AudioFocusManager`，它在焦点被占时：
 *
 *  - `LOSS_TRANSIENT` → `playbackSuppressionReason = 1`：`playWhenReady` 仍是 true、
 *    `STATE_READY`、缓冲继续涨、seek 照出帧，**唯独时钟不走**；
 *  - `LOSS`（永久）→ 直接把 `playWhenReady` 置 false。
 *
 * 两种结果都表现为「有画面、能拖、就是不播」，而且 ExoPlayer **不会自己重试申请焦点**，
 * 用户点播放键也没用（`playWhenReady` 本来就是 true）。这是 v2.2.2 / v2.2.3 两轮都没修掉的
 * 那半个根因（另一半是 `suppressPlaybackOnUnsuitableOutput`）。
 *
 * ## 现在的策略
 *
 *  - 申请焦点只为「别和别的应用一起吵」；
 *  - 拿不到（延迟授予 / 被抢 / 被拒）→ **静音继续播**，画面一秒都不停；
 *  - 拿回来（`AUDIOFOCUS_GAIN`）→ 自动恢复音量；
 *  - 被要求压低（DUCK）→ 20% 音量。
 *
 * 音量映射是纯函数 [MediaPlaybackPolicy.volumeForFocus]，可单测；本类只负责平台回调。
 */
internal class MediaAudioFocus(
    context: Context,
    private val onState: (Int) -> Unit,
) {
    private val audioManager: AudioManager? =
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()

    private val handler = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    /** 当前持有的焦点请求（null = 没申请 / 已放弃） */
    private var request: AudioFocusRequest? = null

    /**
     * 申请焦点（**重新申请**语义：先撤掉旧的再申请）。
     *
     * 必须先撤：拿到 `AUDIOFOCUS_LOSS` 之后，系统认为这个请求已经失效，
     * 直接再 `requestAudioFocus` 同一个对象不会产生新的回调 —— 这正是
     * 「点了播放没反应」在 API 层的最后一环。
     */
    fun request() {
        // 拿不到 AudioManager（极老 / 裁剪 ROM）：按「有焦点」处理，照常出声。
        // 绝不能就这么 return —— focusState 会停在 FocusNone，而 volumeForFocus(FocusNone)=0，
        // 结果是画面在走、永远没声音，且状态条不会解释（与「被拒也照常出声」的设计相反）。
        val manager = audioManager ?: run {
            onState(MediaPlaybackPolicy.FocusGranted)
            return
        }
        abandonQuietly(manager)
        val pending = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // 别的应用占着时接受「延迟授予」，而不是直接失败 —— 延迟期间先静音播
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change -> onState(fromPlatform(change)) }, handler)
            .build()
        request = pending
        val result = runCatching { manager.requestAudioFocus(pending) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        onState(
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> MediaPlaybackPolicy.FocusGranted
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> MediaPlaybackPolicy.FocusDelayed
                // 被拒 ≠ 不许出声：对预览场景来说照常出声比「莫名没声音」好
                else -> MediaPlaybackPolicy.FocusDenied
            },
        )
    }

    /** 放弃焦点（暂停 / 退出页面）。音量回到 0，等下一次 [request] 再恢复。 */
    fun abandon() {
        audioManager?.let { abandonQuietly(it) }
        onState(MediaPlaybackPolicy.FocusNone)
    }

    private fun abandonQuietly(manager: AudioManager) {
        val current = request ?: return
        request = null
        runCatching { manager.abandonAudioFocusRequest(current) }
    }

    private fun fromPlatform(change: Int): Int = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> MediaPlaybackPolicy.FocusGranted
        AudioManager.AUDIOFOCUS_LOSS -> MediaPlaybackPolicy.FocusLost
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MediaPlaybackPolicy.FocusLostTransient
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MediaPlaybackPolicy.FocusDuck
        else -> MediaPlaybackPolicy.FocusNone
    }
}
