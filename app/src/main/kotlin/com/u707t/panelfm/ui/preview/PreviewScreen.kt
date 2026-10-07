package com.u707t.panelfm.ui.preview

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 预览调度：按 MIME / 扩展名分派到 播放器 / 编辑器 / 字体 / 图片 / 文本。
 *  - 播放器、编辑器、字体预览自带整屏界面（整页接管，不再叠加外壳）
 *  - 顶栏 ⋮ 菜单按文件类型给项（文本/代码/未识别 → 文本+编辑；字体/PDF/APK → 各自入口；本地文件 → 外部应用）
 */
@androidx.media3.common.util.UnstableApi
@Composable
fun PreviewScreen(container: AppContainer, request: PreviewRequest, onBack: () -> Unit) {
    val uri = request.uri
    val context = LocalContext.current
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var effective by remember { mutableStateOf(request.mode) }
    var editing by remember { mutableStateOf(request.mode == PreviewMode.EDITOR) }
    var modeMenu by remember { mutableStateOf(false) }

    // 记录「当前打开着的文件」：进程被杀 / 直接退出后，下次启动自动回到这里。
    // 用户主动返回（onDispose）会清掉这条记录，所以「看完返回列表再退出」不会自动跳回。
    LaunchedEffect(uri) {
        runCatching { container.prefs.saveLastOpenedPreview(request.mode.name, uri.toString()) }
    }
    androidx.compose.runtime.DisposableEffect(uri) {
        onDispose {
            runCatching {
                container.scope.launch { container.prefs.clearLastOpenedPreview() }
            }
        }
    }

    LaunchedEffect(uri) {
        try {
            // resolveSession：会话被回收 / URI 丢过连接号时自动重连，避免整页变成
            // 「会话不可用」而用户什么都没法做
            val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
            meta = vfs.stat(uri)
        } catch (e: Exception) {
            error = (e as? com.u707t.panelfm.core.vfs.VfsException)?.userMessage ?: (e.message ?: "读取失败")
        }
    }

    val item = meta
    val resolved: PreviewMode? = if (item != null && !item.isDirectory) {
        var r = effective
        if (editing) r = PreviewMode.EDITOR
        if (r == PreviewMode.AUTO) {
            val kind = MimeTypes.kindOf(item.extension)
            r = when (kind) {
                MimeTypes.Kind.IMAGE -> PreviewMode.IMAGE
                MimeTypes.Kind.AUDIO, MimeTypes.Kind.VIDEO -> PreviewMode.MEDIA
                MimeTypes.Kind.FONT -> PreviewMode.FONT
                // MT 的 PDF 走内置查看器；PanelFM 用系统 PdfRenderer（只读，非逆向）
                MimeTypes.Kind.PDF -> PreviewMode.PDF
                // APK：只读信息（PackageManager 解析），不做 dex/arsc 编辑
                MimeTypes.Kind.APK -> PreviewMode.APK_INFO
                MimeTypes.Kind.TEXT, MimeTypes.Kind.CODE -> PreviewMode.TEXT
                // 未识别格式：一律文本预览（最多读前 1 MB，界面会标注「已截断」）
                else -> PreviewMode.TEXT
            }
        }
        r
    } else null
    // 独立整屏界面（自带顶栏）：播放器 / 编辑器 / 字体预览
    if (item != null && resolved != null) {
        when (resolved) {
            PreviewMode.MEDIA -> {
                MediaScreen(container, item.uri, item.name, onBack = onBack)
                return
            }
            PreviewMode.EDITOR -> {
                com.u707t.panelfm.ui.editor.EditorScreen(container, item.uri, onBack = onBack)
                return
            }
            PreviewMode.FONT -> {
                FontScreen(container, item.uri, onBack = onBack)
                return
            }
            PreviewMode.PDF -> {
                PdfScreen(container, item, onBack = onBack)
                return
            }
            PreviewMode.APK_INFO -> {
                ApkInfoScreen(container, item, onBack = onBack)
                return
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        // 顶栏：← 返回 · 文件名（单行省略，不会被按钮挤成竖排）· ⋮
        MtScreenTopBar(
            title = meta?.name ?: uri.name,
            onBack = onBack,
        ) {
            Box {
                MtIconButton(
                    icon = MtIcon.MORE,
                    contentDescription = "更多菜单",
                    onClick = { modeMenu = true },
                )
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    // 菜单按类型给项：文本/代码/未识别 → 文本 + 编辑；字体/PDF/APK → 各自入口；
                    // 本地文件才有「外部应用」。媒体/图片/压缩包在当前查看器里即可，不再堆无关入口。
                    val kind = item?.extension?.let { MimeTypes.kindOf(it) }
                    val textLike = kind == null ||
                        kind == MimeTypes.Kind.TEXT || kind == MimeTypes.Kind.CODE || kind == MimeTypes.Kind.OTHER
                    if (textLike) {
                        DropdownMenuItem(
                            text = { Text("文本", color = if (!editing && resolved == PreviewMode.TEXT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { effective = PreviewMode.TEXT; editing = false; modeMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("编辑", color = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { editing = true; modeMenu = false },
                        )
                    }
                    if (kind == MimeTypes.Kind.FONT) {
                        DropdownMenuItem(
                            text = { Text("字体") },
                            onClick = { effective = PreviewMode.FONT; editing = false; modeMenu = false },
                        )
                    }
                    if (kind == MimeTypes.Kind.PDF) {
                        DropdownMenuItem(
                            text = { Text("PDF") },
                            onClick = { effective = PreviewMode.PDF; editing = false; modeMenu = false },
                        )
                    }
                    if (kind == MimeTypes.Kind.APK) {
                        DropdownMenuItem(
                            text = { Text("APK 信息") },
                            onClick = { effective = PreviewMode.APK_INFO; editing = false; modeMenu = false },
                        )
                    }
                    if (uri.scheme == "local") {
                        DropdownMenuItem(
                            text = { Text("外部应用") },
                            onClick = {
                            modeMenu = false
                            val file = runCatching { File(container.localVfs.absolutePath(uri)) }.getOrNull()
                            if (uri.scheme != "local") {
                                error = "网络文件不支持外部应用打开，请先复制到本地"
                            } else if (file == null || !file.exists()) {
                                error = "文件不存在（可能已被移动或删除）"
                            } else {
                                val shareUri = runCatching {
                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                }.getOrNull()
                                if (shareUri == null) {
                                    error = "无法生成打开链接"
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(shareUri, meta?.mimeType ?: "*/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    // 旧实现失败时静默（用户以为点了没反应）
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { error = "没有可用的应用打开该文件类型" }
                                }
                            }
                        },
                        )
                    }
                }
            }
        }

        when {
            error != null -> ErrorState(error!!)
            item == null -> LoadingState()
            item.isDirectory -> Text("这是一个文件夹：${uri.displayPath}", Modifier.padding(16.dp))
            else -> when (resolved) {
                PreviewMode.IMAGE -> ImagePreview(container, item)
                PreviewMode.PDF -> PdfScreen(container, item, onBack = onBack)
                PreviewMode.ARCHIVE -> Text("压缩包：请返回列表后点击它进入内部浏览", Modifier.padding(16.dp))
                PreviewMode.SYSTEM -> Text("已交给系统应用打开（若未弹出，请检查是否有可用应用）", Modifier.padding(16.dp))
                // 文本 / 未识别 → 文本预览（未识别类型走通用兜底）
                else -> TextPreview(container, item)
            }
        }
    }
}

private const val MAX_TEXT_SIZE = 1L * 1024 * 1024
private const val MAX_IMAGE_SIZE = 32L * 1024 * 1024

/**
 * 图片预览（复刻 MT）：
 *  - **同目录图片组成播放列表：左右滑动切换上一张 / 下一张**（MT 同款）
 *  - 双击切换 1x / 2.5x，捏合缩放（1–5x），双指拖动平移；放大后暂停翻页
 *  - 大图流式采样解码（不整包进内存）
 */
@Composable
private fun ImagePreview(container: AppContainer, item: FileMetadata) {
    // 同目录图片列表（左右滑动切图用；加载完成前不组装 Pager，避免初始页错位）
    var siblings by remember(item.uri.parent) { mutableStateOf<List<FileMetadata>?>(null) }
    var initialPage by remember(item.uri) { mutableStateOf(0) }

    LaunchedEffect(item.uri) {
        val parent = item.uri.parent
        val list = if (parent == null) emptyList() else runCatching {
            val vfs = container.resolveSession(parent) ?: return@runCatching emptyList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vfs.list(parent).filter {
                    !it.isDirectory && MimeTypes.kindOf(it.extension) == MimeTypes.Kind.IMAGE
                }
            }
        }.getOrDefault(emptyList())
        val effective = if (list.any { it.uri == item.uri }) list else listOf(item)
        siblings = effective
        initialPage = effective.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
    }

    val list = siblings
    if (list == null) {
        LoadingState("正在加载同目录图片…")
        return
    }

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialPage.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
        pageCount = { list.size },
    )
    // 缩放状态按页重置（翻页后回到适应窗口）
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = androidx.compose.ui.geometry.Offset.Zero
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            // 放大状态下禁止翻页（让双指平移 / 拖动查看细节优先）
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ImagePage(
                container = container,
                item = list[page],
                scale = scale,
                offset = offset,
                onScale = { scale = it },
                onOffset = { offset = it },
            )
        }
        // 页码 + 文件名（MT 观感：底部小字）
        if (list.size > 1) {
            Text(
                "${pagerState.currentPage + 1} / ${list.size} · ${list[pagerState.currentPage].name}",
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(androidx.compose.ui.graphics.Color(0x66000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 双击放大的目标倍率 */
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/**
 * 图片查看手势：捏合缩放 + 平移，且**未放大时把单指拖动让给外层 Pager**。
 *
 * 为什么不能直接用 `detectTransformGestures`：它一旦越过 touch slop 就会 consume 事件，
 * 于是「单指横滑切图」永远收不到事件 —— 缩放和翻页互相抢。
 * 这里只在 `zoom != 1`（双指）或「已放大」（需要拖动查看）时消费。
 */
private suspend fun PointerInputScope.detectZoomPan(
    isZoomed: () -> Boolean,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    do {
        val event = awaitPointerEvent()
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (zoom != 1f || isZoomed()) {
            onGesture(pan, zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    } while (event.changes.any { it.pressed })
}

/** 单张图片：双击 1x/2.5x、捏合 1–5x、已放大后拖动查看 */
@Composable
private fun ImagePage(
    container: AppContainer,
    item: FileMetadata,
    scale: Float,
    offset: androidx.compose.ui.geometry.Offset,
    onScale: (Float) -> Unit,
    onOffset: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        try {
            bitmap = decodeSampled(container, item.uri)
        } catch (e: Exception) {
            error = e.message ?: "解码失败"
        }
    }

    when {
        error != null -> ErrorState("图片预览失败：$error")
        bitmap == null -> LoadingState("解码中…")
        else -> {
            val bm = bitmap!!
            // ⚠️ 手势里必须读**当前**值：`pointerInput(bm)` 的 lambda 只在 bm 变化时重建，
            // 直接捕获 scale/offset 参数会永远停在首次组合时的 `1f` / `Zero`。
            // 旧实现就是这个问题：每个缩放事件都拿「1f × 本帧增量」→ 放大看不到、
            // 一松手就弹回 1x（用户观感就是「图片无法放大」）。
            val liveScale by rememberUpdatedState(scale)
            val liveOffset by rememberUpdatedState(offset)
            Box(
                Modifier
                    .fillMaxSize()
                    // 双击：1x ↔ 2.5x
                    .pointerInput(bm) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (liveScale > 1.01f) {
                                    onScale(1f)
                                    onOffset(androidx.compose.ui.geometry.Offset.Zero)
                                } else {
                                    onScale(DOUBLE_TAP_SCALE)
                                }
                            },
                        )
                    }
                    // 捏合缩放 + 平移：未放大时把**单指拖动**让给 HorizontalPager（翻页），
                    // 只在「双指（缩放）」或「已放大后的拖动」时消费事件 ——
                    // 否则缩放和翻页互相抢事件，两个都会失灵。
                    .pointerInput(bm) {
                        detectZoomPan(
                            isZoomed = { liveScale > 1.01f },
                            onGesture = { pan, zoom ->
                                val base = liveScale
                                val next = (base * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                val maxX = (next - 1f) * size.width / 2f
                                val maxY = (next - 1f) * size.height / 2f
                                val moved = androidx.compose.ui.geometry.Offset(
                                    (liveOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (liveOffset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                                onScale(next)
                                // 回到适应窗口时把平移一并归零，避免「缩小后画面偏在角落」
                                onOffset(if (next <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else moved)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

/**
 * 图片解码：流式两遍读（边界 → 采样），不把整包读进 Java 堆 —— 超大图片 / 远程图片也不 OOM；
 * 最长边 ≤ [TARGET_MAX_EDGE] 采样。
 */
private suspend fun decodeSampled(container: AppContainer, uri: VfsUri): android.graphics.Bitmap =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val openStream: () -> java.io.InputStream = when {
            uri.scheme == "local" -> {
                val path = runCatching { container.localVfs.absolutePath(uri) }.getOrNull()
                if (path != null && File(path).exists()) {
                    { java.io.FileInputStream(path) }
                } else {
                    { openVfsStream(container, uri) }
                }
            }
            else -> ({ openVfsStream(container, uri) })
        }
        // 第一遍：读边界（BufferedInputStream 提供 mark/reset，BitmapFactory 需要）
        val (w, h) = openStream().use { s ->
            val b = java.io.BufferedInputStream(s, 64 * 1024)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(b, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                throw IllegalStateException("无法解码图片（不支持的格式或数据损坏）")
            }
            opts.outWidth to opts.outHeight
        }
        // 第二遍：按边界采样解码（流式读入，解码后位图最长边 ≤ 2048px）
        val sample = sampleToFit(w, h, TARGET_MAX_EDGE)
        openStream().use { s ->
            val b = java.io.BufferedInputStream(s, 64 * 1024)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeStream(b, null, opts) ?: throw IllegalStateException("无法解码图片")
        }
    }

/**
 * 把统一 VFS 的顺序读适配成 InputStream（`BitmapFactory.decodeStream` 用）。
 *
 * 说明：
 *  - `read()` 是**阻塞式**的（`InputStream` 的契约如此），调用方已在
 *    `Dispatchers.IO` 上执行，所以这里用 `runBlocking` 把 suspend 的 VFS 读取桥接过来；
 *  - `skip()` **不借用共享缓冲区**：早期实现复用 `buf` 与 `read()` 抢同一块内存，
 *    并发或嵌套调用时会读到脏数据（表现为图片偶发解码失败）；
 *  - 支持随机访问的 VFS 直接用 `seek` 跳过，避免把整段数据读进来丢掉；
 *  - `available()` 返回 `min(剩余, Int.MAX_VALUE)`：部分解码器会用它估算缓冲，
 *    恒返回 0 会让它们退化（虽然多数情况下仍能工作）。
 */
private fun openVfsStream(container: AppContainer, uri: VfsUri): java.io.InputStream {
    val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
    return object : java.io.InputStream() {
        private val reader = vfs.openRead(uri)
        private val total = reader.size
        private var consumed = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = kotlinx.coroutines.runBlocking { reader.read(one, 0, 1) }
            return if (n <= 0) -1 else {
                consumed += n
                one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = kotlinx.coroutines.runBlocking { reader.read(b, off, len) }
            if (n > 0) consumed += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            // 能 seek 的 VFS 直接跳（远程 / 本地都支持），不浪费带宽与内存
            if (reader.supportsSeek) {
                val target = (consumed + n).coerceAtMost(total?.takeIf { it >= 0 } ?: Long.MAX_VALUE)
                val delta = target - consumed
                if (delta <= 0) return 0
                runCatching { kotlinx.coroutines.runBlocking { reader.seek(target) } }
                    .onSuccess { consumed = target; return delta }
            }
            // 兜底：逐块读掉（用**独立**缓冲区，不与 read() 共享）
            val scratch = ByteArray(16 * 1024)
            var left = n
            while (left > 0) {
                val want = minOf(scratch.size.toLong(), left).toInt()
                val got = kotlinx.coroutines.runBlocking { reader.read(scratch, 0, want) }
                if (got <= 0) break
                consumed += got
                left -= got
            }
            return n - left
        }

        override fun available(): Int {
            val t = total ?: return 0
            if (t < 0) return 0
            return (t - consumed).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() = runCatching { reader.close() }.let { Unit }
    }
}

private fun sampleToFit(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

private const val TARGET_MAX_EDGE = 2048

@Composable
private fun TextPreview(container: AppContainer, item: FileMetadata) {
    var text by remember { mutableStateOf<String?>(null) }
    var encoding by remember { mutableStateOf("") }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        try {
            val read = readBytes(container, item.uri, MAX_TEXT_SIZE + 1)
            truncated = read.size > MAX_TEXT_SIZE
            val bytes = if (truncated) read.copyOf(MAX_TEXT_SIZE.toInt()) else read
            val decoded = decodeText(bytes)
            encoding = decoded.first
            text = decoded.second
        } catch (e: Exception) {
            error = e.message
        }
    }

    when {
        error != null -> ErrorState("文本预览失败：$error")
        text == null -> LoadingState()
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "${item.name} · ${Fmt.size(item.size)} · ${encoding}" + if (truncated) " · 已截断显示前 1 MB" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    items(text!!.split('\n')) { line ->
                        Text(
                            line.ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun decodeText(bytes: ByteArray): Pair<String, String> {
    val decoded = com.u707t.panelfm.core.common.TextEncodings.decode(bytes)
    return decoded.charset to decoded.text
}

private suspend fun readBytes(container: AppContainer, uri: VfsUri, max: Long): ByteArray {
    val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
    val reader = vfs.openRead(uri)
    try {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val want = minOf(buffer.size.toLong(), max - total).toInt()
            val n = reader.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}
