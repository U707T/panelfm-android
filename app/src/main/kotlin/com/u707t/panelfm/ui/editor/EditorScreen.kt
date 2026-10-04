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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.CodeFormatter
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.LineOps
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.HistoryButton
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.safeAreaPadding
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
 *
 * **v1.0 补齐 MT 编辑器菜单 `0x7f0e001b` / `0x7f0e000e` 的一批命令**：
 *  - 行操作：复制行 / 剪切行 / 删除行 / 清空行 / 重复行 / 转大写 / 转小写 / 增删缩进 / 切换注释
 *  - 「压缩代码」（保守实现：去行尾空白 + 去首尾空行，`0x7f110424`）
 *  - 「格式化代码」（JSON / XML，`0x7f110411`）
 *  - 「转到指定行」（`0x7f1106f5`~`6f8` 的定位系列）
 *  - 「查找」条抄 MT `0x7f0c004f` 的一行式（输入框 + 上个 + 下个 + 替换 + 全部替换），
 *    并补 MT 的「找不到文本」差异化提示（`0x7f1106e7`~`6ec`，按正则 / 大小写 / 全词组合给不同文案）
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }
    var status by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(true) }
    var wholeWord by remember { mutableStateOf(false) }
    var originalMode by remember { mutableStateOf<Int?>(null) }
    var lang by remember { mutableStateOf(SyntaxLanguage.PLAIN) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var gotoLineDialog by remember { mutableStateOf(false) }
    var gotoLineText by remember { mutableStateOf("") }

    // ---- 大文件只读分段浏览
    var paged by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var pageRange by remember { mutableStateOf(0L to 0L) }
    var pageLoading by remember { mutableStateOf(false) }

    val text = value.text
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
                value = TextFieldValue(slice.text)
                original = slice.text
                page = 0
                pageRange = slice.start to slice.end
            } else {
                val bytes = readAtMost(vfs, uri, MAX_EDIT_SIZE)
                val decoded = TextEncodings.decode(bytes)
                charset = decoded.charset
                value = TextFieldValue(decoded.text)
                original = decoded.text
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
        this.value = if (hlActive) withContext(Dispatchers.Default) {
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
                value = TextFieldValue(slice.text)
                original = slice.text
                page = t
                pageRange = slice.start to slice.end
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

    // ---------------- 查找（MT 的「找不到文本」差异化提示 0x7f1106e7~6ec）
    fun notFoundMessage(): String = buildString {
        append("找不到文本")
        val flags = buildList {
            if (useRegex) add("正则表达式")
            if (matchCase) add("区分大小写")
            if (wholeWord) add("全词匹配")
        }
        if (flags.isNotEmpty()) append("（已开启").append(flags.joinToString("和")).append("）")
    }

    /** 找下一个匹配（循环）；[backward] = 上个 */
    fun findNext(backward: Boolean = false) {
        if (findText.isEmpty()) return
        val total = countMatches(text, findText, useRegex, matchCase, wholeWord)
        if (total == 0) {
            status = notFoundMessage()
            return
        }
        val hits = matchOffsets(text, findText, useRegex, matchCase, wholeWord)
        val cur = value.selection.start
        val target = if (backward) {
            hits.lastOrNull { it < cur } ?: hits.last()
        } else {
            hits.firstOrNull { it > cur } ?: hits.first()
        }
        val len = hitLength(text, findText, target, useRegex, matchCase, wholeWord)
        value = value.copy(selection = TextRange(target, target + len))
        val line = text.take(target).count { it == '\n' } + 1
        val ordinal = hits.indexOf(target) + 1
        status = "第 $ordinal / $total 处 · 第 $line 行"
        scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_FIND, findText) }
    }

    fun replaceCurrent() {
        if (findText.isEmpty()) return
        val sel = value.selection
        val selected = if (sel.collapsed) "" else text.substring(sel.start, sel.end)
        val matches = selected.isNotEmpty() &&
            matchesPattern(selected, findText, useRegex, matchCase, wholeWord)
        if (!matches) {
            findNext()
            return
        }
        val out = buildString {
            append(text, 0, sel.start)
            append(if (useRegex) Regex(findText).replace(selected, replaceText) else replaceText)
            append(text, sel.end, text.length)
        }
        value = TextFieldValue(out, TextRange(sel.start + replaceText.length))
        status = "已替换 1 处"
    }

    fun replaceAll() {
        if (findText.isEmpty()) return
        val count = countMatches(text, findText, useRegex, matchCase, wholeWord)
        if (count == 0) {
            status = notFoundMessage()
            return
        }
        val out = if (useRegex) {
            val r = Regex(findText)
            if (matchCase && wholeWord) text.replace(r) { m -> replaceText }
            else text.replace(r, replaceText)
        } else if (matchCase && wholeWord) {
            Regex("\\b" + Regex.escape(findText) + "\\b").replace(text, replaceText)
        } else if (!matchCase && !wholeWord) {
            text.replace(findText, replaceText, ignoreCase = true)
        } else if (!matchCase) {
            Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).replace(text, replaceText)
        } else {
            Regex("\\b" + Regex.escape(findText) + "\\b").replace(text, replaceText)
        }
        value = TextFieldValue(out)
        status = "已替换 $count 处"
        scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replaceText) }
    }

    // ---------------- 行操作（MT 菜单 0x7f0e001b）
    val cursorLine = LineOps.lineIndexOf(text, value.selection.start)
    val commentPrefix = commentPrefixOf(lang)

    fun applyLineOp(op: (String, Int) -> String, label: String) {
        val out = op(text, cursorLine)
        if (out == text) {
            status = "「$label」没有可操作的内容"
            return
        }
        value = TextFieldValue(out, TextRange(LineOps.lineStartOffset(out, cursorLine).coerceAtMost(out.length)))
        status = label
    }

    val editorSettings = container.settings.collectAsState().value
    val findHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_FIND].orEmpty()
    val replaceHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_REPLACE].orEmpty()

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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // MT 0x7f0e000e 的 ⋮ 主菜单
            Box {
                TextButton(onClick = { showMenu = true }) { Text("⋮") }
                EditorMenu(
                    expanded = showMenu,
                    lang = lang,
                    readOnly = readOnly,
                    onDismiss = { showMenu = false },
                    onSave = {
                        showMenu = false
                        scope.launch {
                            saveText(container, uri, text, charset, originalMode, { msg -> status = msg }) { ok ->
                                if (ok) {
                                    original = text
                                    container.browser.refreshAll()
                                }
                            }
                        }
                    },
                    onLineOp = { label, op ->
                        showMenu = false
                        applyLineOp(op, label)
                    },
                    onToggleComment = {
                        showMenu = false
                        if (commentPrefix == null) {
                            // MT 0x7f110701「当前语言不支持该操作」
                            status = "当前语言不支持切换注释"
                        } else {
                            val out = LineOps.toggleComment(text, commentPrefix)
                            value = TextFieldValue(out, TextRange(LineOps.lineStartOffset(out, cursorLine).coerceAtMost(out.length)))
                            status = "切换注释"
                        }
                    },
                    onCompress = {
                        showMenu = false
                        value = TextFieldValue(LineOps.trimTrailingWhitespace(text))
                        status = "压缩代码（去行尾空白 / 首尾空行）"
                    },
                    onFormat = {
                        showMenu = false
                        val formatted = CodeFormatter.format(text, if (lang == SyntaxLanguage.XML) "xml" else "json")
                        if (formatted == null) {
                            status = "暂不支持该语言的格式化（当前支持 JSON / XML）"
                        } else {
                            value = TextFieldValue(formatted)
                            status = "已格式化代码"
                        }
                    },
                    onGotoLine = { showMenu = false; gotoLineText = (cursorLine + 1).toString(); gotoLineDialog = true },
                    onToggleFind = { showMenu = false; showFind = !showFind },
                )
            }
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A-") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28) }) { Text("A+") }
            TextButton(onClick = { showFind = !showFind }) { Text("查找") }
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
                    "段 ${page + 1}/$pageCount · ${Fmt.size(pageRange.first)}–${Fmt.size(pageRange.second)}" +
                        if (pageLoading) " · 加载中…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                PageButton("›", enabled = page < pageCount - 1 && !pageLoading) { goPage(page + 1) }
                PageButton("⇥", enabled = page < pageCount - 1 && !pageLoading) { goPage(pageCount - 1) }
            }
        }

        // ---- 查找条（抄 MT 0x7f0c004f 的一行式：输入框 + 上个 + 下个 + 替换 + 全部替换）
        if (showFind) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorField(findText, { findText = it }, "查找", Modifier.weight(1f))
                    HistoryButton(findHistory) { findText = it }
                    TextButton(onClick = { findNext(backward = true) }) { Text("上个") }
                    TextButton(onClick = { findNext() }) { Text("下个") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorField(replaceText, { replaceText = it }, "替换为", Modifier.weight(1f))
                    HistoryButton(replaceHistory) { replaceText = it }
                    TextButton(enabled = !readOnly, onClick = { replaceCurrent() }) { Text("替换") }
                    TextButton(enabled = !readOnly, onClick = { replaceAll() }) { Text("全部替换") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagCheckbox("正则表达式", useRegex) { useRegex = it }
                    FlagCheckbox("区分大小写", matchCase) { matchCase = it }
                    FlagCheckbox("全词匹配", wholeWord) { wholeWord = it }
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
                    value = value,
                    onValueChange = { if (!readOnly) value = it },
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

    if (gotoLineDialog) {
        AlertDialog(
            onDismissRequest = { gotoLineDialog = false },
            title = { Text("转到指定行") },
            text = {
                Column {
                    EditorField(gotoLineText, { gotoLineText = it }, "行号", Modifier.fillMaxWidth())
                    Text(
                        "共 ${text.count { it == '\n' } + 1} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = gotoLineText.trim().toIntOrNull()
                    if (target == null || target < 1) {
                        status = "请输入有效的行号"
                    } else {
                        val off = LineOps.lineStartOffset(text, target - 1).coerceIn(0, text.length)
                        value = value.copy(selection = TextRange(off))
                        status = "已定位到第 $target 行"
                    }
                    gotoLineDialog = false
                }) { Text("转到") }
            },
            dismissButton = { TextButton(onClick = { gotoLineDialog = false }) { Text("取消") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
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

/** 编辑器 ⋮ 菜单（复刻 MT 0x7f0e001b 行操作 + 0x7f0e000e 主菜单的可用子集） */
@Composable
private fun EditorMenu(
    expanded: Boolean,
    lang: SyntaxLanguage,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onLineOp: (String, (String, Int) -> String) -> Unit,
    onToggleComment: () -> Unit,
    onCompress: () -> Unit,
    onFormat: () -> Unit,
    onGotoLine: () -> Unit,
    onToggleFind: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("💾  保存") }, onClick = onSave, enabled = !readOnly)
        DropdownMenuItem(text = { Text("🔍  查找 / 替换") }, onClick = onToggleFind)
        DropdownMenuItem(text = { Text("↧  转到指定行…") }, onClick = onGotoLine)
        Text(
            "行操作",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        DropdownMenuItem(text = { Text("⧉  复制行") }, onClick = { onLineOp("复制行") { t, i -> LineOps.duplicateLine(t, i) } })
        DropdownMenuItem(text = { Text("✂  剪切行") }, onClick = { onLineOp("剪切行") { t, i -> LineOps.cutLine(t, i).first } })
        DropdownMenuItem(text = { Text("🗑  删除行") }, onClick = { onLineOp("删除行") { t, i -> LineOps.deleteLine(t, i) } })
        DropdownMenuItem(text = { Text("␣  清空行") }, onClick = { onLineOp("清空行") { t, i -> LineOps.clearLine(t, i) } })
        DropdownMenuItem(text = { Text("⇊  重复行") }, onClick = { onLineOp("重复行") { t, i -> LineOps.duplicateLine(t, i) } })
        DropdownMenuItem(
            text = { Text("🅰  转为大写") },
            onClick = { onLineOp("转为大写") { t, _ -> LineOps.toUpperCase(t) } },
        )
        DropdownMenuItem(
            text = { Text("🅰  转为小写") },
            onClick = { onLineOp("转为小写") { t, _ -> LineOps.toLowerCase(t) } },
        )
        DropdownMenuItem(text = { Text("→|  增加缩进") }, onClick = { onLineOp("增加缩进") { t, _ -> LineOps.indent(t) } })
        DropdownMenuItem(text = { Text("|←  减小缩进") }, onClick = { onLineOp("减小缩进") { t, _ -> LineOps.unindent(t) } })
        DropdownMenuItem(text = { Text("//  切换注释") }, onClick = onToggleComment)
        Text(
            "代码整理",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        DropdownMenuItem(text = { Text("🗜  压缩代码（去空白）") }, onClick = onCompress)
        DropdownMenuItem(
            text = {
                Text(
                    "✨  格式化代码" + if (lang == SyntaxLanguage.JSON || lang == SyntaxLanguage.XML) "" else "（当前语言不支持）",
                )
            },
            onClick = onFormat,
        )
    }
}

/** 小号输入框（查找条用） */
@Composable
private fun EditorField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
        )
    }
}

@Composable
private fun FlagCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 按语言给行注释前缀（MT 的「切换注释」按语法规则；不支持的语言返回 null） */
internal fun commentPrefixOf(lang: SyntaxLanguage): String? = when (lang) {
    SyntaxLanguage.KOTLIN, SyntaxLanguage.JAVA, SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.TYPESCRIPT,
    SyntaxLanguage.CSS,
    -> "//"
    SyntaxLanguage.PYTHON, SyntaxLanguage.SHELL, SyntaxLanguage.YAML, SyntaxLanguage.PROPERTIES,
    SyntaxLanguage.INI,
    -> "#"
    else -> null
}

// ------------------------------------------------------------------ 匹配工具

/** 找出所有匹配起点（支持正则 / 大小写 / 全词） */
internal fun matchOffsets(text: String, needle: String, useRegex: Boolean, matchCase: Boolean, wholeWord: Boolean): List<Int> {
    if (needle.isEmpty()) return emptyList()
    return runCatching {
        val out = ArrayList<Int>()
        if (useRegex) {
            val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(needle, opts).findAll(text).forEach { out.add(it.range.first) }
        } else {
            val pattern = if (wholeWord) "\\b" + Regex.escape(needle) + "\\b" else Regex.escape(needle)
            val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, opts).findAll(text).forEach { out.add(it.range.first) }
        }
        out
    }.getOrDefault(emptyList())
}

internal fun countMatches(text: String, needle: String, useRegex: Boolean, matchCase: Boolean, wholeWord: Boolean): Int =
    matchOffsets(text, needle, useRegex, matchCase, wholeWord).size

/** 命中的长度（正则按实际匹配长度） */
internal fun hitLength(text: String, needle: String, at: Int, useRegex: Boolean, matchCase: Boolean, wholeWord: Boolean): Int {
    if (!useRegex) return needle.length
    return runCatching {
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val m = Regex(needle, opts).find(text, at)
        if (m != null && m.range.first == at) m.value.length else needle.length
    }.getOrDefault(needle.length)
}

internal fun matchesPattern(text: String, needle: String, useRegex: Boolean, matchCase: Boolean, wholeWord: Boolean): Boolean =
    runCatching {
        val pattern = when {
            useRegex -> needle
            wholeWord -> "\\b" + Regex.escape(needle) + "\\b"
            else -> Regex.escape(needle)
        }
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(pattern, opts).matches(text)
    }.getOrDefault(false)

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
