package com.u707t.panelfm.ui.preview

import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 媒体播放（MT 风格 · 纯悬浮控制层）：
 *  - 顶：← / 文件名 / ⋮（倍速 · 循环 · 画面 · 静音），全部悬浮在画面上（无灰条）
 *  - 底：`当前时间 ——— 进度条 ——— 总时长` ＋ 矢量图标控制行 `|◀ ↺10 ⏯ 15↻ ▶|`
 *  - 左缘：锁定小圆钮；右缘：静音小圆钮；右缘竖条 = 音量，左缘竖条 = 亮度（滑动时出现）
 *  - 手势：单击显隐（播放中 4s 自动隐藏）；双击左右 ±10s、中间播放暂停；长按 2.0x；横滑进度；竖滑音量 / 亮度
 *  - 同目录音视频自动组成播放列表（⏮ ⏭）
 */
@Composable
fun MediaScreen(container: AppContainer, uri: VfsUri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    val player = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.vfsDataSourceFactory))
            .build()
            .apply { setAudioAttributes(audioAttributes, true) }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speedBoost by remember { mutableStateOf(false) }
    var userSpeed by remember { mutableStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var menuOpen by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var volumeRatio by remember { mutableStateOf<Float?>(null) }
    var brightnessRatio by remember { mutableStateOf<Float?>(null) }
    var playlist by remember { mutableStateOf<List<FileMetadata>>(emptyList()) }
    var playlistIndex by remember { mutableStateOf(-1) }

    val isAudioOnly = MimeTypes.kindOf(uri.name.substringAfterLast('.', "")) == MimeTypes.Kind.AUDIO

    // 播放期间屏幕常亮；退出释放播放器；亮度改动退出时恢复
    DisposableEffect(player) {
        val win = activity?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 记录进入时的亮度（-1 = 跟随系统），退出时恢复——否则用户调过亮度后系统亮度被永久改变
        val originalBrightness = win?.attributes?.screenBrightness ?: -1f
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose {
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching {
                val attrs = win?.attributes ?: return@runCatching
                if (attrs.screenBrightness != originalBrightness) {
                    attrs.screenBrightness = originalBrightness
                    win.attributes = attrs
                }
            }
            runCatching { player.removeListener(listener) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // 状态轮询（250ms）
    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            positionMs = player.currentPosition.coerceAtLeast(0)
            val d = player.duration
            durationMs = if (d in 1..(24L * 3600 * 1000)) d else 0
            delay(250)
        }
    }

    LaunchedEffect(speedBoost, userSpeed) { player.setPlaybackSpeed(if (speedBoost) 2f else userSpeed) }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(looping) { player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    // 控制栏自动隐藏（播放中且未开菜单；锁定时强制隐藏）
    LaunchedEffect(controlsVisible, isPlaying, menuOpen, locked) {
        if (controlsVisible && isPlaying && !menuOpen && !locked) {
            delay(4000)
            controlsVisible = false
        }
        if (locked) controlsVisible = false
    }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(1200)
            hud = null
        }
    }
    LaunchedEffect(volumeRatio) {
        if (volumeRatio != null) {
            delay(900)
            volumeRatio = null
        }
    }
    LaunchedEffect(brightnessRatio) {
        if (brightnessRatio != null) {
            delay(900)
            brightnessRatio = null
        }
    }

    fun playAt(index: Int) {
        val item = playlist.getOrNull(index) ?: return
        playlistIndex = index
        error = null
        player.setMediaItem(MediaItem.fromUri(mediaUriFor(item.uri)))
        player.prepare()
        player.playWhenReady = true
        controlsVisible = true
    }

    // 载入媒体 + 同目录播放列表
    LaunchedEffect(uri) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(mediaUriFor(uri)))
            player.prepare()
            player.playWhenReady = true
        }.onFailure { error = it.message }
        controlsVisible = true
        val parent = uri.parent
        if (parent != null) {
            runCatching {
                val vfs = container.locator.find(parent) ?: return@runCatching
                val media = withContext(Dispatchers.IO) {
                    vfs.list(parent).filter {
                        !it.isDirectory &&
                            MimeTypes.kindOf(it.extension) in listOf(MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO)
                    }
                }
                playlist = media
                playlistIndex = media.indexOfFirst { it.uri.toString() == uri.toString() }
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        player.seekTo(target)
    }

    var lastTapAt by remember { mutableStateOf(0L) }
    var lastTapX by remember { mutableStateOf(0f) }

    /** 单击 = 显隐控制栏；双击 = 左/右 ±10s、中间播放暂停 */
    fun registerTap(x: Float, width: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt in 1..300 && abs(x - lastTapX) < width * 0.3f) {
            lastTapAt = 0L
            when {
                x < width / 3f -> {
                    seekBy(-10_000)
                    hud = "-10s"
                }
                x > width * 2 / 3f -> {
                    seekBy(+10_000)
                    hud = "+10s"
                }
                else -> if (player.isPlaying) player.pause() else player.play()
            }
        } else {
            lastTapAt = now
            lastTapX = x
            scope.launch {
                delay(310)
                if (lastTapAt == now && !locked) controlsVisible = !controlsVisible
            }
        }
    }

    // ---- 手势层：单击 / 双击 / 长按倍速 / 横滑进度 / 竖滑音量（右）·亮度（左）
    val gestureModifier = Modifier.pointerInput(Unit) {
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (locked) return@awaitEachGesture
            val startPos = down.position
            val slop = viewConfiguration.touchSlop
            var mode = 0   // 0=未定 1=进度 2=右侧音量 3=左侧亮度
            var boost = false
            var released = false
            var seekTarget = 0L
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val window = activity?.window
            val startBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
            val longWatcher = scope.launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                if (!released && mode == 0) {
                    boost = true
                    speedBoost = true
                }
            }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val pos = change.position
                val dx = pos.x - startPos.x
                val dy = pos.y - startPos.y

                // 事件已被滑条 / 按钮等组件消费：让位，不参与播放器手势
                if (mode == 0 && !boost && change.isConsumed) {
                    longWatcher.cancel()
                    break
                }

                if (mode == 0 && !boost && (abs(dx) > slop || abs(dy) > slop)) {
                    longWatcher.cancel()
                    mode = when {
                        abs(dx) > abs(dy) -> 1
                        startPos.x > size.width / 2f -> 2
                        else -> 3
                    }
                    if (mode == 1) seekTarget = player.currentPosition
                }
                when (mode) {
                    1 -> {
                        if (durationMs > 0 && size.width > 0) {
                            val delta = (dx / size.width).toDouble() * durationMs
                            seekTarget = (seekTarget + delta).toLong().coerceIn(0, durationMs)
                            seekPreview = seekTarget
                            hud = "${clock(positionMs)} → ${clock(seekTarget)} / ${clock(durationMs)}"
                            change.consume()
                        }
                    }
                    2 -> {
                        if (size.height > 0) {
                            val delta = -(dy / size.height).toDouble()
                            val target = (startVolume + delta * maxVolume).toInt().coerceIn(0, maxVolume)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            volumeRatio = target.toFloat() / maxVolume
                            muted = target == 0
                            change.consume()
                        }
                    }
                    else -> {
                        if (size.height > 0 && window != null) {
                            val delta = -(dy / size.height)
                            val target = (startBrightness + delta).coerceIn(0.02f, 1f)
                            val attrs = window.attributes
                            attrs.screenBrightness = target
                            window.attributes = attrs
                            brightnessRatio = target
                            change.consume()
                        }
                    }
                }

                if (!change.pressed) {
                    released = true
                    longWatcher.cancel()
                    if (boost) {
                        speedBoost = false
                    } else when (mode) {
                        1 -> {
                            seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                        }
                        2, 3 -> Unit
                        // 被按钮 / 滑条消费的点击不再当作「单击画面」
                        else -> if (!change.isConsumed) registerTap(pos.x, size.width)
                    }
                    break
                }
            }
            if (boost) speedBoost = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 视频面（或音频占位）
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (isAudioOnly) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MtVectorIcon(
                    icon = MtIcon.AUDIO,
                    size = 72.dp,
                    tint = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                )
            }
        }

        // 手势层（覆盖整屏；按钮在它之上优先接收事件）
        Box(Modifier.fillMaxSize().then(gestureModifier))

        // ---- 顶部：← / 文件名 / ⋮（悬浮，无底条）
        if (controlsVisible && !locked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onBack() }
                        .padding(8.dp)
                        .size(24.dp),
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
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { menuOpen = true }
                            .padding(8.dp)
                            .size(24.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "倍速 ${s}x" + if (userSpeed == s) "  ✓" else "",
                                        color = if (userSpeed == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = { userSpeed = s; menuOpen = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("循环播放" + if (looping) "  ✓" else "") },
                            onClick = { looping = !looping; menuOpen = false },
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
                                onClick = { resizeMode = m; menuOpen = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("静音" + if (muted) "  ✓" else "") },
                            onClick = { muted = !muted; menuOpen = false },
                        )
                    }
                }
            }
        }

        // ---- 左侧小圆钮：锁定（锁定时常驻，其余控件隐藏）
        if (controlsVisible || locked) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (locked) 0.22f else 0.12f))
                    .clickable {
                        locked = !locked
                        if (!locked) controlsVisible = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) { drawLock(locked) }
            }
        }

        // ---- 右侧小圆钮：静音（未锁定且控制栏可见）
        if (controlsVisible && !locked) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable { muted = !muted },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) { drawSpeaker(muted) }
            }
        }

        // ---- 右缘音量竖条 / 左缘亮度竖条（滑动时出现）
        volumeRatio?.let { r ->
            VerticalLevelBar(
                ratio = r,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
            )
        }
        brightnessRatio?.let { r ->
            VerticalLevelBar(
                ratio = r,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
            )
        }

        // ---- 倍速提示（▶▶▶ 2.0X）
        if (speedBoost) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("▶▶▶  2.0X", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---- HUD 提示
        hud?.let {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (locked) {
            // 锁定时单击提示
            LaunchedEffect(locked) { hud = "已锁定，点左侧锁按钮解锁" }
        }

        // ---- 底部：进度行 + 矢量图标控制行（悬浮）
        if (controlsVisible && !locked) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(seekPreview ?: positionMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = (seekPreview ?: positionMs).coerceIn(0, durationMs.coerceAtLeast(1)).toFloat(),
                        onValueChange = { seekPreview = it.toLong() },
                        onValueChangeFinished = {
                            seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(clock(durationMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton("上一集", enabled = playlistIndex > 0, onClick = { playAt(playlistIndex - 1) }) {
                        drawSkip(next = false)
                    }
                    IconButton("后退 10 秒", onClick = { seekBy(-10_000) }) {
                        drawReplay(forward = false, seconds = "10")
                    }
                    IconButton(if (isPlaying) "暂停" else "播放", large = true, onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    }) {
                        if (isPlaying) drawPause() else drawPlay()
                    }
                    IconButton("前进 15 秒", onClick = { seekBy(+15_000) }) {
                        drawReplay(forward = true, seconds = "15")
                    }
                    IconButton(
                        "下一集",
                        enabled = playlistIndex >= 0 && playlistIndex < playlist.lastIndex,
                        onClick = { playAt(playlistIndex + 1) },
                    ) {
                        drawSkip(next = true)
                    }
                }
            }
        }

        error?.let {
            Text(
                "播放失败：$it\n（可在 ⋮ 用其他应用打开，或先复制到本地）",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

// --------------------------------------------------------------------------- 矢量图标

/** 图标按钮：固定触控尺寸 + 矢量绘制内容（禁用整体降透明度） */
@Composable
private fun IconButton(label: String, enabled: Boolean = true, large: Boolean = false, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(
        Modifier
            .size(if (large) 56.dp else 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(if (large) 12.dp else 10.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.35f),
        ) { draw() }
    }
}

/** 播放 ▲ */
private fun DrawScope.drawPlay() {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.28f, h * 0.16f)
        lineTo(w * 0.86f, h * 0.5f)
        lineTo(w * 0.28f, h * 0.84f)
        close()
    }
    drawPath(p, Color.White)
}

/** 暂停 ‖ */
private fun DrawScope.drawPause() {
    val w = size.width
    val h = size.height
    val barW = w * 0.22f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.22f, h * 0.16f),
        size = Size(barW, h * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.56f, h * 0.16f),
        size = Size(barW, h * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f),
    )
}

/** 上一集 |◀ / 下一集 ▶| */
private fun DrawScope.drawSkip(next: Boolean) {
    val w = size.width
    val h = size.height
    val tri = Path().apply {
        if (next) {
            moveTo(w * 0.20f, h * 0.18f)
            lineTo(w * 0.72f, h * 0.5f)
            lineTo(w * 0.20f, h * 0.82f)
        } else {
            moveTo(w * 0.80f, h * 0.18f)
            lineTo(w * 0.28f, h * 0.5f)
            lineTo(w * 0.80f, h * 0.82f)
        }
        close()
    }
    drawPath(tri, Color.White)
    val barX = if (next) w * 0.76f else w * 0.16f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(barX, h * 0.20f),
        size = Size(w * 0.09f, h * 0.60f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
    )
}

/** ↺10 / 15↻：圆弧箭头 + 中间数字 */
private fun DrawScope.drawReplay(forward: Boolean, seconds: String) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val inset = w * 0.16f
    val arcSize = Size(w - inset * 2, h - inset * 2)
    val rectTopLeft = Offset(inset, inset)
    val startAngle = if (forward) -50f else 230f
    val sweep = if (forward) 290f else -290f
    drawArc(
        color = Color.White,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = rectTopLeft,
        size = arcSize,
        style = stroke,
    )
    // 箭头（在弧的终点，沿切线方向）
    val cx = inset + arcSize.width / 2f
    val cy = inset + arcSize.height / 2f
    val r = arcSize.width / 2f
    val endAngle = Math.toRadians((startAngle + sweep).toDouble())
    val ex = cx + r * cos(endAngle).toFloat()
    val ey = cy + r * sin(endAngle).toFloat()
    val tangent = endAngle + (if (sweep > 0) Math.PI / 2 else -Math.PI / 2)
    val tx = cos(tangent).toFloat()
    val ty = sin(tangent).toFloat()
    val arrow = Path().apply {
        moveTo(ex + tx * w * 0.14f, ey + ty * h * 0.14f)
        lineTo(ex - ty * w * 0.11f, ey + tx * w * 0.11f)
        lineTo(ex + ty * w * 0.11f, ey - tx * w * 0.11f)
        close()
    }
    drawPath(arrow, Color.White)
    // 数字（居中）
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = w * 0.40f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        seconds,
        cx,
        cy - (paint.descent() + paint.ascent()) / 2f,
        paint,
    )
}

/** 锁 / 开锁 */
private fun DrawScope.drawLock(locked: Boolean) {
    val w = size.width
    val h = size.height
    val body = androidx.compose.ui.geometry.RoundRect(
        left = w * 0.24f, top = h * 0.44f, right = w * 0.76f, bottom = h * 0.88f,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(body.left, body.top),
        size = Size(body.right - body.left, body.bottom - body.top),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
    )
    drawArc(
        color = Color.White,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.34f, h * 0.16f),
        size = Size(w * 0.32f, h * 0.44f),
        style = Stroke(width = w * 0.10f, cap = StrokeCap.Round),
    )
    // 锁孔
    drawCircle(
        color = if (locked) Color(0xFF212121) else Color.Transparent,
        radius = w * 0.05f,
        center = Offset(w * 0.5f, h * 0.63f),
    )
}

/** 喇叭 / 静音 */
private fun DrawScope.drawSpeaker(muted: Boolean) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.12f, h * 0.36f)
        lineTo(w * 0.30f, h * 0.36f)
        lineTo(w * 0.52f, h * 0.18f)
        lineTo(w * 0.52f, h * 0.82f)
        lineTo(w * 0.30f, h * 0.64f)
        lineTo(w * 0.12f, h * 0.64f)
        close()
    }
    drawPath(body, Color.White)
    if (muted) {
        drawLine(
            color = Color.White,
            start = Offset(w * 0.62f, h * 0.34f),
            end = Offset(w * 0.86f, h * 0.66f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.86f, h * 0.34f),
            end = Offset(w * 0.62f, h * 0.66f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
        )
    } else {
        drawArc(
            color = Color.White,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.30f),
            size = Size(w * 0.28f, h * 0.40f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.40f, h * 0.16f),
            size = Size(w * 0.46f, h * 0.68f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
    }
}

/** 右缘音量 / 左缘亮度竖条 */
@Composable
private fun VerticalLevelBar(ratio: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(4.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((148 * ratio.coerceIn(0f, 1f)).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
    }
}

/** 秒表格式：m:ss / h:mm:ss */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 把 VFS URI 编码成 Media3 可用的 Uri（自定义 panelfm:// 方案由 VfsDataSource 解回） */
fun mediaUriFor(vfsUri: VfsUri): Uri =
    Uri.parse("panelfm://vfs?u=" + URLEncoder.encode(vfsUri.toString(), "UTF-8"))

/** Media3 数据源工厂：把播放器的读取接到统一 VFS（本地/网络同一套） */
class VfsDataSourceFactory(private val locator: VfsLocator) : DataSource.Factory {
    override fun createDataSource(): DataSource = VfsDataSource(locator)
}

class VfsDataSource(private val locator: VfsLocator) : BaseDataSource(false) {

    private var reader: VfsReader? = null
    private var target: VfsUri? = null
    private var remaining: Long = -1L

    override fun open(dataSpec: DataSpec): Long {
        val encoded = dataSpec.uri.getQueryParameter("u") ?: throw IOException("非法媒体地址")
        val vfsUri = VfsUri.parse(URLDecoder.decode(encoded, "UTF-8"))
        target = vfsUri
        val vfs = locator.find(vfsUri) ?: throw IOException("会话不可用（存储已断开）")
        transferInitializing(dataSpec)
        val r = vfs.openRead(vfsUri, offset = dataSpec.position)
        reader = r
        val total = r.size
        remaining = when {
            dataSpec.length != -1L -> dataSpec.length
            total != null && total > 0 -> (total - dataSpec.position).coerceAtLeast(0)
            else -> -1L
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val want = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        val n = runBlocking { reader!!.read(buffer, offset, want) }
        if (n > 0) {
            if (remaining > 0) remaining -= n
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = target?.let { mediaUriFor(it) }

    override fun close() {
        runBlocking { runCatching { reader?.close() } }
        reader = null
        target = null
        remaining = -1L
        transferEnded()
    }
}
