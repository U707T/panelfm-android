package com.u707t.panelfm.ui.preview

import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 媒体播放（MT 风格 · 纯悬浮控制层）：
 *  - 顶：← / 文件名 / ⋮（倍速 · 循环 · 画面 · 静音），全部悬浮在画面上（无灰条）
 *  - 底：`当前时间 ——— 进度条 ——— 总时长` ＋ 矢量图标控制行：
 *    `⤨  |◀  ↺10  ⏯（居中）  15↻  ▶|  ☰`（7 键均分，播放键正好居中；最右 = 播放列表）
 *  - 播放列表：右侧滑出面板，当前项高亮 + 打开时自动滚到当前项，点按切换
 *  - 左缘：锁定小圆钮；右缘：静音小圆钮；竖滑浮现中央胶囊：右 = 音量 / 左 = 应用内亮度（照 IRIS）
 *  - 手势：单击显隐（播放中 5s 自动隐藏）；双击左右 ±10s、中间播放暂停；长按 2.0x；横滑进度；竖滑音量 / 亮度
 *  - 同目录音视频自动组成播放列表（⏮ ⏭）
 *
 * 拆分（2026-10-08 重审 §2）：本文件只保留页面骨架（状态 / 播放器效果 / 装配）；
 * 数据源见 [MediaDataSource] 所在的 `MediaDataSource.kt`，控件见 `MediaChrome.kt`，
 * 播放列表见 `MediaPlaylist.kt`，手势见 `MediaGestures.kt`，绘制见 `MediaDraw.kt`。
 */
@androidx.media3.common.util.UnstableApi
@Composable
fun MediaScreen(container: AppContainer, uri: VfsUri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    val audio = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    }

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
    /** 页面交互态（显隐之外的瞬时反馈）——见 [MediaUiState] */
    val ui = remember { MediaUiState() }
    var controlsVisible by remember { mutableStateOf(true) }
    var userSpeed by remember { mutableStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var menuOpen by remember { mutableStateOf(false) }
    /** 应用内亮度（1 = 原始，越小越暗）。**不改系统/窗口亮度**，只叠一层黑色遮罩。 */
    var appBrightness by remember { mutableStateOf(1f) }
    var playlist by remember { mutableStateOf<List<com.u707t.panelfm.core.vfs.FileMetadata>>(emptyList()) }
    var playlistIndex by remember { mutableStateOf(-1) }
    /** 按文件本来的顺序（关闭随机时恢复它） */
    var orderedPlaylist by remember { mutableStateOf<List<com.u707t.panelfm.core.vfs.FileMetadata>>(emptyList()) }
    /** 随机播放开关（照 IRIS：打开即重新洗牌，关闭恢复原顺序） */
    var shuffle by remember { mutableStateOf(false) }
    /** 已缓冲到的位置（进度条第二层） */
    var bufferedMs by remember { mutableStateOf(0L) }
    /** 播放列表面板（右侧滑出；底栏最右按钮开关） */
    var showPlaylist by remember { mutableStateOf(false) }
    /** 播放切换的**意图令牌**：连点时让异步完成的结果「过期即弃」（2026-10-08 重审 §2 竞态修复） */
    var playToken by remember { mutableStateOf(0) }
    /** 最近一次播放失败的曲目（错误区「重试 / 外部打开」作用对象，而不是打开页面时的入参） */
    var failedUri by remember { mutableStateOf<VfsUri?>(null) }

    /**
     * 控制层（顶栏 / 底部控制条）实测高度（px）。
     * 手势层据此避让：触点落在控制层内就**完全不参与**播放器手势，
     * 否则「横向滑动调进度」会和进度条的拖动抢事件（表现就是进度条拖不动）。
     */
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }

    /**
     * 当前正在播放的条目（切歌后要跟着变）。
     *
     * 旧实现直接用**入参** `uri` / `title` 算标题与类型 —— 播放列表在页内切换（点「下一集」）
     * 时入参不会变，于是标题一直停在第一首的文件名、音频/视频判断也跟着错。
     */
    val currentItem = playlist.getOrNull(playlistIndex)
    val displayTitle = currentItem?.name ?: title
    val isAudioOnly = MimeTypes.kindOf(
        (currentItem?.name ?: uri.name).substringAfterLast('.', ""),
    ) == MimeTypes.Kind.AUDIO

    /** 构造可播放的 MediaItem（本地走 file://，其余走 panelfm://） */
    fun itemFor(vfsUri: VfsUri): MediaItem = mediaItemFor(vfsUri, localPathOf(container, vfsUri))

    // 播放期间屏幕常亮；退出释放播放器；亮度改动退出时恢复
    val liveNowPlayingUri by rememberUpdatedState(currentItem?.uri ?: uri)
    DisposableEffect(player) {
        val win = activity?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // 面向用户的文案：Media3 的原始 message 是英文技术细节（如
                // "Source error"），按 errorCode 给出可执行的下一步（与项目里
                // 「错误文案可执行化」的约定一致）
                failedUri = liveNowPlayingUri
                error = describePlaybackError(e)
            }

            // U16：状态级变化改用监听器（旧实现 250ms 无条件轮询一切 → 4Hz 重组）
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                positionMs = player.currentPosition.coerceAtLeast(0)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val d = player.duration
                durationMs = if (d in 1..(24L * 3600 * 1000)) d else 0
                bufferedMs = player.bufferedPosition.coerceAtLeast(0)
                // 复审 F19：READY / ENDED 等状态级变化时也刷新一次位置 ——
                // 只靠「播放中 500ms 轮询」的话，暂停拖动 / 播完时进度条会落后半拍
                positionMs = player.currentPosition.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        onDispose {
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 亮度不再写窗口（旧实现会改 window.attributes.screenBrightness，
            // 用户观感是「改了手机亮度」且退出后若没恢复就残留）。现在纯应用内遮罩，
            // 无需恢复：离开本页即自然消失。
            runCatching { player.removeListener(listener) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // 位置/缓冲轮询：**仅播放中**、500ms 一次（进度条足够顺滑；暂停时不再空转）
    LaunchedEffect(player, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0)
            delay(500)
        }
    }

    LaunchedEffect(ui.speedBoost, userSpeed) { player.setPlaybackSpeed(if (ui.speedBoost) 2f else userSpeed) }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(looping) { player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    // ------------------------------------------------------------------ 控制层显隐
    // 照 IRIS：显隐由明确的 show/hide 驱动，并用**令牌**让自动隐藏在每次唤出时重新计时。
    var controlsToken by remember { mutableStateOf(0) }

    fun showControls() {
        controlsVisible = true
        controlsToken++
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
        if (controlsVisible) controlsToken++
    }

    // 控制栏自动隐藏（照 IRIS：唤出时重新计时；播放中、无交互、无错误才收起）
    // ⚠️ controlsToken 参与 key —— 每次「唤出」都会重置计时器。
    //    旧实现只用 controlsVisible 作 key：已经显示时再唤出不会重置，4 秒后照样消失。
    // ⚠️ error != null 时不隐藏：否则「重试 / 用其他应用打开」按钮 5 秒后就点不到了。
    // ⚠️ 进度拖动中（滑块或横滑手势）不隐藏、不落定 —— 松手后重新计时；
    //    旧实现在拖动中把 seekPreview 落定，会与后续拖动叠加成「超调」（2026-10-08 重审 §2）。
    val progressDragActive = ui.sliderDragging || ui.gestureSeeking
    LaunchedEffect(controlsVisible, controlsToken, isPlaying, menuOpen, showPlaylist, locked, error, progressDragActive) {
        if (controlsVisible && isPlaying && !menuOpen && !showPlaylist && !locked && error == null && !progressDragActive) {
            delay(5000)
            controlsVisible = false
        }
        if (locked) controlsVisible = false
    }

    LaunchedEffect(ui.hud) {
        if (ui.hud != null) {
            delay(1200)
            ui.hud = null
        }
    }
    LaunchedEffect(ui.volumeRatio) {
        if (ui.volumeRatio != null) {
            delay(900)
            ui.volumeRatio = null
        }
    }
    LaunchedEffect(ui.brightnessRatio) {
        if (ui.brightnessRatio != null) {
            delay(900)
            ui.brightnessRatio = null
        }
    }

    /** 切换随机播放：开 → 重新洗牌（当前曲目置顶）；关 → 恢复文件本来顺序。两者都把当前曲目保持在这首 */
    fun toggleShuffle() {
        val current = playlist.getOrNull(playlistIndex)?.uri?.toString()
        val plan = planShuffleToggle(
            isShuffled = shuffle,
            playlist = playlist,
            ordered = orderedPlaylist,
            currentKey = current,
        )
        shuffle = plan.shuffleOn
        playlist = plan.playlist
        playlistIndex = playlist.indexOfFirst { it.uri.toString() == current }
        ui.hud = if (shuffle) "随机播放：开" else "随机播放：关"
    }

    // ------------------------------------------------------------------ 播放切换（意图令牌 + 失败回滚）
    /** 真正启动一次播放（预检 → 装载 → 就绪）；返回错误文案，null = 成功 */
    suspend fun startPlayback(vfsUri: VfsUri): String? {
        preflightMedia(container, vfsUri)?.let { return it }
        return runCatching {
            player.setMediaItem(itemFor(vfsUri))
            player.prepare()
            player.playWhenReady = true
        }.fold(
            onSuccess = { null },
            onFailure = { describePlaybackError(it) },
        )
    }

    /**
     * 播放一个条目；[index] 非空时同步「当前曲目」高亮。
     *
     * 一致性（2026-10-08 重审 §2）：
     *  - 每次调用持一个**意图令牌**（[playToken]），异步完成时若不是最新意图就整体丢弃 ——
     *    连点「下一集」时先发后至的一方不再把播放器切回去（旧实现会「点快了就播错歌」）；
     *  - 失败时回滚 [playlistIndex]（乐观高亮的旧值）并记下 [failedUri]，
     *    让「标题 / 列表高亮 / 实际播放」三方保持一致。
     */
    fun play(vfsUri: VfsUri, index: Int?) {
        val token = ++playToken
        val prevIndex = playlistIndex
        if (index != null) playlistIndex = index
        error = null
        controlsVisible = true
        scope.launch {
            val err = startPlayback(vfsUri)
            if (token != playToken) return@launch   // 已被更新的点击取代：结果整体丢弃
            if (err != null) {
                if (index != null) playlistIndex = prevIndex
                failedUri = vfsUri
                error = err
            } else {
                failedUri = null
            }
        }
    }

    fun playAt(index: Int) {
        val item = playlist.getOrNull(index) ?: return
        play(item.uri, index)
    }

    /** 错误区「重试」：重试**失败的那首**（旧实现恒定重试打开页面时的第一首） */
    fun retryPlayback() {
        val target = failedUri ?: uri
        val idx = playlist.indexOfFirst { it.uri.toString() == target.toString() }
        play(target, if (idx >= 0) idx else null)
    }

    /** 交给系统「查看」应用打开（仅本地文件） */
    fun openExternally(vfsUri: VfsUri) {
        runCatching {
            val path = container.localVfs.absolutePath(vfsUri)
            val targetIsAudio = MimeTypes.kindOf(vfsUri.name.substringAfterLast('.', "")) == MimeTypes.Kind.AUDIO
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        java.io.File(path),
                    ),
                    if (targetIsAudio) "audio/*" else "video/*",
                )
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(intent, "用其他应用打开"))
        }
    }

    // 载入媒体 + 同目录播放列表
    LaunchedEffect(uri) {
        // 先预检（可读性），失败就直接给出可执行文案，不进入黑屏
        // （preflightMedia 内部已处理普通异常；取消异常原样穿透 —— 见 2026-10-08 重审 §2 的「吞取消」清理）
        val pre = preflightMedia(container, uri)
        if (pre != null) {
            failedUri = uri
            error = pre
        } else {
            runCatching {
                player.setMediaItem(itemFor(uri))
                player.prepare()
                player.playWhenReady = true
            }.onFailure {
                failedUri = uri
                error = describePlaybackError(it)
            }
        }
        showControls()
        val parent = uri.parent
        if (parent != null) {
            try {
                val media = loadSiblingMedia(container, parent)
                orderedPlaylist = media
                // 若开着随机，切换文件后仍按随机顺序播放（当前曲目置顶）
                playlist = if (shuffle) shuffledFrom(media, uri.toString()) else media
                playlistIndex = playlist.indexOfFirst { it.uri.toString() == uri.toString() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 目录读取失败：保持现状（播放器本身仍可用，不把失败伪装成「空目录」）
            }
        }
    }

    // ------------------------------------------------------------------ 手势层（见 MediaGestures.kt）
    val gestureHooks = MediaGestureHooks(
        player = player,
        scope = scope,
        audio = audio,
        isLocked = { locked },
        isControlsVisible = { controlsVisible },
        topBarHeightPx = { topBarHeightPx },
        bottomBarHeightPx = { bottomBarHeightPx },
        showControls = { showControls() },
        toggleControls = { toggleControls() },
        setSpeedBoost = { ui.speedBoost = it },
        setHud = { ui.hud = it },
        setSeekPreview = { ui.seekPreview = it },
        setPositionMs = { positionMs = it },
        setVolumeRatio = { ui.volumeRatio = it },
        setBrightnessRatio = { ui.brightnessRatio = it },
        getBrightness = { appBrightness },
        setBrightness = { appBrightness = it },
        getDurationMs = { durationMs },
        setMuted = { muted = it },
        setGestureSeeking = { ui.gestureSeeking = it },
    )
    val gestureModifier = rememberMediaPlayerGestures(gestureHooks)

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
                    displayTitle,
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

        // 应用内亮度遮罩：叠在画面上、控件之下。
        // 无 pointerInput 的 Box 不参与事件消费 → 不会挡住下面的手势层。
        if (appBrightness < 0.999f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (1f - appBrightness) * 0.85f)),
            )
        }

        // ---- 顶部：← / 文件名 / ⋮（悬浮，无底条；见 MediaChrome.kt）
        if (controlsVisible && !locked) {
            MediaTopBar(
                title = displayTitle,
                onBack = onBack,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                userSpeed = userSpeed,
                onSpeedChange = { userSpeed = it },
                looping = looping,
                onToggleLoop = { looping = !looping },
                resizeMode = resizeMode,
                onResizeModeChange = { resizeMode = it },
                muted = muted,
                onToggleMute = { muted = !muted },
                modifier = Modifier.onGloballyPositioned { topBarHeightPx = it.size.height },
            )
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

        // ---- 音量（右滑）/ 亮度（左滑）：中央悬浮胶囊（照 IRIS：图标 + 100×4dp 横条）
        ui.volumeRatio?.let { r ->
            LevelPanel(
                ratio = r,
                kind = LevelKind.VOLUME,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ui.brightnessRatio?.let { r ->
            LevelPanel(
                ratio = r,
                kind = LevelKind.BRIGHTNESS,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ---- 倍速提示（▶▶▶ 2.0X）
        if (ui.speedBoost) {
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
        ui.hud?.let {
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
            LaunchedEffect(locked) { ui.hud = "已锁定，点左侧锁按钮解锁" }
        }

        // ---- 迷你进度浮层（控制层收起时；见 MediaChrome.kt）
        if (!controlsVisible && !locked && durationMs > 0 && ui.hud != null) {
            MediaMiniProgress(
                title = displayTitle,
                positionMs = positionMs,
                seekPreview = ui.seekPreview,
                durationMs = durationMs,
            )
        }

        // ---- 底部：进度行 + 矢量图标控制行（悬浮；见 MediaChrome.kt）
        if (controlsVisible && !locked) {
            MediaBottomBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                seekPreview = ui.seekPreview,
                sliderDragging = ui.sliderDragging,
                isPlaying = isPlaying,
                shuffle = shuffle,
                playingIndex = playlistIndex,
                playlistSize = playlist.size,
                onSeekStart = {
                    ui.wasPlayingBeforeSeek = player.isPlaying
                    if (ui.wasPlayingBeforeSeek) player.pause()
                    ui.sliderDragging = true
                },
                onSeek = { target ->
                    ui.seekPreview = target
                    player.seekTo(target)
                    positionMs = target
                },
                onSeekEnd = {
                    ui.seekPreview?.let {
                        player.seekTo(it)
                        positionMs = it
                    }
                    ui.seekPreview = null
                    ui.sliderDragging = false
                    if (ui.wasPlayingBeforeSeek) player.play()
                },
                onToggleShuffle = { toggleShuffle() },
                onPrev = { playAt(playlistIndex - 1) },
                onNext = { playAt(playlistIndex + 1) },
                onSeekBack = { seekPlayerBy(player, durationMs, -10_000) },
                onSeekForward = { seekPlayerBy(player, durationMs, +15_000) },
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onTogglePlaylist = { showPlaylist = !showPlaylist },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { bottomBarHeightPx = it.size.height },
            )
        }

        // ---- 播放列表面板（右侧滑出；见 MediaPlaylist.kt）
        if (showPlaylist && playlist.isNotEmpty()) {
            MediaPlaylistPanel(
                playlist = playlist,
                currentIndex = playlistIndex,
                onPick = { index ->
                    playAt(index)
                    showPlaylist = false
                },
                onClose = { showPlaylist = false },
            )
        }

        // ---- 播放失败面板（重试 / 外部打开；见 MediaChrome.kt）
        error?.let { msg ->
            val externalTarget = failedUri ?: uri
            MediaErrorPanel(
                message = msg,
                onRetry = { retryPlayback() },
                onOpenExternal = if (externalTarget.scheme == "local") {
                    { openExternally(externalTarget) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * 页面交互态（瞬时反馈类）：从拆分前散落的 29 个 `mutableStateOf` 中收敛出的高内聚分组
 * （2026-10-08 重审 §2）。全部字段背后都是 `mutableStateOf` → 读取即订阅，语义不变。
 */
@Stable
internal class MediaUiState {
    /** 中央 HUD 提示文本（拖动进度 / 双击 / 随机开关等） */
    var hud by mutableStateOf<String?>(null)

    /** 音量浮层（null = 不显示） */
    var volumeRatio by mutableStateOf<Float?>(null)

    /** 亮度浮层（null = 不显示） */
    var brightnessRatio by mutableStateOf<Float?>(null)

    /** 拖动 / 横滑进度的预览位置（null = 未在拖动） */
    var seekPreview by mutableStateOf<Long?>(null)

    /** 进度条（滑块）拖动中 */
    var sliderDragging by mutableStateOf(false)

    /** 滑块拖动之前是否在播放（松手据此续播） */
    var wasPlayingBeforeSeek by mutableStateOf(false)

    /** 横滑进度进行中（与控制层自动隐藏联动） */
    var gestureSeeking by mutableStateOf(false)

    /** 长按 2 倍速生效中 */
    var speedBoost by mutableStateOf(false)
}
