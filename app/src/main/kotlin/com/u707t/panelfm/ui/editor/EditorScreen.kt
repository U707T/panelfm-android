package com.u707t.panelfm.ui.editor

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.CodeFormatter
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.LineOps
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtIconButton
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.regex.PatternSyntaxException

/**
 * 文本编辑器。
 *
 * **v1.6.0 起换用 sora-editor 引擎**（LGPL-2.1，见 `third_party/`）：
 *  - 渲染 / 输入 / 大文本滚动由 sora 的虚拟化编辑器负责（自研 BasicTextField 版
 *    在几十万字符时整篇排版，是「点一下卡一下」的根因）；
 *  - 语法高亮 = TextMate 语法（增量着色，不再整篇重算），语法/主题见 [EditorLanguages]；
 *  - 查找/替换 = `EditorSearcher`（后台线程搜索、全部命中高亮、正则/大小写/全词）；
 *  - 本文件只保留页面壳：编码识别 / 保存（含 .bak 备份）/ 行操作 / 大文件分段浏览 / 顶部菜单。
 *
 * 大文件（> 2 MB）仍是**只读分段浏览**（每段 512 KB），只是同样交给 sora 渲染。
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dark = LocalPanelDarkTheme.current
    // 编辑器配色（在组合里取好再传进 AndroidView.update —— update 不是 @Composable，读不了 MaterialTheme）
    val editorBackground = MaterialTheme.colorScheme.background.toArgb()
    val editorGutterText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val editorCurrentLine = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(14) }
    var dirty by remember { mutableStateOf(false) }
    var originalMode by remember { mutableStateOf<Int?>(null) }
    var scopeName by remember { mutableStateOf<String?>(null) }
    var charCount by remember { mutableIntStateOf(0) }
    var lineTotal by remember { mutableIntStateOf(1) }
    var languagesReady by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var gotoLineDialog by remember { mutableStateOf(false) }
    var gotoLineText by remember { mutableStateOf("") }

    // 查找 / 替换
    var showFind by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(true) }
    var wholeWord by remember { mutableStateOf(false) }
    var findOptionsMenu by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf<SearchSpec?>(null) }
    var pendingAfterSearch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var searchResultsReady by remember { mutableStateOf(false) }
    var suppressSearchStatus by remember { mutableStateOf(false) }

    // 大文件只读分段浏览
    var paged by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageRange by remember { mutableStateOf(0L to 0L) }
    var pageLoading by remember { mutableStateOf(false) }

    // 编辑器实例 + 「待灌入文本」。用普通容器（非 Compose state）避免在组合期写状态。
    val editorHolder = remember { arrayOfNulls<CodeEditor>(1) }
    var applyText by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var textVersion by remember { mutableIntStateOf(0) }
    val appliedVersion = remember { intArrayOf(-1) }
    val appliedLanguage = remember { arrayOf<Any?>(NoLanguage) }
    val appliedDark = remember { arrayOfNulls<Boolean>(1) }

    val highlightActive = charCount <= HL_MAX_CHARS

    /** 让编辑器整体换一份文本（初次装载 / 翻段）。 */
    fun pushText(text: String) {
        textVersion += 1
        applyText = textVersion to text
        charCount = text.length
        lineTotal = text.count { it == '\n' } + 1
    }

    /** 离开编辑器：未保存时弹「保存 / 放弃 / 取消」，避免手势返回静默丢修改 */
    fun attemptLeave() {
        if (!dirty || readOnly) {
            onBack()
            return
        }
        confirmDiscard = true
    }

    BackHandler(enabled = true) { attemptLeave() }

    // ---- 首次进入：后台加载语法/主题注册表（失败则退化为纯文本，不影响编辑）
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { EditorLanguages.ensureInitialized(context) }
        }
        languagesReady = true
    }

    // ---- 读取文件（编码识别 / 大文件分段）
    LaunchedEffect(uri) {
        try {
            val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
            val info = withContext(Dispatchers.IO) { vfs.stat(uri) }
            meta = info
            originalMode = info.permissions
            scopeName = EditorLanguages.scopeOf(info.name.ifEmpty { uri.name })
            if (info.size > MAX_EDIT_SIZE) {
                readOnly = true
                paged = true
                pageCount = ((info.size + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
                val slice = loadPage(vfs, uri, 0, info.size, charset = null)
                charset = slice.charset
                page = 0
                pageRange = slice.start to slice.end
                pushText(slice.text)
            } else {
                val bytes = readAtMost(vfs, uri, MAX_EDIT_SIZE)
                val decoded = TextEncodings.decode(bytes)
                charset = decoded.charset
                pushText(decoded.text)
            }
            dirty = false
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        } finally {
            loading = false
        }
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
                page = t
                pageRange = slice.start to slice.end
                pushText(slice.text)
            } catch (e: Exception) {
                status = "读取分段失败：${e.message}"
            } finally {
                pageLoading = false
            }
        }
    }

    // ---------------- 查找 / 替换（sora EditorSearcher：后台搜索 + 全部命中高亮）

    fun notFoundMessage(): String = when {
        useRegex && matchCase && wholeWord -> "找不到文本（已开启正则表达式、全词匹配和区分大小写）"
        useRegex && matchCase -> "找不到文本（已开启正则表达式和区分大小写）"
        useRegex && wholeWord -> "找不到文本（已开启正则表达式和全词匹配）"
        useRegex -> "找不到文本（已开启正则表达式）"
        wholeWord && matchCase -> "找不到文本（已开启全词匹配和区分大小写）"
        wholeWord -> "找不到文本（已开启全词匹配）"
        matchCase -> "找不到文本（已开启区分大小写）"
        else -> "找不到文本"
    }

    fun currentSpec(pattern: String): SearchSpec = SearchSpec(pattern, useRegex, wholeWord, matchCase)

    fun startSearch(ed: CodeEditor, spec: SearchSpec): Boolean {
        val options = when {
            spec.useRegex -> EditorSearcher.SearchOptions(
                EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION,
                !spec.matchCase,
                RegexBackrefGrammar.DEFAULT,
            )
            spec.wholeWord -> EditorSearcher.SearchOptions(
                EditorSearcher.SearchOptions.TYPE_WHOLE_WORD,
                !spec.matchCase,
            )
            else -> EditorSearcher.SearchOptions(
                EditorSearcher.SearchOptions.TYPE_NORMAL,
                !spec.matchCase,
            )
        }
        return try {
            ed.searcher.search(spec.pattern, options)
            lastQuery = spec
            searchResultsReady = false
            true
        } catch (e: PatternSyntaxException) {
            // MT 0x7f110602
            status = "正则表达式有误"
            false
        } catch (e: Exception) {
            status = "查找失败：${e.message}"
            false
        }
    }

    fun refreshSearchStatus(ed: CodeEditor) {
        val searcher = ed.searcher
        if (!searcher.hasQuery()) return
        if (!searchResultsReady) {
            // 结果还没算完：matchedPositionCount 会返回 0，不能当成「找不到」
            status = "查找中…"
            return
        }
        val count = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        val index = runCatching { searcher.currentMatchedPositionIndex }.getOrDefault(-1)
        status = when {
            count == 0 -> notFoundMessage()
            index >= 0 -> "第 ${index + 1} / $count 处"
            else -> "共 $count 处"
        }
    }

    fun jump(ed: CodeEditor, backward: Boolean) {
        if (findText.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        val spec = currentSpec(findText)
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val jumpNow: () -> Unit = {
            runCatching { if (backward) ed.searcher.gotoPrevious() else ed.searcher.gotoNext() }
            refreshSearchStatus(ed)
        }
        if (fresh) pendingAfterSearch = jumpNow else jumpNow()
        scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_FIND, spec.pattern) }
    }

    fun replaceCurrent(ed: CodeEditor) {
        if (readOnly) return
        if (findText.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        val spec = currentSpec(findText)
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val replaceNow: () -> Unit = {
            // 选区正好是命中 → 替换；否则 sora 会先跳到下一个（与 MT 的操作习惯一致）
            runCatching { ed.searcher.replaceCurrentMatch(replaceText) }
            refreshSearchStatus(ed)
        }
        if (fresh) pendingAfterSearch = replaceNow else replaceNow()
        scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replaceText) }
    }

    fun replaceAll(ed: CodeEditor) {
        if (readOnly) return
        if (findText.isEmpty()) {
            status = "请输入查找内容"
            return
        }
        val spec = currentSpec(findText)
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val replaceNow: () -> Unit = {
            val total = runCatching { ed.searcher.matchedPositionCount }.getOrDefault(0)
            val started = runCatching {
                ed.searcher.replaceAll(replaceText) {
                    suppressSearchStatus = true
                    status = "已替换 $total 处"
                }
            }.isSuccess
            if (!started) refreshSearchStatus(ed)
        }
        if (fresh) pendingAfterSearch = replaceNow else replaceNow()
        scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replaceText) }
    }

    fun closeFind(ed: CodeEditor?) {
        showFind = false
        findOptionsMenu = false
        lastQuery = null
        pendingAfterSearch = null
        searchResultsReady = false
        ed?.searcher?.stopSearch()
    }

    // ---------------- 行操作（MT 菜单 0x7f0e001b）

    /**
     * 把 [op] 作用在编辑器全文上并写回。
     *
     * 细节：LineOps 以 `\n` 为行分隔，而文档可能是 CRLF —— 计算时先归一成 LF，
     * 写回前再还原，避免「做一次行操作，整个文件的换行符被改掉」。
     */
    fun applyWholeTextOp(ed: CodeEditor, label: String, op: (String, Int) -> String) {
        if (readOnly) return
        val before = ed.text.toString()
        val cursorLine = ed.cursor.leftLine
        val crlf = before.contains("\r\n")
        val source = if (crlf) before.replace("\r\n", "\n") else before
        val result = op(source, cursorLine)
        if (result == source) {
            status = "「$label」没有可操作的内容"
            return
        }
        val out = if (crlf) result.replace("\n", "\r\n") else result
        val lastLine = ed.lineCount - 1
        ed.text.replace(0, 0, lastLine, ed.text.getColumnCount(lastLine), out)
        ed.setSelection(cursorLine.coerceIn(0, (ed.lineCount - 1).coerceAtLeast(0)), 0, true)
        status = label
    }

    val commentPrefix = commentPrefixOf(scopeName)

    // ---------------- 菜单动作

    fun save(ed: CodeEditor?) {
        val target = ed ?: return
        scope.launch {
            saveText(container, uri, target.text.toString(), charset, originalMode, { msg -> status = msg }) { ok ->
                if (ok) {
                    dirty = false
                    container.browser.refreshAll()
                }
            }
        }
    }

    val editorSettings = container.settings.collectAsState().value
    val findHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_FIND].orEmpty()
    val replaceHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_REPLACE].orEmpty()

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = (meta?.name ?: uri.name) + if (dirty) " *" else "",
            onBack = { attemptLeave() },
        ) {
            Box {
                MtIconButton(
                    icon = MtIcon.MORE,
                    contentDescription = "更多菜单",
                    onClick = { showMenu = true },
                )
                EditorMenu(
                    expanded = showMenu,
                    canToggleComment = commentPrefix != null,
                    readOnly = readOnly,
                    onDismiss = { showMenu = false },
                    onUndo = { showMenu = false; editorHolder[0]?.undo() },
                    onRedo = { showMenu = false; editorHolder[0]?.redo() },
                    onSave = { showMenu = false; save(editorHolder[0]) },
                    onLineOp = { label, op ->
                        showMenu = false
                        editorHolder[0]?.let { applyWholeTextOp(it, label, op) }
                    },
                    onToggleComment = {
                        showMenu = false
                        val ed = editorHolder[0]
                        val prefix = commentPrefix
                        if (prefix == null) {
                            status = "当前语言不支持切换注释"
                        } else if (ed != null) {
                            applyWholeTextOp(ed, "切换注释") { t, _ -> LineOps.toggleComment(t, prefix) }
                        }
                    },
                    onCompress = {
                        showMenu = false
                        editorHolder[0]?.let {
                            applyWholeTextOp(it, "压缩代码（去行尾空白 / 首尾空行）") { t, _ -> LineOps.trimTrailingWhitespace(t) }
                        }
                    },
                    onFormat = {
                        showMenu = false
                        val ed = editorHolder[0]
                        if (ed != null) {
                            val format = if (scopeName == "text.xml") "xml" else "json"
                            val formatted = CodeFormatter.format(ed.text.toString(), format)
                            if (formatted == null) {
                                status = "暂不支持该语言的格式化（当前支持 JSON / XML）"
                            } else {
                                applyWholeTextOp(ed, "已格式化代码") { _, _ -> formatted }
                            }
                        }
                    },
                    onGotoLine = {
                        showMenu = false
                        gotoLineText = ((editorHolder[0]?.cursor?.leftLine ?: 0) + 1).toString()
                        gotoLineDialog = true
                    },
                    onToggleFind = {
                        showMenu = false
                        if (showFind) closeFind(editorHolder[0]) else showFind = true
                    },
                )
            }
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A-") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28) }) { Text("A+") }
            TextButton(onClick = {
                if (showFind) closeFind(editorHolder[0]) else showFind = true
            }) { Text("查找") }
            TextButton(
                enabled = !readOnly && dirty,
                onClick = { save(editorHolder[0]) },
            ) {
                Text(
                    "保存",
                    color = if (!readOnly && dirty) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (readOnly) {
                        Text(
                            if (paged) "只读：文件大于 ${Fmt.size(MAX_EDIT_SIZE)}，已进入分段浏览（每段 ${Fmt.size(PAGE_SIZE)}）"
                            else "只读：文件大于 ${Fmt.size(MAX_EDIT_SIZE)}（可另存或复制到本地后编辑）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                    AndroidView(
                        factory = { ctx ->
                            CodeEditor(ctx).apply {
                                setTypefaceText(Typeface.MONOSPACE)
                                setLineNumberEnabled(true)
                                setWordwrap(false)
                                setTabWidth(4)
                                setTextSize(fontSize.toFloat())
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                isEditable = false
                                // 编辑即「已修改」；整体换文本（装载/翻段）不算
                                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                                    if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                                        dirty = true
                                        charCount = text.length
                                        lineTotal = this.lineCount
                                    }
                                }
                                // 搜索结果就绪（主线程派发；再延后一帧，保证 lastResults 已生效）
                                subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
                                    postInLifecycle {
                                        searchResultsReady = true
                                        val pending = pendingAfterSearch
                                        if (pending != null) {
                                            pendingAfterSearch = null
                                            pending()
                                        } else if (suppressSearchStatus) {
                                            suppressSearchStatus = false
                                        } else {
                                            refreshSearchStatus(this)
                                        }
                                    }
                                }
                                editorHolder[0] = this
                            }
                        },
                        update = { ed ->
                            if (languagesReady) {
                                val effective = if (highlightActive) scopeName else null
                                if (appliedLanguage[0] == NoLanguage || appliedLanguage[0] != effective) {
                                    appliedLanguage[0] = effective
                                    EditorLanguages.applyLanguage(ed, effective)
                                }
                                if (appliedDark[0] != dark) {
                                    appliedDark[0] = dark
                                    ed.colorScheme = EditorLanguages.createColorScheme(
                                        dark = dark,
                                        background = editorBackground,
                                        gutterText = editorGutterText,
                                        currentLine = editorCurrentLine,
                                    )
                                }
                            }
                            ed.setTextSize(fontSize.toFloat())
                            ed.isEditable = !readOnly
                            applyText?.let { (version, text) ->
                                if (appliedVersion[0] != version) {
                                    appliedVersion[0] = version
                                    ed.setText(text)
                                    ed.setSelection(0, 0, true)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        buildString {
                            append("$lineTotal 行 · $charCount 字符 · $charset")
                            EditorLanguages.labelOf(scopeName)?.let { append(" · $it") }
                            if (paged) append(" · 分段 ${page + 1}/$pageCount")
                            meta?.size?.let { append(" · ${Fmt.size(it)}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (showFind) {
            EditorSearchBar(
                findText = findText,
                onFindTextChange = { findText = it },
                replaceText = replaceText,
                onReplaceTextChange = { replaceText = it },
                findHistory = findHistory,
                replaceHistory = replaceHistory,
                readOnly = readOnly,
                options = EditorSearchOptionsState(useRegex, matchCase, wholeWord),
                optionsMenuExpanded = findOptionsMenu,
                onOptionsMenuExpandedChange = { findOptionsMenu = it },
                onToggleRegex = { useRegex = it; lastQuery = null },
                onToggleMatchCase = { matchCase = it; lastQuery = null },
                onToggleWholeWord = { wholeWord = it; lastQuery = null },
                onPrevious = { editorHolder[0]?.let { jump(it, backward = true) } },
                onNext = { editorHolder[0]?.let { jump(it, backward = false) } },
                onReplace = { editorHolder[0]?.let { replaceCurrent(it) } },
                onReplaceAll = { editorHolder[0]?.let { replaceAll(it) } },
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
                        "共 $lineTotal 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = gotoLineText.trim().toIntOrNull()
                    val ed = editorHolder[0]
                    if (target == null || target < 1 || ed == null) {
                        status = "请输入有效的行号"
                    } else if (target > ed.lineCount) {
                        status = "行号超出范围（共 ${ed.lineCount} 行）"
                    } else {
                        ed.setSelection(target - 1, 0, true)
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
                        val ed = editorHolder[0] ?: return@launch
                        saveText(container, uri, ed.text.toString(), charset, originalMode, { msg -> status = msg }) { ok ->
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

/** 「尚未设置过语言」的哨兵（用来区分「没设置」与「设置成纯文本」）。 */
private val NoLanguage = Any()

/** 查找/替换的查询快照（用于判断「条件是否变化」）。 */
private data class SearchSpec(
    val pattern: String,
    val useRegex: Boolean,
    val wholeWord: Boolean,
    val matchCase: Boolean,
)

/** 编辑器 ⋮ 菜单（复刻 MT 0x7f0e001b：撤销/重做 + 行操作 + 代码整理） */
@Composable
private fun EditorMenu(
    expanded: Boolean,
    canToggleComment: Boolean,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
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
        DropdownMenuItem(text = { Text("↶  撤销") }, onClick = onUndo, enabled = !readOnly)
        DropdownMenuItem(text = { Text("↷  重做") }, onClick = onRedo, enabled = !readOnly)
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
        DropdownMenuItem(
            text = { Text("//  切换注释" + if (canToggleComment) "" else "（当前语言不支持）") },
            onClick = onToggleComment,
        )
        Text(
            "代码整理",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        DropdownMenuItem(text = { Text("🗜  压缩代码（去空白）") }, onClick = onCompress)
        DropdownMenuItem(text = { Text("✨  格式化代码") }, onClick = onFormat)
    }
}

/** 按 scope 给行注释前缀（不支持的语言返回 null） */
internal fun commentPrefixOf(scope: String?): String? = when (scope) {
    "source.kotlin", "source.java", "source.js", "source.ts", "source.css" -> "//"
    "source.python", "source.shell", "source.yaml" -> "#"
    else -> null
}

// ------------------------------------------------------------------ 分段浏览

private const val MAX_EDIT_SIZE = 2L * 1024 * 1024

/** 大文件分段大小与读取冗余（对齐行边界用） */
private const val PAGE_SIZE = 512L * 1024
private const val PAGE_SLACK = 64L * 1024

/** 语法高亮字符上限（超过则退化为纯文本，保证输入流畅） */
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
 * 复制到不冲突的 `.bak` / `.bak.1` / …（复用 [AppContainer.uniqueChild]），
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
