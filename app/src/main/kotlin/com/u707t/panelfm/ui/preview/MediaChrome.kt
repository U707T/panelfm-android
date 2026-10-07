package com.u707t.panelfm.ui.preview

// ================================================================================================
// MediaScreen 拆分（2026-10-08 重审 §2）：控制层交互控件
//  - PlayerSlider：进度条（三层轨道 + 拖动/点按双检测器）
//  - MediaTopBar / MediaBottomBar：悬浮顶栏 / 底栏（参数化，无页面状态所有权）
//  - MediaMiniProgress：控制层收起时的迷你进度浮层
//  - MediaErrorPanel：播放失败面板（重试 / 外部打开）
// ================================================================================================

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtSpec
import kotlin.math.abs

/**
 * 播放进度条（照 IRIS）。
 *
 * 视觉：4dp 圆角轨道 + **三层**——未播（暗）/ **已缓冲**（中）/ 已播（亮），6dp 圆拇指；
 * 交互：按下即暂停并进入拖动态、拖动实时 seek、松手按拖动前的状态续播；
 * 触摸热区 28dp（视觉只有 4dp，太细会拖不住）。
 */
@Composable
internal fun PlayerSlider(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    dragging: Boolean,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(0f) }
    val total = durationMs.coerceAtLeast(1L)
    val posF = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    val bufF = (bufferedMs.toFloat() / total).coerceIn(0f, 1f)
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier
            .height(28.dp)
            .onGloballyPositioned { widthPx = it.size.width.toFloat() }
            // 点按轨道 = 直接跳转（照 IRIS 的 Slider 行为）。
            // 必须走「开始 → seek → 结束」三步：只调 onSeek 会把 seekPreview 留在那里
            // 没人清理，时间显示就冻在点击值上。
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0 && widthPx > 0f) {
                        onSeekStart()
                        onSeek(((offset.x / widthPx).coerceIn(0f, 1f) * durationMs).toLong())
                        onSeekEnd()
                    }
                }
            }
            // 拖动 = 暂停 → 实时 seek → 松手续播
            //
            // ⚠️ 这里**不能**用 detectDragGestures：上面的 detectTapGestures 会
            //    `down.consume()`，而 detectDragGestures 的 awaitFirstDown 默认要求
            //    「未被消费」→ 拖动永远起不来（用户观感就是「进度条不跟手」）。
            //    改为：显式允许已消费的 down + 用**手指绝对位置**换算比例
            //    （绝对位置比累加 delta 更准，拇指始终在手指正下方）。
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (durationMs <= 0 || widthPx <= 0f) return@awaitEachGesture
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!started) {
                            if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                // 越过 touch slop 才进入拖动态（否则会把点按吃掉）
                                started = true
                                dragFraction = (down.position.x / widthPx).coerceIn(0f, 1f)
                                onSeekStart()
                            } else if (!change.pressed) {
                                break   // 没有位移 = 点按，交给上面的 tap 检测器
                            }
                        }
                        if (started) {
                            change.consume()
                            val next = (change.position.x / widthPx).coerceIn(0f, 1f)
                            dragFraction = next
                            onSeek((next * durationMs).toLong())
                        }
                        if (!change.pressed) {
                            if (started) {
                                dragFraction = null
                                onSeekEnd()
                            }
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val trackTop = (h - 4.dp.toPx()) / 2f
            val trackH = 4.dp.toPx()
            val radius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f)
            val shown = dragFraction ?: posF

            // 1) 未播
            drawRoundRect(
                color = Color.White.copy(alpha = 0.27f),
                topLeft = Offset(0f, trackTop),
                size = Size(w, trackH),
                cornerRadius = radius,
            )
            // 2) 已缓冲
            drawRoundRect(
                color = Color.White.copy(alpha = 0.47f),
                topLeft = Offset(0f, trackTop),
                size = Size(w * bufF.coerceAtLeast(shown), trackH),
                cornerRadius = radius,
            )
            // 3) 已播
            drawRoundRect(
                color = Color.White.copy(alpha = 0.87f),
                topLeft = Offset(0f, trackTop),
                size = Size(w * shown, trackH),
                cornerRadius = radius,
            )
            // 拇指（拖动时更大）
            drawCircle(
                color = Color.White,
                radius = if (dragging) 8.dp.toPx() / 2f * 1.6f else 6.dp.toPx() / 2f * 1.6f,
                center = Offset(w * shown, h / 2f),
            )
        }
    }
}

/**
 * 悬浮顶栏：← / 文件名 / ⋮（倍速 · 循环 · 画面 · 静音）。
 *
 * 菜单开合状态由页面持有（[menuOpen] 参与「控制层自动隐藏」判定——菜单打开时不许收起），
 * 不能内化在本组件里；其余参数都是「当前值 + 变更回调」。
 */
@Composable
internal fun MediaTopBar(
    title: String,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    userSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    looping: Boolean,
    onToggleLoop: () -> Unit,
    resizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MtIconButton(
            icon = MtIcon.BACK,
            contentDescription = "返回",
            iconSize = 24.dp,
            tint = Color.White,
            onClick = { onBack() },
        )
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp),
        )
        Box {
            MtIconButton(
                icon = MtIcon.MORE,
                contentDescription = "更多",
                iconSize = 24.dp,
                tint = Color.White,
                onClick = { onMenuOpenChange(true) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "倍速 ${s}x" + if (userSpeed == s) "  ✓" else "",
                                color = if (userSpeed == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { onSpeedChange(s); onMenuOpenChange(false) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("循环播放" + if (looping) "  ✓" else "") },
                    onClick = { onToggleLoop(); onMenuOpenChange(false) },
                )
                listOf(
                    "适应" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    "拉伸" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    "裁剪" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                ).forEach { (label, m) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "画面：$label" + if (resizeMode == m) "  ✓" else "",
                                color = if (resizeMode == m) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { onResizeModeChange(m); onMenuOpenChange(false) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("静音" + if (muted) "  ✓" else "") },
                    onClick = { onToggleMute(); onMenuOpenChange(false) },
                )
            }
        }
    }
}

/**
 * 悬浮底栏：进度行（当前时间 — 进度条 — 总时长）+ 7 键控制行。
 *
 * 进度拖动的三个回调由页面侧与「拖动前是否在播放」联动实现（本组件只负责转发）。
 */
@Composable
internal fun MediaBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    seekPreview: Long?,
    sliderDragging: Boolean,
    isPlaying: Boolean,
    shuffle: Boolean,
    playingIndex: Int,
    playlistSize: Int,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlay: () -> Unit,
    onTogglePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .then(modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(seekPreview ?: positionMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
            // 进度条照 IRIS：4dp 圆角轨道 + 三层（未播 / **已缓冲** / 已播）+ 6dp 圆拇指。
            // 拖动期间暂停播放（IRIS 同款做法），松手后按拖动前的播放状态决定是否续播。
            PlayerSlider(
                positionMs = seekPreview ?: positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                dragging = sliderDragging,
                onSeekStart = onSeekStart,
                onSeek = onSeek,
                onSeekEnd = onSeekEnd,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            Text(clock(durationMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        // 7 键 SpaceEvenly：第 4 键（播放/暂停，large）**正好落在屏幕正中**，
        // 左右各 3 键对称（随机 … 播放列表），比旧版 6 键时播放键偏右更和谐。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                label = if (shuffle) "随机播放：开" else "随机播放：关",
                // 打开态高亮（IRIS：未开启时颜色降到 60%）
                enabled = playlistSize > 0,
                onClick = onToggleShuffle,
                dim = !shuffle,
            ) {
                drawShuffle()
            }
            IconButton("上一集", enabled = playingIndex > 0, onClick = onPrev) {
                drawSkip(next = false)
            }
            IconButton("后退 10 秒", onClick = onSeekBack) {
                drawReplay(forward = false, seconds = "10")
            }
            IconButton(if (isPlaying) "暂停" else "播放", large = true, onClick = onTogglePlay) {
                if (isPlaying) drawPause() else drawPlay()
            }
            IconButton("前进 15 秒", onClick = onSeekForward) {
                drawReplay(forward = true, seconds = "15")
            }
            IconButton(
                "下一集",
                enabled = playingIndex >= 0 && playingIndex < playlistSize - 1,
                onClick = onNext,
            ) {
                drawSkip(next = true)
            }
            // 播放列表（最右）：右侧滑出面板（当前项高亮，点按切换）
            IconButton(
                "播放列表",
                enabled = playlistSize > 0,
                onClick = onTogglePlaylist,
            ) {
                drawPlaylist()
            }
        }
    }
}

/**
 * 迷你进度浮层（照 IRIS）：控制层收起时（例如双击进退后），
 * 仍能在不唤出整套控件的情况下看到「文件名 + 细进度条 + 时间」。
 *
 * 根节点无 pointerInput / clickable → 不参与事件消费，不挡住下层手势层。
 */
@Composable
internal fun MediaMiniProgress(
    title: String,
    positionMs: Long,
    seekPreview: Long?,
    durationMs: Long,
) {
    Box(Modifier.fillMaxSize()) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp)
                .fillMaxWidth(0.72f),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                val f = ((seekPreview ?: positionMs).toFloat() / durationMs).coerceIn(0f, 1f)
                drawRect(Color.White.copy(alpha = 0.25f))
                drawRect(Color.White, size = Size(size.width * f, size.height))
            }
            Text(
                "${clock(seekPreview ?: positionMs)} / ${clock(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * 播放失败面板：错误文案 + 可执行出口（重试 / 交给系统应用）。
 *
 * [onOpenExternal] 为 null 时不显示「用其他应用打开」（仅本地文件提供）。
 * 根节点无 pointerInput → 不挡手势层；按钮自身可点击。
 */
@Composable
internal fun MediaErrorPanel(
    message: String,
    onRetry: () -> Unit,
    onOpenExternal: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "播放失败",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                message,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            // 可执行出口：重试 / 交给系统应用（本地文件）
            Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onRetry) { Text("重试", color = Color.White) }
                if (onOpenExternal != null) {
                    TextButton(onClick = onOpenExternal) { Text("用其他应用打开", color = Color.White) }
                }
            }
        }
    }
}
