package com.u707t.panelfm.ui.preview

// ================================================================================================
// 播放卡死看门狗（从 MediaScreen 拆出：2026-11-02 第四轮，控制单文件行数）
//  - 判据只看「播放位置有没有前进」，见 MediaPlaybackPolicy.watchdogAction
//  - 自救 = 原地 seekTo（不 pause、不改播放意图），用尽才交给状态条
// ================================================================================================

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * 卡死看门狗：**只看播放位置有没有前进**。
 *
 * ## 为什么不再看 `isPlaying`、也不看缓冲
 *
 * - `isPlaying` 在抑制态本身就是 false —— 用它当判据等于「用病症诊断病症」；
 * - 缓冲在涨不代表在播（用户报的正是「缓冲在涨、位置不动」）。
 *
 * ## v2.2.3 为什么没修好（事故复盘）
 *
 * 上一版是 `LaunchedEffect(player, playWhenReady)` + 自愈动作 `pause()` → `play()`。
 * 自愈本身会改 `playWhenReady` → **effect 被取消并重启** → 局部变量
 * `retries` / `noProgressMs` 一起清零 → 「最多 3 次」的上限形同虚设，
 * 实际是**每 0.9 秒 pause 一次的死循环**：起播稍慢的视频永远起不来，
 * 而且每次 pause 都会把正在解码的首帧丢掉。这一版：
 *
 *  - effect **只以 player 为 key**，循环里读实时值 —— 任何状态变化都不会重启它；
 *  - 自愈动作是 `seekTo(当前位置)`（[MediaPlaybackPolicy.planStallRecovery]），
 *    **不 pause**、不改播放意图，只让 Media3 丢掉旧 SampleQueue 重读；
 *  - 首帧窗口（[MediaPlaybackPolicy.StallTimeoutMs]）比「已出帧后冻结」窗口
 *    （[MediaPlaybackPolicy.AutoRecoverDelayMs]）宽得多，慢片源不会被误杀。
 *
 * @param onStalledChange 判定「自救也救不回来」时通知页面弹状态条
 * @param onRetryCountChange 已自救次数（进状态条文案，便于用户截图定位）
 */
@Composable
internal fun MediaStallWatchdog(
    player: ExoPlayer,
    onStalledChange: (Boolean) -> Unit,
    onRetryCountChange: (Int) -> Unit,
) {
    LaunchedEffect(player) {
        // 起点必须取实时位置：写成 -1 会让第一拍就判成「前进了」，首帧窗口直接失效
        var lastPosition = runCatching { player.currentPosition }.getOrDefault(0L)
        var frozenMs = 0L
        var retries = 0
        var everAdvanced = false
        var stalled = false
        while (true) {
            delay(500)
            val position = runCatching { player.currentPosition }.getOrDefault(0L)
            val intent = runCatching { player.playWhenReady }.getOrDefault(false)
            val state = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
            val advancing = position != lastPosition
            lastPosition = position

            if (!intent || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                // 用户自己暂停 / 还没装载 / 播完了：完全不动作（也别弹提示）。
                // 重试预算在这里复位：下一次「用户要求播放」是全新一轮。
                frozenMs = 0L
                retries = 0
                if (stalled) {
                    stalled = false
                    onStalledChange(false)
                }
                onRetryCountChange(0)
                continue
            }
            if (advancing) {
                frozenMs = 0L
                everAdvanced = true
                retries = 0
                if (stalled) {
                    stalled = false
                    onStalledChange(false)
                }
                onRetryCountChange(0)
                continue
            }
            frozenMs += 500

            val action = MediaPlaybackPolicy.watchdogAction(
                playWhenReady = intent,
                playbackState = state,
                frozenMs = frozenMs,
                retries = retries,
                everAdvanced = everAdvanced,
            )
            if (action.recoverInPlace) {
                retries++
                onRetryCountChange(retries)
                frozenMs = 0L
                // 唯一有效的自救：原地重新发起装载。不 pause、不改意图。
                runCatching { player.seekTo(position.coerceAtLeast(0)) }
                continue
            }
            if (action.giveUp != stalled) {
                stalled = action.giveUp
                onStalledChange(stalled)
            }
        }
    }
}
