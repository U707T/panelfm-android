package com.u707t.panelfm.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.RenderFormats
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Markdown / CSV 的「渲染预览」（与 Office 预览共用 WebView 页面，kind = markdown / csv）。
 *
 *  - **编码**：文件字节先按 [TextEncodings] 识别（UTF-8 / GBK / Big5…）再统一转 UTF-8 喂给页面 ——
 *    否则 GBK 编码的 md / csv 中文会乱码（页面侧只按 UTF-8 解）；
 *  - 只读、不联网：拦截规则与 Office 预览完全一致（见 [PreviewWebView]）；
 *  - Markdown 由 marked 渲染 + DOMPurify 清洗（md 是不可信输入）；CSV 由 SheetJS 按表格渲染。
 */
private const val RENDER_MAX_BYTES = 16L * 1024 * 1024

@Composable
fun RenderScreen(container: AppContainer, item: FileMetadata, onBack: () -> Unit) {
    val kind = RenderFormats.viewerKindOf(item.extension)
    val context = LocalContext.current
    var bytes by remember(item.uri) { mutableStateOf<ByteArray?>(null) }
    var encoding by remember(item.uri) { mutableStateOf("") }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var pageError by remember(item.uri) { mutableStateOf<String?>(null) }
    var showDiag by remember(item.uri) { mutableStateOf(false) }
    var forceText by remember(item.uri) { mutableStateOf(false) }
    val logs = remember(item.uri) { mutableStateListOf<String>() }

    LaunchedEffect(item.uri) {
        if (kind.isEmpty()) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                val raw = readPreviewBytes(container, item, RENDER_MAX_BYTES)
                val decoded = TextEncodings.decode(raw)
                decoded.charset to decoded.text.toByteArray(Charsets.UTF_8)
            }
        }.onSuccess { (charset, utf8) ->
            encoding = charset
            bytes = utf8
        }.onFailure { error = it.message ?: "读取文件失败" }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = item.name,
            subtitle = if (kind.isEmpty()) null else "${Fmt.size(item.size)} · $encoding · 只读预览",
            onBack = onBack,
            actions = {
                if (kind.isNotEmpty()) {
                    MtIconButton(
                        icon = MtIcon.INFO,
                        contentDescription = "预览诊断",
                        onClick = { showDiag = true },
                    )
                }
            },
        )
        when {
            kind.isEmpty() -> ErrorState("该格式不支持渲染预览（仅 Markdown / CSV）")
            error != null -> ErrorState(error!!)
            pageError != null -> ErrorState(pageError!!)
            bytes == null -> LoadingState("正在读取文件…")
            else -> key(forceText) {
                PreviewWebView(
                    kind = kind,
                    bytes = bytes!!,
                    forceText = forceText,
                    logs = logs,
                    onPageError = { pageError = it },
                )
            }
        }
    }

    if (showDiag && kind.isNotEmpty()) {
        PreviewDiagnosticsDialog(
            report = buildPreviewDiagnosticsReport(
                context = context,
                item = item,
                kind = kind,
                forceText = forceText,
                pageError = pageError,
                logs = logs.toList(),
            ),
            forceText = forceText,
            onToggleTextMode = {
                forceText = !forceText
                showDiag = false
            },
            onDismiss = { showDiag = false },
        )
    }
}
