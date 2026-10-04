package com.u707t.panelfm.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 文本编辑器：可编辑 + 保存（保留权限）。
 *  - 编码识别（BOM / UTF-8 / GBK）并回写同编码
 *  - 字号缩放、查找（循环定位）/ 替换、行数统计、未保存提示
 *  - **语法高亮**（按后缀识别 JSON / XML / Kotlin / Java / JS / Python / Shell / YAML / Markdown 等，
 *    后台词法分析，超大文本自动关闭）
 *  - \> 2 MB 自动进入**只读分段浏览**（每段 512 KB，可前后翻段），大日志也能看
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }
    var status by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var findCursor by remember { mutableStateOf(-1) }
    var originalMode by remember { mutableStateOf<Int?>(null) }
    var lang by remember { mutableStateOf(SyntaxLanguage.PLAIN) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // ---- 大文件只读分段浏览
    var paged by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var pageRange by remember { mutableStateOf(0L to 0L) }
    var pageLoading by remember { mutableStateOf(false) }

    val dirty = text != original && !paged

    LaunchedEffect(uri) {
        try {
            val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
            val info = withContext(Dispatchers.IO) { vfs.stat(uri) }
            meta = info
            originalMode = info.permissions
            lang = SyntaxLanguage.ofFileName(info.name.ifEmpty { uri.name })
            if (info.size > MAX_EDIT_SIZE) {
                // 大文件：只读 + 分段浏览
                readOnly = true
                paged = true
                pageCount = ((info.size + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
                val slice = loadPage(vfs, uri, 0, info.size, charset = null)
                charset = slice.charset
                text = slice.text
                original = text
                page = 0
                pageRange = slice.start to slice.end
            } else {
                val bytes = readAtMost(vfs, uri, MAX_EDIT_SIZE)
                val decoded = TextEncodings.decode(bytes)
                charset = decoded.charset
                text = decoded.text
                original = text
            }
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        } finally {
            loading = false
        }
    }

    // ---- 语法高亮：后台词法分析（文本 / 语言变化时重算；超大文本关闭）
    val hlActive = lang != SyntaxLanguage.PLAIN && text.length <= HL_MAX_CHARS
    val spans by produceState(emptyList<TokenSpan>(), text, lang, hlActive) {
        value = if (hlActive) withContext(Dispatchers.Default) {
            runCatching { SyntaxLexer.highlight(text, lang) }.getOrDefault(emptyList())
        } else emptyList()
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val highlight = remember(spans, dark, primaryColor) {
        SyntaxHighlightTransformation(spans, syntaxColors(dark, primaryColor))
    }

    /** 翻到指定段（0 起） */
    fun goPage(target: Int) {
        if (pageLoading) return
        val info = meta ?: return
        val t = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        scope.launch {
            pageLoading = true
            try {
                val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
                val slice = loadPage(vfs, uri, t, info.size, charset)
                text = slice.text
                original = text
                page = t
                pageRange = slice.start to slice.end
                findCursor = -1
            } catch (e: Exception) {
                status = "读取分段失败：${e.message}"
            } finally {
                pageLoading = false
            }
        }
    }

    /** 离开编辑器：未保存时弹「保存 / 放弃 / 取消」，避免手势返回静默丢修改 */
    fun attemptLeave() {
        if (!dirty || readOnly) {
            onBack()
            return
        }
        confirmDiscard = true
    }

    androidx.activity.compose.BackHandler(enabled = true) { attemptLeave() }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { attemptLeave() }) { Text("← 返回") }
            Text(
                (meta?.name ?: uri.name) + if (dirty) " *" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A-") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28) }) { Text("A+") }
            TextButton(onClick = { showReplace = !showReplace }) { Text("查找") }
            TextButton(
                enabled = !readOnly && dirty,
                onClick = {
                    scope.launch {
                        saveText(container, uri, text, charset, originalMode, { msg -> status = msg }) { ok ->
                            if (ok) {
                                original = text
                                container.browser.refreshAll()
                            }
                        }
                    }
                },
            ) { Text("保存", color = if (!readOnly && dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // ---- 大文件分段浏览控制条
        if (paged) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageButton("⇤", enabled = page > 0 && !pageLoading) { goPage(0) }
                PageButton("‹", enabled = page > 0 && !pageLoading) { goPage(page - 1) }
                Text(
                    "段 ${page + 1}/${pageCount} · ${Fmt.size(pageRange.first)}–${Fmt.size(pageRange.second)}" +
                        if (pageLoading) " · 加载中…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                PageButton("›", enabled = page < pageCount - 1 && !pageLoading) { goPage(page + 1) }
                PageButton("⇥", enabled = page < pageCount - 1 && !pageLoading) { goPage(pageCount - 1) }
            }
        }

        if (showReplace) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        BasicTextField(
                            value = findText,
                            onValueChange = { findText = it; findCursor = -1 },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                        )
                    }
                    TextButton(onClick = {
                        if (findText.isEmpty()) return@TextButton
                        val total = countOccurrences(text, findText)
                        if (total == 0) {
                            findCursor = -1
                            status = "未找到「$findText」"
                        } else {
                            var next = text.indexOf(findText, (findCursor + 1).coerceAtLeast(0))
                            var wrapped = false
                            if (next < 0) {
                                next = text.indexOf(findText)
                                wrapped = true
                            }
                            findCursor = next
                            val line = text.take(next).count { it == '\n' } + 1
                            status = "第 ${ordinalOf(text, findText, next)} / $total 处 · 第 $line 行" +
                                if (wrapped) "（已回到开头）" else ""
                        }
                    }) { Text("查找") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        BasicTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                        )
                    }
                    TextButton(
                        enabled = !readOnly && findText.isNotEmpty(),
                        onClick = {
                            val count = countOccurrences(text, findText)
                            text = text.replace(findText, replaceText)
                            findCursor = -1
                            status = "已替换 $count 处"
                        },
                    ) { Text("全部替换") }
                }
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        when {
            loading -> LoadingState()
            error != null -> ErrorState("打开失败：$error")
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (readOnly) {
                    Text(
                        if (paged) "只读：文件大于 ${Fmt.size(MAX_EDIT_SIZE)}，已进入分段浏览（每段 ${Fmt.size(PAGE_SIZE)}）"
                        else "只读：文件大于 ${Fmt.size(MAX_EDIT_SIZE)}（可另存或复制到本地后编辑）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { if (!readOnly) text = it },
                    textStyle = TextStyle(
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.35f).sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    visualTransformation = highlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    buildString {
                        append("${text.count { it == '\n' } + 1} 行 · ${text.length} 字符 · $charset")
                        if (lang != SyntaxLanguage.PLAIN) append(" · ${lang.label}")
                        if (paged) append(" · 分段 ${page + 1}/$pageCount")
                        meta?.size?.let { append(" · ${Fmt.size(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (confirmDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("有未保存的修改") },
            text = { Text("「${meta?.name ?: uri.name}」已修改但未保存，直接返回会丢失这些修改。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    scope.launch {
                        saveText(container, uri, text, charset, originalMode, { msg -> status = msg }) { ok ->
                            if (ok) onBack()
                        }
                    }
                }) { Text("保存并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmDiscard = false
                        onBack()
                    }) { Text("放弃修改") }
                    TextButton(onClick = { confirmDiscard = false }) { Text("取消") }
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 分段浏览

private const val MAX_EDIT_SIZE = 2L * 1024 * 1024

/** 大文件分段大小与读取冗余（对齐行边界用） */
private const val PAGE_SIZE = 512L * 1024
private const val PAGE_SLACK = 64L * 1024

/** 语法高亮字符上限（超过则关闭，保证输入流畅） */
private const val HL_MAX_CHARS = 640_000

private data class PageSlice(val text: String, val charset: String, val start: Long, val end: Long)

/**
 * 读取一页：从 [page]×PAGE_SIZE 起读 PAGE_SIZE + SLACK；
 * 非首页跳过开头不完整的行（对齐行边界），返回显示区间（字节）。
 */
private suspend fun loadPage(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    page: Int,
    fileSize: Long,
    charset: String?,
): PageSlice = withContext(Dispatchers.IO) {
    val start = page.toLong() * PAGE_SIZE
    val end = minOf(start + PAGE_SIZE, fileSize)
    if (start >= fileSize) return@withContext PageSlice("", charset ?: "UTF-8", start, start)
    val readEnd = minOf(end + PAGE_SLACK, fileSize)
    val reader = vfs.openRead(uri, offset = start)
    try {
        val len = (readEnd - start).toInt().coerceAtLeast(0)
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = reader.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        var skip = 0
        if (page > 0) {
            val nl = indexOfByte(buf, off, '\n'.code.toByte())
            if (nl >= 0) skip = nl + 1
        }
        val bytes = buf.copyOfRange(skip, off)
        val decoded = if (charset == null) TextEncodings.decode(bytes)
        else TextEncodings.Decoded(charset, decodeWith(charset, bytes))
        PageSlice(decoded.text, decoded.charset, start + skip, start + off)
    } finally {
        runCatching { reader.close() }
    }
}

/** 在 [0, limit) 内找字节 [b] 的下标；找不到返回 -1 */
private fun indexOfByte(buf: ByteArray, limit: Int, b: Byte): Int {
    for (i in 0 until limit) if (buf[i] == b) return i
    return -1
}

private fun decodeWith(charset: String, bytes: ByteArray): String = when {
    charset.startsWith("UTF-8") -> {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
    charset == "GBK" -> String(bytes, Charset.forName("GBK"))
    charset == "UTF-16LE" -> String(bytes, Charsets.UTF_16LE)
    charset == "UTF-16BE" -> String(bytes, Charsets.UTF_16BE)
    else -> String(bytes, Charsets.ISO_8859_1)
}

private fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var i = text.indexOf(needle)
    while (i >= 0) {
        count++
        i = text.indexOf(needle, i + needle.length)
    }
    return count
}

/** 第 [at] 个匹配是第几处（1 起） */
private fun ordinalOf(text: String, needle: String, at: Int): Int {
    if (needle.isEmpty()) return 1
    var count = 1
    var i = text.indexOf(needle)
    while (i >= 0 && i < at) {
        count++
        i = text.indexOf(needle, i + needle.length)
    }
    return count
}

// ------------------------------------------------------------------ 语法着色

/** 高亮配色（明/暗两套；只影响观感，不影响文本与编辑） */
private fun syntaxColors(dark: Boolean, primary: Color): Map<TokenType, SpanStyle> {
    fun color(light: Long, darkColor: Long) = Color(if (dark) darkColor else light)
    return mapOf(
        TokenType.KEYWORD to SpanStyle(color = color(0xFF7C3AED, 0xFFC792EA)),
        TokenType.STRING to SpanStyle(color = color(0xFF1E8E3E, 0xFF9CCC65)),
        TokenType.COMMENT to SpanStyle(color = color(0xFF80868B, 0xFF9AA0A6), fontStyle = FontStyle.Italic),
        TokenType.NUMBER to SpanStyle(color = color(0xFF1565C0, 0xFF82B1FF)),
        TokenType.KEY to SpanStyle(color = color(0xFF00695C, 0xFF4DB6AC)),
        TokenType.TAG to SpanStyle(color = color(0xFFD81B60, 0xFFF48FB1)),
        TokenType.ATTR to SpanStyle(color = color(0xFFE65100, 0xFFFFB74D)),
        TokenType.ANNOTATION to SpanStyle(color = color(0xFF8E24AA, 0xFFB39DDB)),
        TokenType.HEADING to SpanStyle(color = primary, fontWeight = FontWeight.Bold),
        TokenType.CODE to SpanStyle(color = color(0xFF00695C, 0xFF80CBC4)),
    )
}

private class SyntaxHighlightTransformation(
    private val spans: List<TokenSpan>,
    private val colors: Map<TokenType, SpanStyle>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val plain = text.text
        val annotated = buildAnnotatedString {
            append(plain)
            spans.forEach { span ->
                val style = colors[span.type] ?: return@forEach
                if (span.start in 0 until span.end && span.end <= plain.length) {
                    addStyle(style, span.start, span.end)
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

// ------------------------------------------------------------------ 小工具组件

@Composable
private fun PageButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

// ------------------------------------------------------------------ 读写

private suspend fun readAtMost(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    max: Long,
): ByteArray = withContext(Dispatchers.IO) {
    val reader = vfs.openRead(uri)
    try {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = reader.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}

private suspend fun saveText(
    container: AppContainer,
    uri: VfsUri,
    text: String,
    charset: String,
    originalMode: Int?,
    onStatus: (String?) -> Unit,
    onDone: (Boolean) -> Unit,
) {
    try {
        val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
        val bytes = when {
            charset.startsWith("UTF-8") -> text.toByteArray(Charsets.UTF_8)
            charset == "GBK" -> text.toByteArray(Charset.forName("GBK"))
            charset == "UTF-16LE" -> text.toByteArray(Charsets.UTF_16LE)
            charset == "UTF-16BE" -> text.toByteArray(Charsets.UTF_16BE)
            else -> text.toByteArray(Charsets.UTF_8)
        }
        // MT「保存文件时自动将原文件重命名为 .bak 备份文件」
        if (container.settings.value.backupOnSave && vfs.capabilities.rename) {
            runCatching { vfs.rename(uri, uri.parent?.child(uri.name + ".bak") ?: uri) }
        }
        withContext(Dispatchers.IO) {
            val writer = vfs.openWrite(uri, size = bytes.size.toLong(), offset = 0L)
            writer.write(bytes, 0, bytes.size)
            writer.commit()
            // 保留原有权限（本地/SFTP/FTP 支持 chmod 时）
            if (originalMode != null && vfs.capabilities.permissions) {
                runCatching { vfs.setPermissions(uri, originalMode) }
            }
        }
        onStatus("已保存（$charset，${bytes.size} 字节）")
        onDone(true)
    } catch (e: Exception) {
        onStatus("保存失败：${e.message}")
        onDone(false)
    }
}
