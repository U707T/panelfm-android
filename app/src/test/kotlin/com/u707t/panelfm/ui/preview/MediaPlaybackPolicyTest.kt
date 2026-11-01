package com.u707t.panelfm.ui.preview

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机策略回归（「视频无法播放但能缓冲 / 能拖动看画面」专项）。
 *
 * 这一组用例锁死的是**一个具体的误判**：把「播放意图」（`playWhenReady`）与
 * 「实况」（`isPlaying`）混成一个 `isPlaying`，于是「缓冲中」「被音频焦点抑制」
 * 「用户暂停」三种完全不同的状态在代码里长得一模一样 —— 播放键在这三种状态下
 * 都显示成「播放」，而其中只有最后一种能靠 `play()` 救回来。
 */
class MediaPlaybackPolicyTest {

    private val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    // ------------------------------------------------------------------ planToggle

    @Test
    fun `正在播放时点一下 = 暂停`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            suppressionReason = none,
        )
        assertFalse("应暂停", plan.play)
        assertFalse(plan.reacquireFocus)
        assertFalse(plan.prepareFirst)
        assertFalse(plan.restartFromStart)
    }

    @Test
    fun `缓冲中按播放键 = 暂停（因为按钮此时显示的是暂停）`() {
        // 关键点不是「按下去变成什么」，而是**按钮显示什么**：
        // 旧实现用 isPlaying 决定图标，缓冲中显示「播放」，按下去 play() 又是空操作
        // → 用户看到的就是「点了播放没反应」。现在图标与动作都取 playWhenReady，两者永远一致。
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_BUFFERING,
            suppressionReason = none,
        )
        assertFalse("playWhenReady=true 时按钮应显示暂停，按下即暂停", plan.play)
    }

    @Test
    fun `按钮显示态取播放意图而不是实况（缓冲中显示暂停）`() {
        assertTrue(
            "缓冲中 playWhenReady=true → 按钮必须是暂停态，否则按下去是空操作",
            MediaPlaybackPolicy.showPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
            ),
        )
        assertFalse(
            MediaPlaybackPolicy.showPauseIcon(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `用户暂停后即使还在后台缓冲也显示播放（不能显示成暂停）`() {
        assertFalse(
            MediaPlaybackPolicy.showPauseIcon(
                playWhenReady = false,
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `播放结束恒显示播放（此时按下是重播）`() {
        assertFalse(
            MediaPlaybackPolicy.showPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_ENDED,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `被音频焦点抑制时必须 pause 再 play 重新申请焦点`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
        )
        assertTrue(plan.play)
        assertTrue("抑制态直接 play() 是空操作，必须重新申请焦点", plan.reacquireFocus)
    }

    @Test
    fun `输出设备不可用同样走重新申请焦点路径`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
        )
        assertTrue(plan.reacquireFocus)
    }

    @Test
    fun `首次打开（STATE_IDLE）点播放要先 prepare`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = false,
            playbackState = Player.STATE_IDLE,
            suppressionReason = none,
        )
        assertTrue(plan.play)
        assertTrue("IDLE 下 play() 不启动装载，必须先 prepare()", plan.prepareFirst)
    }

    @Test
    fun `播完（STATE_ENDED）点播放从头开始`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = false,
            playbackState = Player.STATE_ENDED,
            suppressionReason = none,
        )
        assertTrue(plan.play)
        assertTrue("结束态要 seekTo(0) 而不是原地空转", plan.restartFromStart)
    }

    @Test
    fun `用户手动暂停后点播放 = 继续`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = false,
            playbackState = Player.STATE_READY,
            suppressionReason = none,
        )
        assertTrue(plan.play)
        assertFalse(plan.reacquireFocus)
    }

    // ------------------------------------------------------------------ shouldRefreshProgress

    @Test
    fun `缓冲中也必须刷新进度（否则两条进度一起冻住 = 看起来卡死）`() {
        assertTrue(
            MediaPlaybackPolicy.shouldRefreshProgress(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `被抑制时也要刷新（用户能看到缓冲在涨，从而知道不是卡死）`() {
        assertTrue(
            MediaPlaybackPolicy.shouldRefreshProgress(
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `用户暂停后不再空转轮询`() {
        assertFalse(
            MediaPlaybackPolicy.shouldRefreshProgress(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `正常播放时当然要刷新`() {
        assertTrue(
            MediaPlaybackPolicy.shouldRefreshProgress(
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                isPlaying = true,
            ),
        )
    }

    // ------------------------------------------------------------------ isStalled

    @Test
    fun `正常起播的缓冲期不算卡死（阈值未到）`() {
        assertFalse(
            MediaPlaybackPolicy.isStalled(
                playWhenReady = true,
                isPlaying = false,
                stallMs = MediaPlaybackPolicy.StallTimeoutMs - 1,
            ),
        )
    }

    @Test
    fun `超过阈值仍无进展才算卡死`() {
        assertTrue(
            MediaPlaybackPolicy.isStalled(
                playWhenReady = true,
                isPlaying = false,
                stallMs = MediaPlaybackPolicy.StallTimeoutMs,
            ),
        )
    }

    @Test
    fun `没有播放意图时不判卡死（用户自己暂停的不该弹提示）`() {
        assertFalse(
            MediaPlaybackPolicy.isStalled(
                playWhenReady = false,
                isPlaying = false,
                stallMs = MediaPlaybackPolicy.StallTimeoutMs * 10,
            ),
        )
    }

    @Test
    fun `正在播时不判卡死`() {
        assertFalse(
            MediaPlaybackPolicy.isStalled(
                playWhenReady = true,
                isPlaying = true,
                stallMs = MediaPlaybackPolicy.StallTimeoutMs * 10,
            ),
        )
    }

    // ------------------------------------------------------------------ describeSuppression

    @Test
    fun `正常状态不显示任何提示`() {
        assertNull(MediaPlaybackPolicy.describeSuppression(none))
    }

    @Test
    fun `每种抑制原因都有可执行文案（不留空白）`() {
        val reasons = listOf(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE,
            Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING,
            Int.MAX_VALUE, // 未来新增的原因也不能显示空白
        )
        reasons.forEach { reason ->
            val text = MediaPlaybackPolicy.describeSuppression(reason)
            assertNotNull("抑制原因 $reason 没有文案", text)
            assertTrue("文案不能为空", text!!.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ bufferPercent

    @Test
    fun `缓冲百分比按剩余可播时长算`() {
        assertEquals(25, MediaPlaybackPolicy.bufferPercent(positionMs = 0, bufferedMs = 25_000, durationMs = 100_000))
        assertEquals(0, MediaPlaybackPolicy.bufferPercent(positionMs = 50_000, bufferedMs = 50_000, durationMs = 100_000))
    }

    @Test
    fun `已缓冲落后于当前位置时按 0 显示（不出现负缓冲）`() {
        assertEquals(0, MediaPlaybackPolicy.bufferPercent(positionMs = 60_000, bufferedMs = 10_000, durationMs = 100_000))
    }

    @Test
    fun `总时长未知时不给百分比`() {
        assertNull(MediaPlaybackPolicy.bufferPercent(positionMs = 0, bufferedMs = 1_000, durationMs = 0))
    }
}
