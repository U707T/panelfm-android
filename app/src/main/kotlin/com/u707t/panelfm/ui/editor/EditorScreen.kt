package com.u707t.panelfm.ui.editor

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.ui.browser.refreshAll
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文本编辑器（页面壳）。
 *
 * **v1.6.0 起换用 sora-editor 引擎**（LGPL-2.1，见 `third_party/`）：
 *  - 渲染 / 输入 / 大文本滚动由 sora 的虚拟化编辑器负责（自研 BasicTextField 版
 *    在几十万字符时整篇排版，是「点一下卡一下」的根因）；
 *  - 语法高亮 = TextMate 语法（增量着色），语法与主题见 [EditorLanguages]；
 *  - 查找 / 替换 = `EditorSearcher`，状态与交互收在 [EditorFindController]；
 *  - 大文件（> 2 MB）只读分段浏览（每段 512 KB、页与页严格相接），
 *    分页 / 保存 / 备份见 `EditorFileIo.kt`；
 *  - 顶栏与分页条见 `EditorChrome.kt`，菜单见 `EditorMenu.kt`，对话框见 `EditorDialogs.kt`。
 *
 * 本文件只做页面装配：状态 / 读取装载 / 行操作入口 / 编辑器视图 / 对话框编排。
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
    var languageDialog by remember { mutableStateOf(false) }
    var manualLanguage by remember { mutableStateOf(false) }
    var autoScope by remember { mutableStateOf<String?>(null) }
    var currentExt by remember { mutableStateOf("") }
    var wordwrap by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var gotoLineDialog by remember { mutableStateOf(false) }
    var gotoLineText by remember { mutableStateOf("") }

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
    val appliedWordwrap = remember { arrayOfNulls<Boolean>(1) }

    // 查找 / 替换：状态与 sora searcher 的交互全部收在控制器里
    val find = remember {
        EditorFindController(
            onStatus = { status = it },
            onHistory = { key, value -> scope.launch { container.prefs.addInputHistory(key, value) } },
            isReadOnly = { readOnly },
        )
    }

    val highlightActive = charCount <= HL_MAX_CHARS
    val commentPrefix = commentPrefixOf(scopeName, currentExt)
    val formatName = when (scopeName) {
        "source.json" -> "json"
        "text.xml" -> "xml"
        else -> null
    }
    val canFormat = formatName != null && CodeFormatter.supports(formatName)

    /** 让编辑器整体换一份文本（初次装载 / 翻段）。 */
    fun pushText(text: String) {
        textVersion += 1
        applyText = textVersion to text
        charCount = text.length
        lineTotal = text.count { it == '\n' } + 1
    }

    /** 「语法」选择：null = 自动识别；空串 = 纯文本；其余 = scope。按扩展名记忆。 */
    fun chooseLanguage(target: String?) {
        languageDialog = false
        if (target == null) {
            manualLanguage = false
            scopeName = autoScope
            scope.launch { container.prefs.setEditorLangOverride(currentExt, null) }
        } else {
            manualLanguage = true
            scopeName = target.ifEmpty { null }
            scope.launch { container.prefs.setEditorLangOverride(currentExt, target) }
        }
        wordwrap = defaultWordwrap(scopeName)
    }

    /** 离开编辑器：未保存时弹「保存 / 放弃 / 取消」，避免手势返回静默丢修改 */
    fun attemptLeave() {
        if (!dirty || readOnly) {
            onBack()
            return
        }
        confirmDiscard = true
    }

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

    /** 保存：正常保存与「保存并返回」共用同一条路径（成功都刷新目录）。 */
    fun saveAnd(onSaved: () -> Unit = {}) {
        val ed = editorHolder[0] ?: return
        scope.launch {
            saveText(container, uri, ed.text.toString(), charset, originalMode, { msg -> status = msg }) { ok ->
                if (ok) {
                    dirty = false
                    container.browser.refreshAll()
                    onSaved()
                }
            }
        }
    }

    /** 「复制行」：只写剪贴板，不改正文（只读分段下同样可用）。 */
    fun copyCurrentLine() {
        val line = lineTextOf(editorHolder[0])
        if (line == null) {
            status = "没有可复制的行"
            return
        }
        val copied = runCatching { clipboard.setText(AnnotatedString(line)) }.isSuccess
        status = if (copied) "已复制该行" else "复制到剪贴板失败"
    }

    /** 「剪切行」：被剪内容写入剪贴板，再从正文删除（与 MT 的 Cut line 对齐）。 */
    fun cutCurrentLine() {
        val ed = editorHolder[0] ?: return
        val payload = lineTextOf(ed)
        applyWholeTextOp(ed, "剪切行") { t, i -> LineOps.cutLine(t, i).first }
        if (!payload.isNullOrEmpty()) {
            runCatching { clipboard.setText(AnnotatedString(payload)) }
        }
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
            val fileName = info.name.ifEmpty { uri.name }
            val auto = EditorLanguages.scopeOf(fileName)
            autoScope = auto
            currentExt = fileName.substringAfterLast('.', "").lowercase()
            // 「语法」手动选择按扩展名记忆（设置里持久化）；没有记录 = 自动识别
            val override = container.settings.value.editorLangOverrides[currentExt]
            manualLanguage = override != null
            scopeName = override ?: auto
            wordwrap = defaultWordwrap(override ?: auto)
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

    /** 翻到指定段（0 起）；会话断开时自动重连一次。 */
    fun goPage(target: Int) {
        if (pageLoading) return
        val info = meta ?: return
        val t = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        scope.launch {
            pageLoading = true
            try {
                val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
                val slice = loadPage(vfs, uri, t, info.size, charset)
                page = t
                pageRange = slice.start to slice.end
                pushText(slice.text)
            } catch (e: Exception) {
                status = "读取分段失败：${e.message ?: "会话不可用"}"
            } finally {
                pageLoading = false
            }
        }
    }

    val editorSettings = container.settings.collectAsState().value
    val findHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_FIND].orEmpty()
    val replaceHistory = editorSettings.inputHistory[PrefsStore.RecordKeys.EDITOR_REPLACE].orEmpty()

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        EditorTopBar(
            title = (meta?.name ?: uri.name) + if (dirty) " *" else "",
            onBack = { attemptLeave() },
            canSave = !readOnly && dirty,
            onSave = { saveAnd() },
            onFontSizeDelta = { delta -> fontSize = (fontSize + delta).coerceIn(10, 28) },
            onToggleFind = { if (find.showFind) find.close(editorHolder[0]) else find.showFind = true },
            languageLabel = EditorLanguages.labelOf(scopeName) ?: "纯文本",
            languageManual = manualLanguage,
            canToggleComment = commentPrefix != null,
            canFormat = canFormat,
            wordwrap = wordwrap,
            readOnly = readOnly,
            onLanguage = { languageDialog = true },
            onToggleWordwrap = { wordwrap = !wordwrap },
            onUndo = { editorHolder[0]?.undo() },
            onRedo = { editorHolder[0]?.redo() },
            onCopyLine = { copyCurrentLine() },
            onCutLine = { cutCurrentLine() },
            onLineOp = { label, op -> editorHolder[0]?.let { applyWholeTextOp(it, label, op) } },
            onToggleComment = {
                val ed = editorHolder[0]
                val prefix = commentPrefix
                if (prefix == null) {
                    status = "当前语言不支持切换注释"
                } else if (ed != null) {
                    applyWholeTextOp(ed, "切换注释") { t, _ -> LineOps.toggleComment(t, prefix) }
                }
            },
            onCompress = {
                editorHolder[0]?.let {
                    applyWholeTextOp(it, "压缩代码（去行尾空白 / 首尾空行）") { t, _ -> LineOps.trimTrailingWhitespace(t) }
                }
            },
            onFormat = {
                val ed = editorHolder[0]
                if (ed != null && formatName != null) {
                    val formatted = CodeFormatter.format(ed.text.toString(), formatName)
                    if (formatted == null) {
                        status = "格式化失败：内容不是有效的 ${formatName.uppercase()}"
                    } else {
                        applyWholeTextOp(ed, "已格式化代码") { _, _ -> formatted }
                    }
                }
            },
            onGotoLine = {
                gotoLineText = ((editorHolder[0]?.cursor?.leftLine ?: 0) + 1).toString()
                gotoLineDialog = true
            },
        )

        // ---- 大文件分段浏览控制条
        if (paged) {
            EditorPageBar(
                page = page,
                pageCount = pageCount,
                rangeStart = pageRange.first,
                rangeEnd = pageRange.second,
                loading = pageLoading,
                onGo = { goPage(it) },
            )
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
                                    postInLifecycle { find.onResultsPublished(this) }
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
                            if (appliedWordwrap[0] != wordwrap) {
                                appliedWordwrap[0] = wordwrap
                                ed.setWordwrap(wordwrap)
                            }
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
                            append(" · " + (EditorLanguages.labelOf(scopeName) ?: "纯文本"))
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

        if (find.showFind) {
            EditorSearchBar(
                findText = find.findText,
                onFindTextChange = { find.findText = it },
                replaceText = find.replaceText,
                onReplaceTextChange = { find.replaceText = it },
                findHistory = findHistory,
                replaceHistory = replaceHistory,
                readOnly = readOnly,
                options = EditorSearchOptionsState(find.useRegex, find.matchCase, find.wholeWord),
                optionsMenuExpanded = find.optionsMenuExpanded,
                onOptionsMenuExpandedChange = { find.optionsMenuExpanded = it },
                onToggleRegex = { find.useRegex = it; find.invalidateQuery() },
                onToggleMatchCase = { find.matchCase = it; find.invalidateQuery() },
                onToggleWholeWord = { find.wholeWord = it; find.invalidateQuery() },
                onPrevious = { editorHolder[0]?.let { find.jump(it, backward = true) } },
                onNext = { editorHolder[0]?.let { find.jump(it, backward = false) } },
                onReplace = { editorHolder[0]?.let { find.replaceCurrent(it) } },
                onReplaceAll = { editorHolder[0]?.let { find.replaceAll(it) } },
            )
        }
    }

    if (languageDialog) {
        EditorLanguageDialog(
            selectedScope = scopeName,
            autoScope = autoScope,
            manual = manualLanguage,
            onPick = { chooseLanguage(it) },
            onDismiss = { languageDialog = false },
        )
    }

    if (gotoLineDialog) {
        EditorGotoLineDialog(
            text = gotoLineText,
            onTextChange = { gotoLineText = it },
            lineTotal = lineTotal,
            editor = editorHolder[0],
            onStatus = { status = it },
            onDismiss = { gotoLineDialog = false },
        )
    }

    if (confirmDiscard) {
        EditorDiscardDialog(
            fileName = meta?.name ?: uri.name,
            onSaveAndLeave = { confirmDiscard = false; saveAnd { onBack() } },
            onDiscard = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** 取编辑器当前行文本（剪贴板用）；无实例 / 越界返回 null。 */
private fun lineTextOf(ed: CodeEditor?): String? =
    ed?.let { runCatching { it.text.getLineString(it.cursor.leftLine) }.getOrNull() }

/** 「尚未设置过语言」的哨兵（用来区分「没设置」与「设置成纯文本」）。 */
private val NoLanguage = Any()
