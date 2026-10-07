package com.u707t.panelfm.ui.editor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.CodeFormatter
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.LineOps
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.common.TextSearch
import com.u707t.panelfm.core.common.TextSearchOptions
import com.u707t.panelfm.core.common.TextSearchQuery
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.ErrorState
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
 *  - 「查找」条对齐 MT `0x7f0c0048` 的底部布局（查找 / 替换两行 + 上个 / 下个 / 替换 / 全部 / ⋮），
 *    查找/替换在后台线程执行，并补 MT 的「找不到文本」差异化提示（`0x7f1106e7`~`6ec`）
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dark = LocalPanelDarkTheme.current
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
    var findOptionsMenu by remember { mutableStateOf(false) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchRequestId by remember { mutableLongStateOf(0L) }

    // ---- 大文件只读分段浏览
    var paged by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var pageRange by remember { mutableStateOf(0L to 0L) }
    var pageLoading by remember { mutableStateOf(false) }

    val text = value.text
    val dirty = text != original && !paged
    val searchOptions = TextSearchOptions(
        regex = useRegex,
        matchCase = matchCase,
        wholeWord = wholeWord,
    )

    LaunchedEffect(uri) {
        searchRequestId++
        searchBusy = false
        try {
            val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
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
        searchRequestId++
        searchBusy = false
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
    fun notFoundMessage(options: TextSearchOptions): String = when {
        options.regex && options.matchCase && options.wholeWord ->
            "找不到文本（已开启正则表达式、全词匹配和区分大小写）"
        options.regex && options.matchCase -> "找不到文本（已开启正则表达式和区分大小写）"
        options.regex && options.wholeWord -> "找不到文本（已开启正则表达式和全词匹配）"
        options.regex -> "找不到文本（已开启正则表达式）"
        options.wholeWord && options.matchCase -> "找不到文本（已开启全词匹配和区分大小写）"
        options.wholeWord -> "找不到文本（已开启全词匹配）"
        options.matchCase -> "找不到文本（已开启区分大小写）"
        else -> "找不到文本"
    }

    /** 输入条件/正文发生变化时，让正在后台执行的查找结果失效。 */
    fun invalidateSearch(clearStatus: Boolean = true) {
        searchRequestId++
        searchBusy = false
        if (clearStatus) status = null
    }

    /** 找下一个匹配（后台执行、循环）；[backward] = 上个。 */
    fun findNext(backward: Boolean = false) {
        val needle = findText
        if (needle.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        if (searchBusy) return
        val source = text
        val options = searchOptions
        val selection = value.selection
        val from = if (backward || selection.collapsed) selection.start else selection.end
        val requestId = searchRequestId + 1
        searchRequestId = requestId
        searchBusy = true
        status = "查找中…"
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                TextSearch.findNext(source, TextSearchQuery(needle, options), from, backward)
            }
            if (requestId == searchRequestId) searchBusy = false
            // 查询、正文或选项在后台计算期间发生变化时，丢弃旧结果，避免跳回旧位置。
            if (
                requestId != searchRequestId ||
                value.text != source ||
                findText != needle ||
                searchOptions != options
            ) return@launch
            when {
                result.error != null -> status = result.error
                result.match == null -> status = notFoundMessage(options)
                else -> {
                    val match = result.match ?: return@launch
                    value = value.copy(selection = TextRange(match.start, match.end))
                    val line = withContext(Dispatchers.Default) { lineNumberAt(source, match.start) }
                    status = "已找到 · 第 $line 行"
                    container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_FIND, needle)
                }
            }
        }
    }

    /** 替换当前已选中的命中；没有命中时沿用 MT 的操作习惯先定位下一个。 */
    fun replaceCurrent() {
        if (readOnly) return
        val needle = findText
        if (needle.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        val selection = value.selection
        if (selection.collapsed || searchBusy) {
            if (!searchBusy) findNext()
            return
        }
        val source = text
        val replacement = replaceText
        val options = searchOptions
        val requestId = searchRequestId + 1
        searchRequestId = requestId
        searchBusy = true
        status = "替换中…"
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                TextSearch.replaceOne(
                    source,
                    TextSearchQuery(needle, options),
                    selection.start,
                    selection.end,
                    replacement,
                )
            }
            if (requestId == searchRequestId) searchBusy = false
            if (
                requestId != searchRequestId ||
                value.text != source ||
                findText != needle ||
                replaceText != replacement ||
                searchOptions != options
            ) return@launch
            when {
                result.error != null -> status = result.error
                result.count == 0 -> findNext()
                else -> {
                    value = TextFieldValue(
                        result.text,
                        TextRange(selection.start + result.replacementLength),
                    )
                    status = "已替换 1 处"
                    container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replacement)
                }
            }
        }
    }

    /** 全部替换只做一次后台扫描，并保留当前光标的大致位置。 */
    fun replaceAll() {
        if (readOnly) return
        val needle = findText
        if (needle.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        if (searchBusy) return
        val source = text
        val replacement = replaceText
        val options = searchOptions
        val cursor = value.selection.start
        val requestId = searchRequestId + 1
        searchRequestId = requestId
        searchBusy = true
        status = "替换中…"
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                TextSearch.replaceAll(source, TextSearchQuery(needle, options), replacement)
            }
            if (requestId == searchRequestId) searchBusy = false
            if (
                requestId != searchRequestId ||
                value.text != source ||
                findText != needle ||
                replaceText != replacement ||
                searchOptions != options
            ) return@launch
            when {
                result.error != null -> status = result.error
                result.count == 0 -> status = notFoundMessage(options)
                else -> {
                    value = TextFieldValue(
                        result.text,
                        TextRange(cursor.coerceAtMost(result.text.length)),
                    )
                    status = "已替换 ${result.count} 处"
                    container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replacement)
                }
            }
        }
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
        MtScreenTopBar(
            title = (meta?.name ?: uri.name) + if (dirty) " *" else "",
            onBack = { attemptLeave() },
        ) {
            // MT 0x7f0e000e 的 ⋮ 主菜单
            Box {
                MtIconButton(
                    icon = MtIcon.MORE,
                    contentDescription = "更多菜单",
                    onClick = { showMenu = true },
                )
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
                    onToggleFind = {
                        showMenu = false
                        showFind = !showFind
                        findOptionsMenu = false
                    },
                )
            }
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A-") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28) }) { Text("A+") }
            TextButton(onClick = {
                showFind = !showFind
                findOptionsMenu = false
            }) { Text("查找") }
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
                PageButton(MtIcon.FIRST_PAGE, enabled = page > 0 && !pageLoading) { goPage(0) }
                PageButton(MtIcon.CHEVRON_L, enabled = page > 0 && !pageLoading) { goPage(page - 1) }
                Text(
                    "段 ${page + 1}/$pageCount · ${Fmt.size(pageRange.first)}–${Fmt.size(pageRange.second)}" +
                        if (pageLoading) " · 加载中…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                PageButton(MtIcon.CHEVRON_R, enabled = page < pageCount - 1 && !pageLoading) { goPage(page + 1) }
                PageButton(MtIcon.FORWARD, enabled = page < pageCount - 1 && !pageLoading) { goPage(pageCount - 1) }
            }
        }

        // 查找条本体固定在编辑器底部，避免遮住正文。

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        when {
            loading -> LoadingState(modifier = Modifier.weight(1f).fillMaxWidth())
            error != null -> ErrorState("打开失败：$error", modifier = Modifier.weight(1f).fillMaxWidth())
            else -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                    onValueChange = {
                        if (!readOnly) {
                            value = it
                            invalidateSearch()
                        }
                    },
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
        if (showFind) {
            EditorSearchBar(
                findText = findText,
                onFindTextChange = {
                    findText = it
                    invalidateSearch()
                },
                replaceText = replaceText,
                onReplaceTextChange = {
                    replaceText = it
                    invalidateSearch()
                },
                findHistory = findHistory,
                replaceHistory = replaceHistory,
                readOnly = readOnly,
                busy = searchBusy,
                options = searchOptions,
                optionsMenuExpanded = findOptionsMenu,
                onOptionsMenuExpandedChange = { findOptionsMenu = it },
                onRegexChange = {
                    useRegex = it
                    invalidateSearch()
                },
                onMatchCaseChange = {
                    matchCase = it
                    invalidateSearch()
                },
                onWholeWordChange = {
                    wholeWord = it
                    invalidateSearch()
                },
                onPrevious = { findNext(backward = true) },
                onNext = { findNext() },
                onReplace = { replaceCurrent() },
                onReplaceAll = { replaceAll() },
            )
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

private fun lineNumberAt(text: String, offset: Int): Int {
    var line = 1
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') line++
    return line
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
private fun PageButton(icon: MtIcon, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MtVectorIcon(
            icon = icon,
            size = 20.dp,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
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
        // MT「保存文件时自动将原文件重命名为 .bak 备份文件」：备份先做，且必须能看见成败
        val backupEnabled = container.settings.value.backupOnSave
        val backupName = if (backupEnabled) backupBeforeSave(container, vfs, uri) else null
        withContext(Dispatchers.IO) {
            val writer = vfs.openWrite(uri, size = bytes.size.toLong(), offset = 0L)
            writer.write(bytes, 0, bytes.size)
            writer.commit()
            // 保留原有权限（本地/SFTP/FTP 支持 chmod 时）
            if (originalMode != null && vfs.capabilities.permissions) {
                runCatching { vfs.setPermissions(uri, originalMode) }
            }
        }
        val backupNote = when {
            !backupEnabled -> ""
            backupName != null -> "，原文件已备份为 $backupName"
            else -> "；⚠️ 备份失败（本次未生成备份）"
        }
        onStatus("已保存（$charset，${bytes.size} 字节$backupNote）")
        onDone(true)
    } catch (e: Exception) {
        onStatus("保存失败：${e.message}")
        onDone(false)
    }
}

/**
 * 「保存前自动 .bak 备份」的实际实现。
 *
 * 旧实现有两个真 bug（v1.2.1 起就有）：
 *  ① 用 `rename`：改名成功、写新内容失败时，原文件名已经没了，目录里只剩 `x.bak`；
 *  ② 目标名**固定** `x.bak`：第二次保存必然撞名（`LocalVfs.rename` 抛 Conflict），
 *     而调用点用 `runCatching` 吞掉 → 从第二次起**静默不备份**，用户却以为一直在备份。
 *
 * 现在：复制到不冲突的 `.bak` / `.bak.1` / …（复用 [AppContainer.uniqueChild]），
 * 返回备份名；失败返回 null，由调用方在状态里明确提示。
 */
private suspend fun backupBeforeSave(
    container: AppContainer,
    vfs: VirtualFileSystem,
    uri: VfsUri,
): String? {
    val parent = uri.parent ?: return null
    return runCatching {
        val dest = container.uniqueChild(parent, uri.name + ".bak")
        // 服务端复制优先（本地 / S3 是秒级）；不支持时退化为「读流 → 写流」
        if (vfs.capabilities.serverSideCopy && vfs.serverSideCopy(uri, dest)) {
            return@runCatching dest.name
        }
        val reader = vfs.openRead(uri)
        try {
            val writer = vfs.openWrite(dest, size = reader.size, offset = 0L)
            try {
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) break
                    writer.write(buf, 0, n)
                }
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
        } finally {
            runCatching { reader.close() }
        }
        dest.name
    }.getOrNull()
}
