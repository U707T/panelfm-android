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
    fun `播完后 playWhenReady 仍是 true 时，播放键也必须从头重播`() {
        // 真实取值：ExoPlayer 播完不会复位 playWhenReady（仍是 true）。
        // 旧条件 `!playWhenReady && ENDED` 永远不成立 → 落到取反分支变成 pause()，
        // 用户看到 ▶、按下去没反应，要按两下才重播。
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_ENDED,
            suppressionReason = none,
        )
        assertTrue(plan.play)
        assertTrue("结束态不能退化成 pause", plan.restartFromStart)
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

    // ------------------------------------------------------------------ describeSuppression

    @Test
    fun `正常状态不显示任何提示`() {
        assertNull(MediaPlaybackPolicy.describeSuppression(none))
    }

    @Test
    fun `每种抑制原因都有可执行文案（不留空白）`() {
        val reasons = listOf(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            2, // legacy UNSUITABLE_AUDIO_ROUTE（Media3 1.10 已废弃，旧设备仍可能上报）
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

    // ------------------------------------------------------------------ planStallRecovery

    @Test
    fun `卡死恢复必须原地重新装载，不能退化成播放键取反`() {
        // 卡死态的 playWhenReady 本来就是 true —— 走 planToggle 会得到 pause，
        // 「继续播放」反而把播放停住。这里锁死恢复动作的语义。
        val plan = MediaPlaybackPolicy.planStallRecovery(currentPositionMs = 42_000)
        assertTrue("恢复动作必须是播放", plan.play)
        assertFalse("不是暂停（取反 playWhenReady 就错成暂停了）", plan.reacquireFocus)
        assertEquals("必须在当前位置重新发起装载", 42_000L, plan.reseekToMs)
        assertFalse("不该把用户弹回开头", plan.restartFromStart)
        assertFalse(plan.prepareFirst)
    }

    @Test
    fun `卡死恢复的负位置按 0 处理`() {
        assertEquals(0L, MediaPlaybackPolicy.planStallRecovery(-1).reseekToMs)
    }

    @Test
    fun `普通播放计划不带 reseek（避免每次点播放都多一次 seek）`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = false,
            playbackState = Player.STATE_READY,
            suppressionReason = none,
        )
        assertNull(plan.reseekToMs)
    }

    // ------------------------------------------------------------------ watchdogAction

    /**
     * 看门狗只看「位置有没有前进」。v2.2.3 的动作是 `pause()` → 每 0.9 秒打断一次，
     * 慢片源永远起不来；现在只有两种动作：原地重新装载 / 交给用户。
     */
    @Test
    fun `已出过帧后又冻住 → 1 秒多就原地自救（不 pause）`() {
        val action = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            frozenMs = MediaPlaybackPolicy.AutoRecoverDelayMs,
            retries = 0,
            everAdvanced = true,
        )
        assertTrue("必须自救", action.recoverInPlace)
        assertFalse("自救不等于交给用户", action.giveUp)
    }

    @Test
    fun `还没出过第一帧时窗口要宽得多（慢片源不能被误杀）`() {
        // 4K / HEVC / 网络存储的首帧解码 1–3 秒是正常的；这时候 seek 一下
        // 正好把正在解的首帧丢掉重排，越救越慢。
        val early = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            frozenMs = MediaPlaybackPolicy.AutoRecoverDelayMs * 2,
            retries = 0,
            everAdvanced = false,
        )
        assertFalse("首帧窗口内不许动", early.recoverInPlace)
        assertFalse(early.giveUp)

        val late = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            frozenMs = MediaPlaybackPolicy.StallTimeoutMs,
            retries = 0,
            everAdvanced = false,
        )
        assertTrue("超过 8 秒还不出首帧才自救", late.recoverInPlace)
    }

    @Test
    fun `缓冲中绝不自动 seek（会打断正常装载）`() {
        val action = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_BUFFERING,
            frozenMs = MediaPlaybackPolicy.AutoRecoverDelayMs + 500,
            retries = 0,
            everAdvanced = true,
        )
        assertFalse(action.recoverInPlace)
        assertFalse("还没到 8 秒，先别打扰用户", action.giveUp)
    }

    @Test
    fun `用户手动暂停后完全不动作（不能自己响起来）`() {
        val action = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = false,
            playbackState = Player.STATE_READY,
            frozenMs = MediaPlaybackPolicy.StallTimeoutMs * 10,
            retries = 0,
            everAdvanced = true,
        )
        assertFalse(action.recoverInPlace)
        assertFalse(action.giveUp)
    }

    @Test
    fun `IDLE 与 ENDED 不判卡死`() {
        listOf(Player.STATE_IDLE, Player.STATE_ENDED).forEach { state ->
            val action = MediaPlaybackPolicy.watchdogAction(
                playWhenReady = true,
                playbackState = state,
                frozenMs = MediaPlaybackPolicy.StallTimeoutMs * 10,
                retries = 0,
                everAdvanced = true,
            )
            assertFalse("state=$state", action.recoverInPlace || action.giveUp)
        }
    }

    @Test
    fun `自救次数用尽后交给用户（不再无限抖动）`() {
        val action = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            frozenMs = MediaPlaybackPolicy.StallTimeoutMs,
            retries = MediaPlaybackPolicy.AutoRecoverLimit,
            everAdvanced = true,
        )
        assertFalse("次数已用尽", action.recoverInPlace)
        assertTrue(action.giveUp)
    }

    @Test
    fun `缓冲卡死超过 8 秒直接交给用户（网络存储断了）`() {
        val action = MediaPlaybackPolicy.watchdogAction(
            playWhenReady = true,
            playbackState = Player.STATE_BUFFERING,
            frozenMs = MediaPlaybackPolicy.StallTimeoutMs,
            retries = 0,
            everAdvanced = true,
        )
        assertTrue(action.giveUp)
        assertFalse("BUFFERING 下 seek 只会重排队，不救", action.recoverInPlace)
    }

    // ------------------------------------------------------------------ describeStall

    @Test
    fun `卡死文案必须带真实状态码（截图即可定位，不用猜）`() {
        val text = MediaPlaybackPolicy.describeStall(
            playbackState = Player.STATE_READY,
            suppressionReason = 0,
            retries = 0,
            aheadPercent = 42,
        )
        assertTrue("要能看出 READY（时钟不走的那种卡）", text.contains("state=READY"))
        assertTrue("要能看出抑制原因", text.contains("suppression=0"))
        assertTrue("要带上剩余可播", text.contains("剩余可播 42%"))
    }

    @Test
    fun `卡死文案在重试过之后要标注次数`() {
        val text = MediaPlaybackPolicy.describeStall(
            playbackState = Player.STATE_BUFFERING,
            suppressionReason = 1,
            retries = 3,
            aheadPercent = null,
        )
        assertTrue(text.contains("state=BUFFERING"))
        assertTrue(text.contains("suppression=1"))
        assertTrue(text.contains("已重试 3"))
        assertFalse("总长未知时不要编造百分比", text.contains("剩余可播"))
    }

    @Test
    fun `未知状态码也不能显示成空白`() {
        val text = MediaPlaybackPolicy.describeStall(99, 0, 0, null)
        assertTrue(text.contains("STATE_99"))
    }

    // ------------------------------------------------------------------ aheadPercent

    @Test
    fun `可播放余量按「播放头之后还能播多久」算`() {
        assertEquals(25, MediaPlaybackPolicy.aheadPercent(positionMs = 0, bufferedMs = 25_000, durationMs = 100_000))
        assertEquals(0, MediaPlaybackPolicy.aheadPercent(positionMs = 50_000, bufferedMs = 50_000, durationMs = 100_000))
    }

    @Test
    fun `已缓冲落后于当前位置时按 0 显示（不出现负缓冲）`() {
        assertEquals(0, MediaPlaybackPolicy.aheadPercent(positionMs = 60_000, bufferedMs = 10_000, durationMs = 100_000))
    }

    @Test
    fun `总时长未知时不给百分比`() {
        assertNull(MediaPlaybackPolicy.aheadPercent(positionMs = 0, bufferedMs = 1_000, durationMs = 0))
    }

    // ------------------------------------------------------------------ planToggle：卡住时的语义

    @Test
    fun `READY 且有意图却没出帧 → 播放键必须是原地重装，不能取反成暂停`() {
        // 这是「画面上有帧、缓冲在涨、就是不播」时用户按播放键的那一下。
        // playWhenReady 本来就是 true，取反会得到 pause —— 用户观感就是「按播放反而停住」。
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            suppressionReason = none,
            isPlaying = false,
            currentPositionMs = 12_345,
        )
        assertTrue("必须继续播", plan.play)
        assertEquals("原地重新发起装载，不把用户弹回开头", 12_345L, plan.reseekToMs)
        assertFalse(plan.restartFromStart)
        assertFalse(plan.prepareFirst)
    }

    @Test
    fun `正常播放时仍是暂停（isPlaying=true 不会被误判成卡住）`() {
        val plan = MediaPlaybackPolicy.planToggle(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            suppressionReason = none,
            isPlaying = true,
            currentPositionMs = 12_345,
        )
        assertFalse(plan.play)
        assertNull(plan.reseekToMs)
    }

    @Test
    fun `卡住时播放键图标必须显示播放（此时按下去是重装，不是暂停）`() {
        assertFalse(
            "READY + 有意图 + 没在出帧 = 真卡住，按钮与动作必须一致",
            MediaPlaybackPolicy.showPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                isPlaying = false,
            ),
        )
    }

    // ------------------------------------------------------------------ 音频焦点 → 音量

    @Test
    fun `拿到焦点就正常出声（用户没静音）`() {
        assertEquals(1f, MediaPlaybackPolicy.volumeForFocus(MediaPlaybackPolicy.FocusGranted, muted = false), 0f)
    }

    @Test
    fun `拿不到焦点一律静音，但绝不因此不播`() {
        // 焦点只影响音量：延迟授予 / 被抢 / 暂时被占 → 都是 0 音量，
        // 播放器本身照常推进（isPlaying 不会被抑制）。
        listOf(
            MediaPlaybackPolicy.FocusNone,
            MediaPlaybackPolicy.FocusDelayed,
            MediaPlaybackPolicy.FocusLost,
            MediaPlaybackPolicy.FocusLostTransient,
        ).forEach { focus ->
            assertEquals("focus=$focus 必须静音", 0f, MediaPlaybackPolicy.volumeForFocus(focus, muted = false), 0f)
        }
    }

    @Test
    fun `系统要求压低时给两成音量，而不是静音`() {
        assertEquals(0.2f, MediaPlaybackPolicy.volumeForFocus(MediaPlaybackPolicy.FocusDuck, muted = false), 0.0001f)
    }

    @Test
    fun `申请被系统驳回时照常出声（被拒 ≠ 不许出声）`() {
        assertEquals(1f, MediaPlaybackPolicy.volumeForFocus(MediaPlaybackPolicy.FocusDenied, muted = false), 0f)
    }

    @Test
    fun `用户手动静音优先级最高`() {
        listOf(
            MediaPlaybackPolicy.FocusGranted,
            MediaPlaybackPolicy.FocusDuck,
            MediaPlaybackPolicy.FocusDenied,
        ).forEach { focus ->
            assertEquals(0f, MediaPlaybackPolicy.volumeForFocus(focus, muted = true), 0f)
        }
    }

    @Test
    fun `静音播放要解释原因（用户不知道就会当成没声音的 bug）`() {
        assertNotNull(MediaPlaybackPolicy.describeFocusAudio(MediaPlaybackPolicy.FocusLost, muted = false))
        assertNotNull(MediaPlaybackPolicy.describeFocusAudio(MediaPlaybackPolicy.FocusDelayed, muted = false))
        assertNull("有焦点时不用解释", MediaPlaybackPolicy.describeFocusAudio(MediaPlaybackPolicy.FocusGranted, muted = false))
        assertNull("用户自己按的静音不用解释", MediaPlaybackPolicy.describeFocusAudio(MediaPlaybackPolicy.FocusLost, muted = true))
    }
}
