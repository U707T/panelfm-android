package com.u707t.panelfm.ui.preview

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内置 PDF 查看器（只读，复刻 MT 的「PDF 查看」位）：
 *  - 用系统 [PdfRenderer]（零额外依赖）逐页渲染为位图；整篇共用一个渲染器实例（每页重开代价高）；
 *  - 远程 / 压缩包内的 PDF 先落到应用缓存再渲染（渲染器只接受文件描述符）；
 *  - 纵向滚动逐页浏览，顶栏显示总页数、页脚显示「第 N / M 页」。
 *
 * 说明：MT 的 PDF 查看器带缩放 / 跳页；PanelFM 保持「只读预览」定位，先给逐页滚动浏览。
 */
@Composable
fun PdfScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    var pdfFile by remember(item.uri) { mutableStateOf<File?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var pageCount by remember(item.uri) { mutableStateOf(0) }

    // 1) 准备本地文件（本地直接引用；远程/压缩包先缓存）
    LaunchedEffect(item.uri) {
        runCatching {
            withContext(container.dispatchers.io) { materializePdf(container, item) }
        }.onSuccess { pdfFile = it }
            .onFailure { error = it.message ?: "无法读取 PDF" }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = item.name,
            subtitle = if (pageCount > 0) "$pageCount 页 · ${Fmt.size(item.size)}" else null,
            onBack = onBack,
        )

        when {
            error != null -> ErrorState("PDF 预览失败：$error\n（可在 ⋮ 菜单点「Hex」查看原始数据）")
            pdfFile == null -> LoadingState("正在打开 PDF…")
            else -> PdfPager(pdfFile!!, onPageCount = { pageCount = it })
        }
    }
}

/** 逐页滚动浏览：整篇共用一个 [PdfRenderer]；渲染放 IO 线程并用 Mutex 串行化（渲染器非线程安全） */
@Composable
private fun PdfPager(file: File, onPageCount: (Int) -> Unit) {
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var descriptor by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pages by remember(file) { mutableStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val lock = remember(file) { Mutex() }
    val listState = rememberLazyListState()

    // 页面离开组合 / 换文件时释放原生资源
    DisposableEffect(file) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    LaunchedEffect(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = fd
            renderer = PdfRenderer(fd).also { pages = it.pageCount }
        }.onFailure { error = it.message ?: "PDF 解析失败" }
        onPageCount(pages)
    }

    when {
        error != null -> ErrorState("PDF 解析失败：$error")
        pages == 0 -> LoadingState("正在解析 PDF…")
        else -> LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF303030)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pages) { index ->
                PdfPage(renderer, lock, index, pages)
            }
        }
    }
}

/** 单页渲染：进入组合时渲染，离开即释放位图 */
@Composable
private fun PdfPage(
    renderer: PdfRenderer?,
    lock: Mutex,
    index: Int,
    total: Int,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }

    LaunchedEffect(renderer, index) {
        val r = renderer ?: return@LaunchedEffect
        runCatching {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                lock.withLock {
                    r.openPage(index).use { page ->
                        // 渲染宽度按 1600px 上限（清晰且不 OOM）
                        val scale = (1600f / page.width).coerceAtMost(3f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.onSuccess { bitmap = it }.onFailure { failed = true }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            failed -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text("第 ${index + 1} 页渲染失败", color = Color.White) }

            bitmap == null -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text("正在渲染第 ${index + 1} 页…", color = Color.White.copy(alpha = 0.7f)) }

            else -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "PDF 第 ${index + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "第 ${index + 1} / $total 页",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/** 远程 / 压缩包内的 PDF 先复制到应用缓存（PdfRenderer 只接受文件描述符） */
private suspend fun materializePdf(container: AppContainer, item: FileMetadata): File {
    if (item.uri.scheme == "local") {
        val path = runCatching { container.localVfs.absolutePath(item.uri) }.getOrNull()
        if (path != null && File(path).exists()) return File(path)
    }
    val dir = File(container.appDirs.cacheDir, "pdf")
    runCatching { dir.mkdirs() }
    val target = File(dir, "doc-${item.uri.toString().hashCode()}-${item.size}.pdf")
    if (target.exists() && target.length() > 0) return target
    val tmp = File(dir, "${target.name}.part")
    val vfs = container.resolveSession(item.uri) ?: throw IllegalStateException("会话不可用（存储已断开）")
    vfs.openRead(item.uri).use { reader ->
        tmp.outputStream().use { out ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }
    return target
}
