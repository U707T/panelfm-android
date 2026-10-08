package com.u707t.panelfm.ui.preview

// ================================================================================================
// MediaScreen 拆分（2026-10-08 重审 §2）：播放器手势层
//  - 点按：detectTapGestures（单击显隐 / 双击进退 / 长按 2 倍速）
//  - 拖动：自定义循环（横滑进度 / 右竖滑音量 / 左竖滑应用内亮度）
//  - 本次修复：① 进度基准改为「按下时的位置快照」（原实时基准会重复计入拖动时长）；
//              ② 横滑进行中标记 gestureSeeking（控制层自动隐藏据此避让，不再落定中途值）；
//              ③ 屏幕左右边缘 48dp 避让（系统返回手势区，照 IRIS）
// ================================================================================================

import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.u707t.panelfm.core.ui.MtGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器手势层的对外依赖：全部是「读当前值 / 写目标状态」的回调。
 *
 * 为什么要这种形状：手势注册在 `pointerInput(Unit)` 里、只捕获**首次组合**的闭包；
 * 页面状态若是普通值会被冻住。这里所有回调都指向 `remember` 的委托对象 / 稳定函数，
 * 跨重组读取的永远是最新值（与旧实现里「闭包直接读委托」的语义一致，只是显式化）。
 */
internal class MediaGestureHooks(
    val player: ExoPlayer,
    val scope: CoroutineScope,
    /** 音频服务（音量手势读写 STREAM_MUSIC） */
    val audio: AudioManager,
    val isLocked: () -> Boolean,
    val isControlsVisible: () -> Boolean,
    val topBarHeightPx: () -> Int,
    val bottomBarHeightPx: () -> Int,
    val showControls: () -> Unit,
    val toggleControls: () -> Unit,
    val setSpeedBoost: (Boolean) -> Unit,
    val setHud: (String?) -> Unit,
    val setSeekPreview: (Long?) -> Unit,
    val setPositionMs: (Long) -> Unit,
    val setVolumeRatio: (Float?) -> Unit,
    val setBrightnessRatio: (Float?) -> Unit,
    val getBrightness: () -> Float,
    val setBrightness: (Float) -> Unit,
    val getDurationMs: () -> Long,
    val setMuted: (Boolean) -> Unit,
    /** 横滑进度进行中：控制层自动隐藏据此避让（拖动期间不许收起、不许落定中途值） */
    val setGestureSeeking: (Boolean) -> Unit,
)

/**
 * 触点是否落在**当前正显示着**的控制层内。
 *
 * 必须带 `isControlsVisible()`：控制层隐藏后，顶/底高度只是残留的测量值；
 * 若继续按它们避让，屏幕顶部/底部两条带（用户最常点的地方）会完全不响应 ——
 * 这正是「控件消失后唤不醒」的原因之一。
 */
private fun isInsideControls(hooks: MediaGestureHooks, x: Float, y: Float, width: Int, height: Int): Boolean =
    hooks.isControlsVisible() && (y <= hooks.topBarHeightPx() || y >= height - hooks.bottomBarHeightPx())

/** 相对当前位置拨动 [deltaMs]（双击进退与底栏 ±10s·15s 共用） */
internal fun seekPlayerBy(player: ExoPlayer, durationMs: Long, deltaMs: Long) {
    val target = (player.currentPosition + deltaMs).coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE)
    player.seekTo(target)
}

/** 双击：左 / 右 1/3 快退 / 快进 10 秒；中间播放暂停 */
private fun doubleTapSeek(hooks: MediaGestureHooks, x: Float, width: Int) {
    when {
        x < width / 3f -> {
            seekPlayerBy(hooks.player, hooks.getDurationMs(), -10_000)
            hooks.setHud("-10s")
        }
        x > width * 2 / 3f -> {
            seekPlayerBy(hooks.player, hooks.getDurationMs(), +10_000)
            hooks.setHud("+10s")
        }
        else -> if (hooks.player.isPlaying) hooks.player.pause() else hooks.player.play()
    }
}

/**
 * 播放器手势层（照 IRIS：**点按与拖动交给两个独立识别器**，互不牵连）。
 *
 * 点按：detectTapGestures —— 单击显隐 / 双击进退 / 长按 2 倍速；
 * 拖动：自定义循环 —— 横滑调进度 / 右竖滑音量 / 左竖滑应用内亮度。
 *
 * 旧实现把四件事塞进同一个手写状态机：单击要等 310ms 才能判定（连点两下就落进
 * 双击分支、永远不显示控件），长按/拖动/单击还会互相干扰。拆开后各自简单可靠。
 */
@Composable
internal fun rememberMediaPlayerGestures(hooks: MediaGestureHooks): Modifier {
    // 长按倍速的任务句柄：拖动开始时要能取消它（照 IRIS：onPanStart 取消长按）
    val boostJob = remember { mutableStateOf<Job?>(null) }

    val tapModifier = Modifier.pointerInput(Unit) {
        var wokeThisGesture = false
        detectTapGestures(
            onPress = { offset ->
                wokeThisGesture = false
                if (!hooks.isLocked() && !hooks.isControlsVisible() &&
                    !isInsideControls(hooks, offset.x, offset.y, size.width, size.height)
                ) {
                    // 隐藏态：按下**立即**唤出（不等双击判定窗口）
                    hooks.showControls()
                    wokeThisGesture = true
                }
                // 长按 = 2 倍速（松手恢复）。只对「画面区域」生效：
                // 长按控制条上的按钮不该触发倍速。
                // ⚠️ 这里**不再**写 HUD（旧实现另发一条「2x」提示，与专门的倍速指示重复）——
                //    提示统一由 MediaChrome.kt 的 SpeedBoostIndicator 动效承担。
                if (!hooks.isLocked() && !isInsideControls(hooks, offset.x, offset.y, size.width, size.height)) {
                    boostJob.value = hooks.scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        hooks.setSpeedBoost(true)
                    }
                }
                tryAwaitRelease()
                boostJob.value?.cancel()
                boostJob.value = null
                hooks.setSpeedBoost(false)
            },
            onTap = {
                if (!wokeThisGesture) hooks.toggleControls()
                wokeThisGesture = false
            },
            onDoubleTap = { offset ->
                if (!hooks.isLocked()) doubleTapSeek(hooks, offset.x, size.width)
                wokeThisGesture = false
            },
        )
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        val audio = hooks.audio
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (hooks.isLocked()) return@awaitEachGesture
            // 边缘避让（照 IRIS 的 48px edgeDeadZone）：屏幕左右边缘是系统返回手势区，
            // 从这里起手的拖动会被系统抢走 —— 直接不参与，免得「边缘滑不动 / 时灵时不灵」。
            val edge = 48.dp.toPx()
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            // 落在控制层内 → 完全让位（进度条拖动、按钮点击不受干扰）
            if (isInsideControls(hooks, down.position.x, down.position.y, size.width, size.height)) {
                return@awaitEachGesture
            }
            val startPos = down.position
            // 手势判定与灵敏度统一走 MtGesture（照 IRIS：死区 8dp、主轴优势比、3px/秒、200px 满量程），
            // 三个手势（进度 / 音量 / 亮度）用同一套阈值与增益 —— 消除「进入难易不一、手感割裂」
            val density = this@pointerInput.density
            var mode = 0   // 0=未定 1=进度 2=右侧音量 3=左侧亮度
            var seekTarget = 0L
            // 进度基准：**按下那一刻的位置快照**（照 IRIS 的 startSeekPosition）。
            // 旧实现每帧以实时 currentPosition 作基准叠加累计位移，而拖动期间播放未暂停、
            // 位置持续推进 → 拖动时长被重复计入，慢拖时落点越拖越偏。
            var seekBase = 0L
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val startBrightness = hooks.getBrightness()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 已被控件消费 → 让位，不参与播放器手势
                    if (mode == 0 && change.isConsumed) break
                    val dx = change.position.x - startPos.x
                    val dy = change.position.y - startPos.y
                    if (mode == 0) {
                        // 死区 + 主轴优势比：未达阈值 / 斜滑时保持未定，避免在功能之间跳变
                        val horizontal = MtGesture.playerAxis(
                            with(density) { dx.toDp().value }, with(density) { dy.toDp().value },
                        )
                        if (horizontal != null) {
                            // 开始拖动 → 取消可能已在计时的长按倍速（否则拖到一半会突然 2x）
                            boostJob.value?.cancel()
                            boostJob.value = null
                            hooks.setSpeedBoost(false)
                            mode = when {
                                horizontal -> 1
                                startPos.x > size.width / 2f -> 2
                                else -> 3
                            }
                            if (mode == 1) {
                                seekBase = hooks.player.currentPosition
                                seekTarget = seekBase
                                hooks.setGestureSeeking(true)
                            }
                        }
                    }
                    when (mode) {
                        1 -> if (hooks.getDurationMs() > 0) {
                            // 固定灵敏度（3px = 1 秒），与屏幕宽度无关
                            val delta = MtGesture.seekDeltaSeconds(dx) * 1000L
                            seekTarget = (seekBase + delta).coerceIn(0, hooks.getDurationMs())
                            hooks.setSeekPreview(seekTarget)
                            hooks.setHud("${clock(hooks.player.currentPosition)} → ${clock(seekTarget)} / ${clock(hooks.getDurationMs())}")
                            change.consume()
                        }
                        2 -> {
                            // 固定灵敏度（200px = 满量程）
                            val delta = MtGesture.levelDelta(dy)
                            val target = (startVolume + delta * maxVolume).toInt().coerceIn(0, maxVolume)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            hooks.setVolumeRatio(target.toFloat() / maxVolume)
                            hooks.setBrightnessRatio(null) // 两端浮层共用中央位置：清掉对面残留，避免叠两层
                            hooks.setMuted(target == 0)
                            change.consume()
                        }
                        3 -> {
                            val delta = MtGesture.levelDelta(dy)
                            val target = (startBrightness + delta).coerceIn(0.05f, 1f)
                            hooks.setBrightness(target)
                            hooks.setBrightnessRatio(target)
                            hooks.setVolumeRatio(null) // 同上：只留当前手势这一个浮层
                            change.consume()
                        }
                    }
                    if (!change.pressed) {
                        if (mode == 1) {
                            // 松手落定：seek 到预览值并同步显示位置
                            if (hooks.getDurationMs() > 0) {
                                hooks.player.seekTo(seekTarget)
                                hooks.setPositionMs(seekTarget)
                            }
                        }
                        break
                    }
                }
            } finally {
                // 任何退出路径（松手 / 中断 / 异常）都要复位横滑标记与预览 ——
                // 否则控制层会因「拖动中」标记残留而永不自动隐藏。
                if (mode == 1) {
                    hooks.setGestureSeeking(false)
                    hooks.setSeekPreview(null)
                }
            }
        }
    }

    return tapModifier.then(dragModifier)
}
