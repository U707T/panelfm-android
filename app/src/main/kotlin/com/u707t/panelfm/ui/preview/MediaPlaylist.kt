package com.u707t.panelfm.ui.preview

// ================================================================================================
// MediaScreen 拆分（2026-10-08 重审 §2）：播放列表域
//  - shuffledFrom / planShuffleToggle：随机播放的**纯函数**（MediaPlaylistTest 直接锁行为）
//  - loadSiblingMedia：同目录音视频列表（播放列表数据源）
//  - MediaPlaylistPanel：右侧滑出面板（当前项高亮 + 打开时自动滚到当前项）
// ================================================================================================

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.ui.browser.clickableNoRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 以 [current] 为第一首、其余随机打乱（照 IRIS：打开随机立刻重新洗牌，
 * 当前曲目排在最前，之后按这份顺序把整个列表播完）。
 *
 * 纯函数：随机性只影响「其余」的顺序，当前曲目恒在首位（[current] 不在列表时则无置顶项）。
 */
internal fun shuffledFrom(list: List<FileMetadata>, current: String?): List<FileMetadata> {
    val head = list.firstOrNull { it.uri.toString() == current }
    val rest = list.filter { it.uri.toString() != current }.shuffled()
    return listOfNotNull(head) + rest
}

/** [planShuffleToggle] 的结果：新开关 + 新列表（列表首项恒为当前曲目） */
internal data class ShuffleTogglePlan(val shuffleOn: Boolean, val playlist: List<FileMetadata>)

/**
 * 「随机播放」开关的**纯计算**（切换逻辑从 UI 状态里提出来，便于单测）：
 *  - 开 → 洗牌（当前曲目置顶）；关 → 恢复文件本来的顺序；
 *  - [ordered] 为空（列表尚未加载完等）时退回当前列表。
 */
internal fun planShuffleToggle(
    isShuffled: Boolean,
    playlist: List<FileMetadata>,
    ordered: List<FileMetadata>,
    currentKey: String?,
): ShuffleTogglePlan {
    val base = ordered.ifEmpty { playlist }
    return if (isShuffled) {
        ShuffleTogglePlan(shuffleOn = false, playlist = base)
    } else {
        ShuffleTogglePlan(shuffleOn = true, playlist = shuffledFrom(base, currentKey))
    }
}

/**
 * 同目录的音频 / 视频列表（播放列表的数据源）。
 *
 * 会话不可用 / 列表失败时**向上抛**（由调用方决定「保持现状」）——不在这里吞异常，
 * 免得把「目录读失败」伪装成「空目录」。
 */
internal suspend fun loadSiblingMedia(container: AppContainer, parent: VfsUri): List<FileMetadata> {
    val vfs = container.resolveSession(parent) ?: return emptyList()
    return withContext(Dispatchers.IO) {
        vfs.list(parent).filter {
            !it.isDirectory &&
                MimeTypes.kindOf(it.extension) in listOf(MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO)
        }
    }
}

/**
 * 播放列表面板（右侧滑出；当前项高亮 + 打开时自动滚到当前项）。
 *
 * 调用方负责挂载条件（`showPlaylist && playlist.isNotEmpty()`）；
 * [onPick] 由调用方决定「切歌 + 关闭面板」的完整动作。
 */
@Composable
internal fun MediaPlaylistPanel(
    playlist: List<FileMetadata>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 遮罩：点空白关闭（无涟漪）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickableNoRipple(onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.86f)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(topStart = MtSpec.CornerLarge, bottomStart = MtSpec.CornerLarge))
                .background(Color(0xF0121212))
                // 面板空白处吞掉点击，避免落到手势层误切换控制层
                .clickableNoRipple { }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("播放列表", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (currentIndex in playlist.indices) "${currentIndex + 1} / ${playlist.size}" else "${playlist.size} 个",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                MtIconButton(
                    icon = MtIcon.CLOSE,
                    contentDescription = "关闭播放列表",
                    tint = Color.White,
                    onClick = onClose,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                // 打开时滚到当前项附近（前留两行），长列表不用手动找
                if (currentIndex > 0) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(playlist) { index, item ->
                    val current = index == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = if (current) MtSpec.AccentDark else Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(26.dp),
                        )
                        Text(
                            item.name,
                            color = if (current) MtSpec.AccentDark else Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) {
                            Canvas(
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(10.dp),
                            ) {
                                drawPath(
                                    Path().apply {
                                        moveTo(size.width * 0.06f, size.height * 0.10f)
                                        lineTo(size.width * 0.94f, size.height * 0.50f)
                                        lineTo(size.width * 0.06f, size.height * 0.90f)
                                        close()
                                    },
                                    MtSpec.AccentDark,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
