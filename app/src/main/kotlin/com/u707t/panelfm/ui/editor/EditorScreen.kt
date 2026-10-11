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
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.ui.ErrorState
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import com.u707t.panelfm.ui.browser.refreshAll
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文本编辑器（页面壳）。
 *
 * **v1.6.0 起换用 sora-editor 引擎**（LGPL-2.1，见 `third_party/`）；
 * **v2.0.15 起菜单 / 功能对齐 NP管理器编辑器**（`edit_menu.xml`、`layout_edit_search.xml`、
 * `activity_edit.xml`、`fe_layout_*.xml`），见 `docs/EDITOR-ENGINE.md`：
 *  - 渲染 / 输入 / 大文本滚动由 sora 的虚拟化编辑器负责；
 *  - 语法高亮 = TextMate 语法（22 种），配色可跟随应用或固定浅 / 深主题；
 *  - 查找 / 替换 = `EditorSearcher`，状态与交互收在 [EditorFindController]；
 *  - **v2.0.16 起没有只读分段浏览**：文本文件一律整篇进编辑器（NP / MT 同款），
 *    大文件靠「超过 2 MB 自动开流畅模式 / 超过 16 MB 先确认 / 超过 64 MB 拒绝」兜底
 *    （判定见 `EditorFileIo.editorOpenPlan`）；
 *  - 菜单内容由 [editorMenuSections] 纯模型产出，顶栏见 `EditorChrome.kt`，
 *    对话框见 `EditorDialogs.kt`，符号输入栏见 `EditorSymbolBar.kt`。
 *
 * 本文件只做页面装配：状态 / 读取装载 / 动作分发 / 编辑器视图 / 对话框编排。
 */
@Composable
fun EditorScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dark = LocalPanelDarkTheme.current
    val settings = container.settings.collectAsState().value

    // 编辑器配色（在组合里取好再传进 AndroidView.update —— update 不是 @Composable，读不了 MaterialTheme）
    val editorBackground = MaterialTheme.colorScheme.background.toArgb()
    val editorGutterText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val editorCurrentLine = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    // ---- 编辑器偏好（NP「设置」子菜单；落在 PrefsStore，跨文件 / 跨会话保留）
    val fontSize = settings.editorFontSize
    val showWhitespace = settings.editorShowWhitespace
    val monospace = settings.editorMonospace
    val symbolBar = settings.editorSymbolBar
    val indentGuide = settings.editorIndentGuide
    val magnifier = settings.editorMagnifier
    val editorTheme = settings.editorTheme

    var meta by remember { mutableStateOf<FileMetadata?>(null) }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
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
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    /** 「流畅模式」自动档的结论（文件大于阈值 → 开）；用户显式设置优先（见 [fluent]）。 */
    var autoFluent by remember { mutableStateOf(false) }

    // 对话框 / 确认框
    var confirmDiscard by remember { mutableStateOf(false) }
    var gotoLineDialog by remember { mutableStateOf(false) }
    var gotoLineText by remember { mutableStateOf("") }
    var charsetReloadDialog by remember { mutableStateOf(false) }
    var charsetSaveDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var fontSizeDialog by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmLargeOpen by remember { mutableStateOf(false) }
    var pendingOpenSize by remember { mutableStateOf(0L) }
    var pendingSaveCharset by remember { mutableStateOf<String?>(null) }
    var pendingSaveDone by remember { mutableStateOf<() -> Unit>({}) }
    var pendingReloadCharset by remember { mutableStateOf<String?>(null) }

    // 编辑器实例 + 「待灌入文本」。用普通容器（非 Compose state）避免在组合期写状态。
    val editorHolder = remember { arrayOfNulls<CodeEditor>(1) }
    var applyText by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var textVersion by remember { mutableIntStateOf(0) }
    val appliedVersion = remember { intArrayOf(-1) }
    val appliedLanguage = remember { arrayOf<Any?>(NoLanguage) }
    val appliedSchemeKey = remember { arrayOfNulls<String>(1) }
    val appliedWordwrap = remember { arrayOfNulls<Boolean>(1) }
    val appliedWhitespace = remember { arrayOfNulls<Boolean>(1) }
    val appliedMonospace = remember { arrayOfNulls<Boolean>(1) }
    val appliedIndentGuide = remember { arrayOfNulls<Boolean>(1) }
    val appliedMagnifier = remember { arrayOfNulls<Boolean>(1) }
    val appliedFluent = remember { arrayOfNulls<Boolean>(1) }
    val appliedFontSize = remember { intArrayOf(-1) }

    // 查找 / 替换：状态与 sora searcher 的交互全部收在控制器里
    val find = remember {
        EditorFindController(
            onStatus = { status = it },
            onHistory = { key, value -> scope.launch { container.prefs.addInputHistory(key, value) } },
        )
    }
    var findFocus by remember { mutableStateOf(EditorSearchFocus.FIND) }

    val highlightActive = charCount <= HL_MAX_CHARS
    val commentPrefix = commentPrefixOf(scopeName, currentExt)
    val validateKind = EditorValidate.kindOf(scopeName)
    val formatName = when (scopeName) {
        "source.json" -> "json"
        "text.xml" -> "xml"
        else -> null
    }
    val canFormat = formatName != null && CodeFormatter.supports(formatName)

    /**
     * 是否处于流畅模式：用户显式选过（`settings.editorFluentMode`）就听用户的，
     * 否则用「按文件大小自动」的结论（[autoFluent]）。流畅模式关掉语法高亮，是大文件最贵的一环。
     */
    val fluent = settings.editorFluentMode ?: autoFluent
    val effectiveScope = if (fluent || !highlightActive) null else scopeName

    /** 让编辑器整体换一份文本（初次装载 / 重新加载）。 */
    fun pushText(text: String) {
        textVersion += 1
        applyText = textVersion to text
        charCount = text.length
        lineTotal = text.count { it == '\n' } + 1
        // setText 会清空撤销栈：可用态在这里同步（不能在 AndroidView.update 里写状态）
        canUndo = false
        canRedo = false
    }

    /** 撤销 / 重做按钮的可用态（编辑器事件与各动作后都要同步）。 */
    fun syncUndoState(ed: CodeEditor?) {
        canUndo = ed?.canUndo() == true
        canRedo = ed?.canRedo() == true
    }

    /** 自动开流畅模式时的状态栏说明（用户不知道发生了什么会很困惑）。 */
    fun autoFluentNotice(): String =
        "文件较大（> ${Fmt.size(AUTO_FLUENT_BYTES)}），已自动开启流畅模式（⋮ → 视图可关）"

    /** 读取整篇并灌入编辑器（首次打开 / 确认后打开 / 重新加载共用）。 */
    suspend fun openWhole(vfs: VirtualFileSystem, charsetOverride: String?) {
        val decoded = readWholeText(vfs, uri, charsetOverride)
        charset = decoded.charset
        pushText(decoded.text)
        dirty = false
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
        // 用户没显式设过「自动换行」时，换语言就跟着语言默认走（Markdown / 纯文本默认开）
        if (settings.editorWordwrap == null) wordwrap = defaultWordwrap(scopeName)
    }

    /** 离开编辑器：未保存时弹「保存 / 放弃 / 取消」，避免手势返回静默丢修改 */
    fun attemptLeave() {
        if (!dirty) {
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
        syncUndoState(ed)
        status = label
    }

    /** 光标移到某行行首 / 行尾（NP `jump2header` / `jump2end`）；行号越界自动夹取。 */
    fun moveCaret(ed: CodeEditor, line: Int, toLineEnd: Boolean) {
        val target = line.coerceIn(0, (ed.lineCount - 1).coerceAtLeast(0))
        val column = if (toLineEnd) ed.text.getColumnCount(target) else 0
        ed.setSelection(target, column, true)
    }

    /** 实际写盘（[useCharset] = 显式编码；正常保存传当前 charset）。 */
    fun performSave(ed: CodeEditor, useCharset: String, onSaved: () -> Unit = {}) {
        scope.launch {
            val outcome = saveText(container, uri, ed.text.toString(), useCharset, originalMode)
            status = saveStatusText(outcome, useCharset)
            if (outcome.ok) {
                charset = outcome.charset
                dirty = false
                // 刷新元数据：它是「文件是否被外部改动」的判定基准
                meta = statQuietly(container, uri) ?: meta
                container.browser.refreshAll()
                onSaved()
            }
        }
    }

    /**
     * 保存入口：先看磁盘上的文件是否被外部改动过（NP `save_inzip_tips` 同款提示），
     * 改过就弹确认（避免静默覆盖别人的改动），没改过直接写。
     */
    fun requestSave(useCharset: String? = null, onSaved: () -> Unit = {}) {
        val ed = editorHolder[0] ?: return
        scope.launch {
            val before = meta
            val now = statQuietly(container, uri)
            if (before != null && fileChangedSince(before, now)) {
                pendingSaveCharset = useCharset
                pendingSaveDone = onSaved
                confirmOverwrite = true
                return@launch
            }
            performSave(ed, useCharset ?: charset, onSaved)
        }
    }

    /**
     * 重新加载：整篇重读（[charsetOverride] 非空 = NP「指定编码重新加载」，空 = 自动识别）。
     * 文件在此期间涨到打开上限之外时取消（不硬撑出 OOM）。
     */
    fun reload(charsetOverride: String?) {
        val override = charsetOverride?.takeIf { it.isNotEmpty() }
        scope.launch {
            try {
                val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
                val info = withContext(Dispatchers.IO) { vfs.stat(uri) }
                if (editorOpenPlan(info.size).decision == EditorOpenDecision.REFUSE) {
                    status = "文件已超过 ${Fmt.size(OPEN_LIMIT_BYTES)}，已取消重新加载"
                    return@launch
                }
                meta = info
                originalMode = info.permissions
                autoFluent = editorOpenPlan(info.size).autoFluent
                openWhole(vfs, override)
                status = "已重新加载（$charset）"
            } catch (e: Exception) {
                status = "重新加载失败：${e.message ?: "会话不可用"}"
            }
        }
    }

    /** 重新加载入口：有未保存修改时先确认（修改会被丢弃）。 */
    fun requestReload(charsetOverride: String?) {
        if (dirty) {
            pendingReloadCharset = charsetOverride
            confirmReload = true
            return
        }
        reload(charsetOverride)
    }

    /** 「复制行」：只写剪贴板，不改正文。 */
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

    /** 符号输入栏：`⇥` 走编辑器的缩进（与键盘 Tab 一致），其余按文本插入。 */
    fun insertSymbol(value: String) {
        val ed = editorHolder[0] ?: return
        if (value == "\t") ed.indentOrCommitTab() else ed.insertText(value, 1)
    }

    /** 菜单动作分发（内容见 [editorMenuSections]）。 */
    fun onMenuAction(action: EditorMenuAction) {
        val ed = editorHolder[0]
        when (action) {
            EditorMenuAction.SAVE -> requestSave()
            EditorMenuAction.RELOAD -> requestReload(null)
            EditorMenuAction.EXIT -> attemptLeave()
            EditorMenuAction.UNDO -> {
                ed?.undo()
                syncUndoState(ed)
            }
            EditorMenuAction.REDO -> {
                ed?.redo()
                syncUndoState(ed)
            }
            EditorMenuAction.COPY_LINE -> copyCurrentLine()
            EditorMenuAction.CUT_LINE -> cutCurrentLine()
            EditorMenuAction.DELETE_LINE ->
                ed?.let { applyWholeTextOp(it, "删除行") { t, i -> LineOps.deleteLine(t, i) } }
            EditorMenuAction.CLEAR_LINE ->
                ed?.let { applyWholeTextOp(it, "清空行") { t, i -> LineOps.clearLine(t, i) } }
            EditorMenuAction.DUPLICATE_LINE ->
                ed?.let { applyWholeTextOp(it, "重复行") { t, i -> LineOps.duplicateLine(t, i) } }
            EditorMenuAction.TO_UPPER ->
                ed?.let { applyWholeTextOp(it, "转为大写") { t, _ -> LineOps.toUpperCase(t) } }
            EditorMenuAction.TO_LOWER ->
                ed?.let { applyWholeTextOp(it, "转为小写") { t, _ -> LineOps.toLowerCase(t) } }
            EditorMenuAction.INDENT ->
                ed?.let { applyWholeTextOp(it, "增加缩进") { t, _ -> LineOps.indent(t) } }
            EditorMenuAction.UNINDENT ->
                ed?.let { applyWholeTextOp(it, "减小缩进") { t, _ -> LineOps.unindent(t) } }
            EditorMenuAction.TOGGLE_COMMENT -> {
                val prefix = commentPrefix
                when {
                    prefix == null -> status = "当前语言不支持切换注释"
                    ed != null -> applyWholeTextOp(ed, "切换注释") { t, _ -> LineOps.toggleComment(t, prefix) }
                }
            }
            EditorMenuAction.FIND -> {
                find.showFind = true
                findFocus = EditorSearchFocus.FIND
            }
            EditorMenuAction.REPLACE -> {
                find.showFind = true
                findFocus = EditorSearchFocus.REPLACE
            }
            EditorMenuAction.GOTO_LINE -> {
                gotoLineText = ((ed?.cursor?.leftLine ?: 0) + 1).toString()
                gotoLineDialog = true
            }
            EditorMenuAction.GOTO_LINE_START -> ed?.let {
                moveCaret(it, it.cursor.leftLine, toLineEnd = false)
                status = "已跳到行首"
            }
            EditorMenuAction.GOTO_LINE_END -> ed?.let {
                moveCaret(it, it.cursor.leftLine, toLineEnd = true)
                status = "已跳到行尾"
            }
            EditorMenuAction.GOTO_FILE_START -> ed?.let {
                moveCaret(it, 0, toLineEnd = false)
                status = "已跳到文件开头"
            }
            EditorMenuAction.GOTO_FILE_END -> ed?.let {
                moveCaret(it, it.lineCount - 1, toLineEnd = true)
                status = "已跳到文件结尾"
            }
            EditorMenuAction.LANGUAGE -> languageDialog = true
            EditorMenuAction.THEME -> themeDialog = true
            EditorMenuAction.WORDWRAP -> {
                wordwrap = !wordwrap
                scope.launch { container.prefs.setEditorWordwrap(wordwrap) }
                status = "自动换行：${if (wordwrap) "开" else "关"}"
            }
            EditorMenuAction.FLUENT_MODE -> {
                val next = !fluent
                scope.launch { container.prefs.setEditorFluentMode(next) }
                status = if (next) "已开启流畅模式（关闭语法高亮，大文本更流畅）" else "已关闭流畅模式"
            }
            EditorMenuAction.FLUENT_AUTO -> {
                scope.launch { container.prefs.setEditorFluentMode(null) }
                status = "流畅模式：按文件大小自动（> ${Fmt.size(AUTO_FLUENT_BYTES)} 自动开启）"
            }
            EditorMenuAction.CHARSET_RELOAD -> charsetReloadDialog = true
            EditorMenuAction.CHARSET_SAVE -> charsetSaveDialog = true
            EditorMenuAction.FONT_SIZE -> fontSizeDialog = true
            EditorMenuAction.WHITESPACE -> scope.launch {
                container.prefs.setEditorShowWhitespace(!showWhitespace)
                status = "显示空白符号：${if (!showWhitespace) "开" else "关"}"
            }
            EditorMenuAction.MONOSPACE -> scope.launch {
                container.prefs.setEditorMonospace(!monospace)
                status = "等宽字体：${if (!monospace) "开" else "关"}"
            }
            EditorMenuAction.SYMBOL_BAR -> scope.launch {
                container.prefs.setEditorSymbolBar(!symbolBar)
                status = "符号输入栏：${if (!symbolBar) "开" else "关"}"
            }
            EditorMenuAction.INDENT_GUIDE -> scope.launch {
                container.prefs.setEditorIndentGuide(!indentGuide)
                status = "缩进参考线：${if (!indentGuide) "开" else "关"}"
            }
            EditorMenuAction.MAGNIFIER -> scope.launch {
                container.prefs.setEditorMagnifier(!magnifier)
                status = "放大镜：${if (!magnifier) "开" else "关"}"
            }
            EditorMenuAction.FORMAT -> if (ed != null && formatName != null) {
                val formatted = CodeFormatter.format(ed.text.toString(), formatName)
                if (formatted == null) {
                    status = "格式化失败：内容不是有效的 ${formatName.uppercase()}"
                } else {
                    applyWholeTextOp(ed, "已格式化代码") { _, _ -> formatted }
                }
            }
            EditorMenuAction.COMPRESS -> ed?.let {
                applyWholeTextOp(it, "压缩代码（去行尾空白 / 首尾空行）") { t, _ -> LineOps.trimTrailingWhitespace(t) }
            }
            EditorMenuAction.VALIDATE -> {
                val kind = validateKind
                val text = ed?.text?.toString()
                if (kind == null) {
                    status = "当前语言不支持校验（仅 JSON / XML）"
                } else if (text != null) {
                    val problem = EditorValidate.validate(text, kind)
                    status = problem?.let { "校验失败（${EditorValidate.labelOf(kind)}）：$it" }
                        ?: "校验通过：${EditorValidate.labelOf(kind)} 语法正确"
                }
            }
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

    // ---- 打开文件：一律整篇进编辑器（> 16 MB 先确认、> 64 MB 拒绝；> 2 MB 自动开流畅模式）
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
            // 「自动换行」：用户显式设过就以用户为准，否则按语言默认
            wordwrap = settings.editorWordwrap ?: defaultWordwrap(override ?: auto)
            val plan = editorOpenPlan(info.size)
            when (plan.decision) {
                EditorOpenDecision.REFUSE -> {
                    error = "文件过大（${Fmt.size(info.size)}），编辑器最多打开 ${Fmt.size(OPEN_LIMIT_BYTES)} 的文本文件"
                    loading = false
                }
                EditorOpenDecision.CONFIRM -> {
                    // 保持 loading：用户确认后再读，取消就退出本页
                    pendingOpenSize = info.size
                    confirmLargeOpen = true
                }
                EditorOpenDecision.OPEN -> {
                    autoFluent = plan.autoFluent
                    if (plan.autoFluent) status = autoFluentNotice()
                    openWhole(vfs, null)
                    loading = false
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
            loading = false
        }
    }

    val findHistory = settings.inputHistory[PrefsStore.RecordKeys.EDITOR_FIND].orEmpty()
    val replaceHistory = settings.inputHistory[PrefsStore.RecordKeys.EDITOR_REPLACE].orEmpty()

    val menuInput = EditorMenuInput(
        canFormat = canFormat,
        canValidate = validateKind != null,
        canToggleComment = commentPrefix != null,
        wordwrap = wordwrap,
        fluentMode = fluent,
        fluentManual = settings.editorFluentMode != null,
        showWhitespace = showWhitespace,
        monospace = monospace,
        symbolBar = symbolBar,
        indentGuide = indentGuide,
        magnifier = magnifier,
        fontSize = fontSize,
        languageLabel = EditorLanguages.labelOf(scopeName) ?: "纯文本",
        languageManual = manualLanguage,
        themeLabel = EditorLanguages.themeLabel(editorTheme),
    )

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        EditorTopBar(
            title = (meta?.name ?: uri.name) + if (dirty) " *" else "",
            onBack = { attemptLeave() },
            canUndo = canUndo,
            canRedo = canRedo,
            canSave = dirty,
            menuInput = menuInput,
            onUndo = { editorHolder[0]?.let { it.undo(); syncUndoState(it) } },
            onRedo = { editorHolder[0]?.let { it.redo(); syncUndoState(it) } },
            onSave = { requestSave() },
            onToggleFind = {
                if (find.showFind) {
                    find.close(editorHolder[0])
                } else {
                    findFocus = EditorSearchFocus.FIND
                    find.showFind = true
                }
            },
            onMenuAction = { onMenuAction(it) },
        )

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
                    AndroidView(
                        factory = { ctx ->
                            CodeEditor(ctx).apply {
                                setTypefaceText(Typeface.MONOSPACE)
                                setLineNumberEnabled(true)
                                setWordwrap(false)
                                setTabWidth(4)
                                setTextSize(fontSize.toFloat())
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                // 编辑即「已修改」；整体换文本（装载/重载）不算（撤销栈由 pushText 重置）
                                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                                    if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                                        dirty = true
                                        charCount = text.length
                                        lineTotal = this.lineCount
                                        syncUndoState(this)
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
                                if (appliedLanguage[0] == NoLanguage || appliedLanguage[0] != effectiveScope) {
                                    appliedLanguage[0] = effectiveScope
                                    EditorLanguages.applyLanguage(ed, effectiveScope)
                                }
                                val schemeKey = "$dark|$editorTheme"
                                if (appliedSchemeKey[0] != schemeKey) {
                                    appliedSchemeKey[0] = schemeKey
                                    ed.colorScheme = EditorLanguages.createColorScheme(
                                        dark = dark,
                                        background = editorBackground,
                                        gutterText = editorGutterText,
                                        currentLine = editorCurrentLine,
                                        theme = editorTheme,
                                    )
                                }
                            }
                            if (appliedFontSize[0] != fontSize) {
                                appliedFontSize[0] = fontSize
                                ed.setTextSize(fontSize.toFloat())
                            }
                            if (appliedWordwrap[0] != wordwrap) {
                                appliedWordwrap[0] = wordwrap
                                ed.setWordwrap(wordwrap)
                            }
                            // 显示空白符号（NP `blank_symbol`）：空格 / Tab / 空行都画出来
                            if (appliedWhitespace[0] != showWhitespace) {
                                appliedWhitespace[0] = showWhitespace
                                ed.setNonPrintablePaintingFlags(if (showWhitespace) WHITESPACE_FLAGS else 0)
                            }
                            if (appliedMonospace[0] != monospace) {
                                appliedMonospace[0] = monospace
                                ed.setTypefaceText(if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT)
                            }
                            // 缩进参考线（NP `reference_line`）：当前代码块的竖线 + 块高亮
                            if (appliedIndentGuide[0] != indentGuide) {
                                appliedIndentGuide[0] = indentGuide
                                ed.setHighlightCurrentBlock(indentGuide)
                                ed.props.drawSideBlockLine = indentGuide
                            }
                            if (appliedMagnifier[0] != magnifier) {
                                appliedMagnifier[0] = magnifier
                                runCatching { ed.getComponent(Magnifier::class.java).isEnabled = magnifier }
                            }
                            // 流畅模式：当前行 / 括号高亮一并关掉
                            if (appliedFluent[0] != fluent) {
                                appliedFluent[0] = fluent
                                ed.setHighlightCurrentLine(!fluent)
                                ed.setHighlightBracketPair(!fluent)
                            }
                            applyText?.let { (version, text) ->
                                if (appliedVersion[0] != version) {
                                    appliedVersion[0] = version
                                    ed.setText(text)
                                    ed.setSelection(0, 0, true)
                                    // 撤销 / 重做按钮态由 ContentChangeEvent 回调同步（这里不写 Compose 状态）
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        buildString {
                            append("$lineTotal 行 · $charCount 字符 · $charset")
                            append(" · " + (EditorLanguages.labelOf(scopeName) ?: "纯文本"))
                            if (fluent) append(" · 流畅模式")
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
                focusTarget = findFocus,
            )
        }

        // ---- 符号输入栏（NP `symbol_input`；底部一条，可横滑）
        if (symbolBar) {
            EditorSymbolBar(
                enabled = true,
                textColor = MaterialTheme.colorScheme.onSurface,
                background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                onSymbol = { insertSymbol(it) },
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

    if (themeDialog) {
        EditorOptionDialog(
            title = "配色风格",
            subtitle = "「跟随应用」的底色与行号栏对齐应用配色；固定主题使用主题自带配色。",
            options = EditorLanguages.THEME_OPTIONS,
            selected = editorTheme,
            onPick = { value ->
                themeDialog = false
                scope.launch { container.prefs.setEditorTheme(value) }
                status = "配色风格：${EditorLanguages.themeLabel(value)}"
            },
            onDismiss = { themeDialog = false },
        )
    }

    if (fontSizeDialog) {
        EditorOptionDialog(
            title = "字体大小",
            subtitle = "当前 $fontSize",
            options = FONT_SIZES.map { it.toString() to if (it == DEFAULT_FONT_SIZE) "$it（默认）" else "$it" },
            selected = fontSize.toString(),
            onPick = { value ->
                fontSizeDialog = false
                value.toIntOrNull()?.let { size -> scope.launch { container.prefs.setEditorFontSize(size) } }
            },
            onDismiss = { fontSizeDialog = false },
        )
    }

    if (charsetReloadDialog) {
        EditorOptionDialog(
            title = "指定编码重新加载",
            subtitle = "当前识别为 $charset；选错编码会显示为乱码，可再换一个。",
            options = EditorCharsets.reloadOptions(),
            selected = charset,
            onPick = { value ->
                charsetReloadDialog = false
                requestReload(value)
            },
            onDismiss = { charsetReloadDialog = false },
        )
    }

    if (charsetSaveDialog) {
        EditorOptionDialog(
            title = "指定保存的编码",
            subtitle = "写回时按所选编码编码；无法表示的字符会转存 UTF-8 并在状态栏注明。",
            options = EditorCharsets.saveOptions(),
            selected = charset,
            onPick = { value ->
                charsetSaveDialog = false
                requestSave(useCharset = value)
            },
            onDismiss = { charsetSaveDialog = false },
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
            onSaveAndLeave = { confirmDiscard = false; requestSave { onBack() } },
            onDiscard = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (confirmReload) {
        EditorConfirmDialog(
            title = "重新加载",
            message = "「${meta?.name ?: uri.name}」有未保存的修改，重新加载会丢弃这些修改。",
            confirmLabel = "丢弃并重新加载",
            onConfirm = {
                confirmReload = false
                reload(pendingReloadCharset)
            },
            onDismiss = { confirmReload = false },
        )
    }

    if (confirmLargeOpen) {
        EditorConfirmDialog(
            title = "文件较大",
            message = "「${meta?.name ?: uri.name}」${Fmt.size(pendingOpenSize)}，" +
                "打开可能需要几秒并占用较多内存；打开后会自动开启流畅模式（关语法高亮）。",
            confirmLabel = "打开",
            onConfirm = {
                confirmLargeOpen = false
                autoFluent = true
                scope.launch {
                    try {
                        val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
                        openWhole(vfs, null)
                        status = autoFluentNotice()
                    } catch (e: Exception) {
                        error = e.message ?: "读取失败"
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = {
                confirmLargeOpen = false
                onBack()
            },
        )
    }

    if (confirmOverwrite) {
        EditorConfirmDialog(
            title = "文件已被外部修改",
            message = "「${meta?.name ?: uri.name}」在编辑器打开后被其他程序修改过，继续保存会覆盖那些改动。",
            confirmLabel = "覆盖保存",
            onConfirm = {
                confirmOverwrite = false
                val ed = editorHolder[0]
                val done = pendingSaveDone
                pendingSaveDone = {}
                if (ed != null) performSave(ed, pendingSaveCharset ?: charset, done)
            },
            onDismiss = {
                confirmOverwrite = false
                pendingSaveDone = {}
                status = "已取消保存"
            },
        )
    }
}

/** 取编辑器当前行文本（剪贴板用）；无实例 / 越界返回 null。 */
private fun lineTextOf(ed: CodeEditor?): String? =
    ed?.let { runCatching { it.text.getLineString(it.cursor.leftLine) }.getOrNull() }

/** 「尚未设置过语言」的哨兵（用来区分「没设置」与「设置成纯文本」）。 */
private val NoLanguage = Any()

/**
 * 「显示空白符号」的绘制标志：行首 / 行内 / 行尾空白 + 空行内的空白。
 * 不打开 `FLAG_DRAW_LINE_SEPARATOR`（换行符）与 `FLAG_DRAW_TAB_SAME_AS_SPACE`：
 * Tab 保留 sora 默认的箭头式画法（与 MT / NP 观感一致）。
 */
private const val WHITESPACE_FLAGS = CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
    CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
    CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
    CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
