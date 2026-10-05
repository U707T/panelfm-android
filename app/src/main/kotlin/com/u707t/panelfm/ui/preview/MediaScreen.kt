package com.u707t.panelfm.ui.preview

import androidx.core.net.toUri
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
import androidx.compose.material3.TextButton
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
@androidx.media3.common.util.UnstableApi
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

    /**
     * 该 VFS URI 对应的**本地绝对路径**（仅当文件真的在本机磁盘上时返回）。
     *
     * 本地文件走 file:// 交给 Media3 原生读取；网络 / 压缩包内返回 null（走 panelfm://）。
     */
    fun localPathOf(vfsUri: VfsUri): String? {
        if (vfsUri.scheme != "local") return null
        val path = runCatching { container.localVfs.absolutePath(vfsUri) }.getOrNull() ?: return null
        return if (java.io.File(path).isFile) path else null
    }

    /** 构造可播放的 MediaItem（本地走 file://，其余走 panelfm://） */
    fun itemFor(vfsUri: VfsUri): MediaItem = mediaItemFor(vfsUri, localPathOf(vfsUri))

    /**
     * 播放前的**可读性预检**：能 stat 到、能读出第一个字节。
     *
     * 为什么需要：播放器失败时用户只看到黑屏，没有任何线索。
     * 预检能在 prepare 之前把「文件不存在 / 没权限 / 会话断开」直接变成可执行文案。
     */
    suspend fun preflight(vfsUri: VfsUri): String? = withContext(Dispatchers.IO) {
        try {
            val vfs = container.locator.find(vfsUri) ?: return@withContext "存储会话不可用（可能已断开，请重新打开该存储）"
            val meta = vfs.stat(vfsUri)
            if (meta.isDirectory) return@withContext "这是一个文件夹，不是媒体文件"
            if (meta.size == 0L) return@withContext "文件为空（0 字节）"
            vfs.openRead(vfsUri, offset = 0, length = 1).use { reader ->
                val buf = ByteArray(1)
                val n = reader.read(buf, 0, 1)
                if (n <= 0) return@withContext "无法读取文件内容（权限不足或文件已损坏）"
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            (e as? com.u707t.panelfm.core.vfs.VfsException)?.userMessage ?: "无法访问文件：${e.message ?: "未知错误"}"
        }
    }

    // 播放期间屏幕常亮；退出释放播放器；亮度改动退出时恢复
    DisposableEffect(player) {
        val win = activity?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 记录进入时的亮度（-1 = 跟随系统），退出时恢复——否则用户调过亮度后系统亮度被永久改变
        val originalBrightness = win?.attributes?.screenBrightness ?: -1f
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // 面向用户的文案：Media3 的原始 message 是英文技术细节（如
                // "Source error"），按 errorCode 给出可执行的下一步（与项目里
                // 「错误文案可执行化」的约定一致）
                error = describePlaybackError(e)
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
    // ⚠️ error != null 时不隐藏：否则「重试 / 用其他应用打开」按钮 4 秒后就点不到了
    LaunchedEffect(controlsVisible, isPlaying, menuOpen, locked, error) {
        if (controlsVisible && isPlaying && !menuOpen && !locked && error == null) {
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
        scope.launch {
            preflight(item.uri)?.let { error = it; return@launch }
            player.setMediaItem(itemFor(item.uri))
            player.prepare()
            player.playWhenReady = true
        }
        controlsVisible = true
    }

    // 载入媒体 + 同目录播放列表
    LaunchedEffect(uri) {
        // 先预检（可读性），失败就直接给出可执行文案，不进入黑屏
        val pre = runCatching { preflight(uri) }.getOrNull()
        if (pre != null) {
            error = pre
        } else {
            runCatching {
                player.setMediaItem(itemFor(uri))
                player.prepare()
                player.playWhenReady = true
            }.onFailure { error = describePlaybackError(it) }
        }
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

        error?.let { msg ->
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
                    msg,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // 可执行出口：重试 / 交给系统应用（本地文件）
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        error = null
                        scope.launch {
                            preflight(uri)?.let { error = it; return@launch }
                            runCatching {
                                player.setMediaItem(itemFor(uri))
                                player.prepare()
                                player.playWhenReady = true
                            }.onFailure { error = describePlaybackError(it) }
                        }
                    }) { Text("重试", color = Color.White) }
                    if (uri.scheme == "local") {
                        TextButton(onClick = {
                            runCatching {
                                val path = container.localVfs.absolutePath(uri)
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(
                                        androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            java.io.File(path),
                                        ),
                                        if (isAudioOnly) "audio/*" else "video/*",
                                    )
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(android.content.Intent.createChooser(intent, "用其他应用打开"))
                            }
                        }) { Text("用其他应用打开", color = Color.White) }
                    }
                }
            }
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

/**
 * 把 Media3 的 [PlaybackException] 翻译成**可执行的中文提示**。
 *
 * Media3 原始 message 是英文技术细节（`Source error` / `Decoder init failed` …），
 * 对用户没有指导意义。这里按 [PlaybackException.errorCode] 给出下一步动作，
 * 并在末尾附上简短原因，便于用户截图反馈。
 */
fun describePlaybackError(e: Throwable): String {
    if (e !is PlaybackException) {
        return "无法开始播放：${e.message ?: e::class.java.simpleName}"
    }
    val code = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在（可能已被移动或删除）"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取权限（可能需要「所有文件访问」授权）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败（检查存储是否在线）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络超时（存储响应过慢或已离线）"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误状态（存储端可能拒绝访问）"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "读取失败（存储可能已断开）"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "文件已损坏（容器格式不完整）"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的封装格式（可试试「其他应用打开」）"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "清单文件已损坏"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "不支持的流媒体清单"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败（可能是编码格式不支持）"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "设备缺少可用的解码器"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败（文件可能损坏或编码异常）"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "不支持的编码格式（如部分 HEVC / AV1）"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频轨初始化失败"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频输出失败"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "设备解码能力不足（如 4K / 高码率）"
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "系统回收了解码器（可重试）"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "服务器返回的内容类型不合法"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许明文 HTTP（请用 https）"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "读取位置越界（文件可能被截断）"
        PlaybackException.ERROR_CODE_TIMEOUT -> "操作超时"
        PlaybackException.ERROR_CODE_PERMISSION_DENIED -> "没有权限"
        PlaybackException.ERROR_CODE_NOT_SUPPORTED -> "当前播放器不支持该内容"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        -> "DRM 保护内容无法播放"
        else -> "播放失败"
    }
    val reason = e.cause?.message ?: e.message
    return if (reason.isNullOrBlank()) code else "$code\n（$reason）"
}

/**
 * 构造 Media3 的 [MediaItem]：**同时给出真实文件名后缀与 mimeType**。
 *
 * 为什么两者都要：
 *  - path 里的后缀 → `Util.inferContentType` 判容器（m3u8/mpd/其它）
 *  - `setMimeType` → `DefaultMediaSourceFactory` 用它选 Extractor / 渲染器，
 *    并在 `inferContentTypeForUriAndMimeType` 里优先于后缀
 *
 * 只给其中任一都可能让 ExoPlayer 选错（或选不到）提取器 → 黑屏 / 播放失败。
 */
fun mediaItemFor(vfsUri: VfsUri, localPath: String? = null): MediaItem {
    // 本地文件优先走 **file://**：由 Media3 自带的 FileDataSource 直接读，
    // 完全绕开自定义 scheme + VfsDataSource + runBlocking 那一整条链路
    // （本地是最常见的场景，少一层就少一类失败可能）。
    // 网络 / 压缩包内 / 无本地路径时仍走 panelfm://。
    val uri = if (localPath != null) Uri.fromFile(java.io.File(localPath)) else mediaUriFor(vfsUri)
    val builder = MediaItem.Builder().setUri(uri)
    mimeTypeForName(vfsUri.name)?.let { builder.setMimeType(it) }
    return builder.build()
}

/**
 * 按扩展名给 MIME（只覆盖常见容器；未命中返回 null 交给 Media3 自己按后缀推断）。
 *
 * 注意 mp4 家族要区分：`.mp4` 是 `video/mp4`，`.m4a` 是 `audio/mp4`，
 * 混用会让音频轨的渲染器选择偏掉。
 */
fun mimeTypeForName(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> null
    }

/** 播放地址的 scheme / authority（`panelfm://vfs/...`） */
const val MEDIA_SCHEME = "panelfm"
const val MEDIA_AUTHORITY = "vfs"

/** query 里放完整 VFS URI 的参数名 */
const val MEDIA_PARAM_VFS = "u"

/**
 * 构造播放地址字符串（**纯函数，便于单测**；[mediaUriFor] 只是把它包成 `Uri`）。
 *
 * ## ⚠️ 路径里必须保留真实文件名（不能只放 query）
 *
 * Media3 用 `Uri.getLastPathSegment()` 的后缀来推断容器类型
 * （`Util.inferContentType` → m3u8/mpd/其它）。
 * 如果写成 `panelfm://vfs?u=...`，`getLastPathSegment()` 拿到的是 authority `vfs`
 * （没有点号）→ 类型恒为 `CONTENT_TYPE_OTHER`，
 * 且 `MediaItem.fromUri` 的 mimeType 为 null，**部分容器的 Extractor 选不出来 → 播放失败**。
 *
 * 所以把**真实文件名**放在 path 末尾，query 里再放编码后的完整 VFS URI：
 *   `panelfm://vfs/movie.mp4?u=local%3A%2F%2Femulated%2FDownload%2Fmovie.mp4`
 * 这样 `inferContentType` 能按 `.mp4` / `.mkv` / `.m3u8` 正确判型。
 */
fun mediaUriString(vfsUri: VfsUri): String =
    "$MEDIA_SCHEME://$MEDIA_AUTHORITY/${percentEncode(vfsUri.name.ifBlank { "media" })}" +
        "?$MEDIA_PARAM_VFS=${percentEncode(vfsUri.toString())}"

/**
 * 从播放地址解回 VFS URI（**纯函数**；[VfsDataSource] 用它）。
 *
 * 只认 `panelfm://vfs/...?u=...`；`u` 缺失或解析失败返回 null。
 */
fun vfsUriFromMediaUri(mediaUri: String): VfsUri? {
    val marker = "?$MEDIA_PARAM_VFS="
    val idx = mediaUri.indexOf(marker)
    if (idx < 0) return null
    val raw = mediaUri.substring(idx + marker.length).substringBefore('&')
    if (raw.isEmpty()) return null
    return runCatching { VfsUri.parse(percentDecode(raw)) }.getOrNull()
}

/**
 * 百分号编码（与 Android `Uri.getQueryParameter` / `getLastPathSegment` 的解码规则对齐）。
 *
 * `URLEncoder` 会把空格编成 `+`，而 Android 的 `Uri.decode` **不会**把 `+` 当空格，
 * 所以必须再把 `+` 换成 `%20`，否则「我的 视频.mp4」这类文件名会带出 `+`。
 */
internal fun percentEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** 百分号解码（`+` 不当空格，与上面成对） */
internal fun percentDecode(value: String): String =
    java.net.URLDecoder.decode(value, "UTF-8")

/** 把 VFS URI 编码成 Media3 可用的 Uri（自定义 `panelfm://` 方案由 [VfsDataSource] 解回）。 */
fun mediaUriFor(vfsUri: VfsUri): Uri = mediaUriString(vfsUri).toUri()

/**
 * Media3 数据源工厂：**通用**（`panelfm://` 走统一 VFS，标准 scheme 交给 Media3 自带数据源）。
 *
 * ## 为什么必须同时支持标准 scheme
 *
 * `DefaultMediaSourceFactory(DataSource.Factory)` 会把**唯一**这个工厂用于所有请求。
 * 如果只认 `panelfm://`，那么本地文件走 `file://`（`Uri.fromFile`）时
 * 就会在 `open()` 里抛「非法媒体地址」—— 看起来像「视频无法播放」。
 * 所以这里对非 `panelfm://` 的请求**委托**给 `DefaultDataSource`
 * （它内部按 scheme 分派：file / content / asset / http(s) / data …）。
 */
@androidx.media3.common.util.UnstableApi
class VfsDataSourceFactory(
    private val locator: VfsLocator,
    context: android.content.Context,
) : DataSource.Factory {

    /** Media3 自带的分派数据源（file:// / content:// / http(s):// …） */
    private val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource =
        VfsDataSource(locator, defaultFactory.createDataSource())
}

/**
 * Media3 数据源：把播放器的读取接到统一 VFS（本地 / SFTP / WebDAV / SMB / S3 通吃）。
 *
 * [BaseDataSource] 的 `isNetwork` 参数影响 Media3 的**加载线程与重试策略**：
 * 网络数据源会走更宽松的超时与重试。这里传 `true`（保守取值）：
 * 同一套代码既要读本地也要读网络，标成网络只是让 Media3 用更宽容的策略，本地读取不受影响。
 */
@androidx.media3.common.util.UnstableApi
class VfsDataSource(
    private val locator: VfsLocator,
    /**
     * 标准 scheme（file / content / http(s) …）的委托数据源。
     * 见 [VfsDataSourceFactory] 的说明：工厂是唯一的，必须能处理所有 scheme。
     */
    private val delegate: DataSource? = null,
) : BaseDataSource(true) {

    private var reader: VfsReader? = null
    private var target: VfsUri? = null
    private var remaining: Long = -1L

    /** 本次 open 是否交给了 [delegate]（close / read 也要跟着走） */
    private var delegated = false

    /**
     * 是否已经 `transferStarted`。
     *
     * [BaseDataSource] 的事件必须**严格成对**：`transferInitializing` → `transferStarted`
     * → `bytesTransferred` → `transferEnded`。
     * 早期实现在 `open()` 里先 `transferInitializing` 再 `openRead`，
     * 如果 `openRead` 抛异常（文件被删 / 权限不足 / 会话断开），`transferStarted` 就不会执行，
     * 但 `close()` 仍会调 `transferEnded` —— 事件不成对，
     * Media3 的 `TransferListener`（含我们注册的统计）会记出负数/错乱。
     */
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        // 用纯函数解回（与 mediaUriString 成对，规则写在一处，避免编码/解码不对称）
        val vfsUri = vfsUriFromMediaUri(dataSpec.uri.toString())
        if (vfsUri == null) {
            // 不是 panelfm:// → 标准 scheme，交给 Media3 自带数据源
            val d = delegate ?: throw IOException("不支持的数据源：${dataSpec.uri}")
            delegated = true
            return d.open(dataSpec)
        }
        val vfs = locator.find(vfsUri) ?: throw IOException("会话不可用（存储已断开）")

        transferInitializing(dataSpec)
        // 打开失败时不要把 started 置位：没有 transferStarted 就不能发送 transferEnded。
        // 否则监听器会收到一个没有开始事件的结束事件，进度统计可能变成负数。
        val r = try {
            vfs.openRead(vfsUri, offset = dataSpec.position)
        } catch (e: Exception) {
            reader = null
            target = null
            remaining = -1L
            throw e
        }
        reader = r
        target = vfsUri
        val total = r.size
        remaining = when {
            dataSpec.length != -1L -> dataSpec.length
            total != null && total > 0 -> (total - dataSpec.position).coerceAtLeast(0)
            else -> -1L
        }
        transferStarted(dataSpec)
        started = true
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegated) return delegate!!.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val r = reader ?: return -1
        val want = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        // runBlocking：InputStream 的契约是阻塞式读，调用方（Media3 加载线程）已在非主线程
        val n = runBlocking { r.read(buffer, offset, want) }
        if (n > 0) {
            if (remaining > 0) remaining -= n
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = target?.let { mediaUriFor(it) }

    override fun close() {
        if (delegated) {
            delegated = false
            runCatching { delegate?.close() }
            return
        }
        runBlocking { runCatching { reader?.close() } }
        reader = null
        target = null
        remaining = -1L
        // 只有真正开始过才结束（保证与 transferStarted 成对）
        if (started) {
            started = false
            transferEnded()
        }
    }
}
