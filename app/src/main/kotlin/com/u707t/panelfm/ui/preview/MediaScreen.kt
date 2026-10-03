package com.u707t.panelfm.ui.preview

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 媒体播放（Media3 / ExoPlayer + 统一 VFS 数据源）：
 * 本地、SFTP、WebDAV、SMB、S3 上的音频视频都能直接播放（SMB 局域网视频也能拖动进度）。
 */
@Composable
fun MediaScreen(container: AppContainer, uri: VfsUri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(container.vfsDataSourceFactory)
            )
            .build()
            .apply { setAudioAttributes(audioAttributes, true) }
    }

    DisposableEffect(Unit) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = e.message ?: "播放失败"
            }
        })
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    LaunchedEffect(uri) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(mediaUriFor(uri)))
            player.prepare()
            player.playWhenReady = true
        }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Text(if (player.isPlaying) "暂停" else "播放")
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            error?.let {
                Text(
                    "播放失败：$it\n（可尝试「用其他应用打开」或先复制到本地）",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
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
