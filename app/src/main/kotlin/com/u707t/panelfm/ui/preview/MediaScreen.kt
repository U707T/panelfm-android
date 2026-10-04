package com.u707t.panelfm.ui.preview

import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 媒体播放（MT 风格播放器 UI + IRIS 式基础手势）：
 *  - 顶部：← 返回 / 文件名 / ⋮（倍速 · 循环 · 画面比例 · 静音 · 外部应用）
 *  - 底部：进度条（当前 / 总时长）+ ⏮ ↺10 ⏯ 15↻ ⏭（同目录音视频自动成播放列表）
 *  - 手势：单击显隐控制栏（播放中 4s 自动隐藏）；双击左/右侧 ±10s、中间播放暂停；
 *    长按 = 2.0x 倍速（▶▶▶ 2.0X 提示）；横滑 = 拖动进度；竖滑 = 调音量
 *  - 本地 / SFTP / WebDAV / SMB / S3 都走统一 VFS 数据源（可随机读、可拖动）
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
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var menuOpen by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var playlist by remember { mutableStateOf<List<FileMetadata>>(emptyList()) }
    var playlistIndex by remember { mutableStateOf(-1) }

    val isAudioOnly = MimeTypes.kindOf(uri.name.substringAfterLast('.', "")) == MimeTypes.Kind.AUDIO

    // 播放期间屏幕常亮；退出释放播放器
    DisposableEffect(player) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { player.removeListener(listener) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // 状态轮询（250ms）：位置 / 时长 / 播放态
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

    // 控制栏自动隐藏（播放中且没打开菜单）
    LaunchedEffect(controlsVisible, isPlaying, menuOpen) {
        if (controlsVisible && isPlaying && !menuOpen) {
            delay(4000)
            controlsVisible = false
        }
    }
    // 提示自动消失
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(1200)
            hud = null
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

    /** 单击 = 显隐控制栏（等 300ms 确认没有第二击）；双击 = 左/右 ±10s、中间播放暂停 */
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
                if (lastTapAt == now) controlsVisible = !controlsVisible
            }
        }
    }

    // ---- 手势层：单击 / 双击 / 长按倍速 / 横滑进度 / 竖滑音量
    val gestureModifier = Modifier.pointerInput(Unit) {
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val slop = viewConfiguration.touchSlop
            var mode = 0   // 0=未定 1=进度 2=音量
            var boost = false
            var seekTarget = 0L
            var released = false
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
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

                if (mode == 0 && !boost && (abs(dx) > slop || abs(dy) > slop)) {
                    longWatcher.cancel()
                    mode = if (abs(dx) > abs(dy)) 1 else 2
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
                            hud = "音量 ${target * 100 / maxVolume}%"
                            change.consume()
                        }
                    }
                    else -> Unit
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
                        2 -> Unit
                        else -> registerTap(pos.x, size.width)
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
                Text("🎵", fontSize = 72.sp)
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

        // 手势层（覆盖视频区域；控制层在上方优先接收按钮事件）
        Box(Modifier.fillMaxSize().then(gestureModifier))

        if (controlsVisible) {
            // ---- 顶部栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "←",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                Box {
                    Text(
                        "⋮",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
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
                        listOf("适应" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "拉伸" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "裁剪" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM).forEach { (label, m) ->
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

        // ---- 左侧静音键（控制栏可见时）
        if (controlsVisible) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable { muted = !muted },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (muted) "🔇" else "🔊", fontSize = 18.sp)
            }
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

        // ---- HUD 提示（双击 / 音量 / 进度）
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

        // ---- 底部控制栏
        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text(clock(durationMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlButton("⏮", enabled = playlistIndex > 0) { playAt(playlistIndex - 1) }
                    CtrlButton("↺10") { seekBy(-10_000) }
                    CtrlButton(if (isPlaying) "⏸" else "▶", large = true) { if (player.isPlaying) player.pause() else player.play() }
                    CtrlButton("15↻") { seekBy(+15_000) }
                    CtrlButton("⏭", enabled = playlistIndex >= 0 && playlistIndex < playlist.lastIndex) { playAt(playlistIndex + 1) }
                }
            }
        }

        error?.let {
            Text(
                "播放失败：$it\n（可尝试「用其他应用打开」或先复制到本地）",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

/** 控制按钮（白色文字，禁用置灰） */
@Composable
private fun CtrlButton(label: String, enabled: Boolean = true, large: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        fontSize = if (large) 26.sp else 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
