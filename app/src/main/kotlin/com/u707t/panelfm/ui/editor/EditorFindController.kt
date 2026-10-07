package com.u707t.panelfm.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.u707t.panelfm.core.data.PrefsStore
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import java.util.regex.PatternSyntaxException

/** 查找 / 替换的查询快照（用于判断「条件是否变化」）。 */
internal data class SearchSpec(
    val pattern: String,
    val useRegex: Boolean,
    val wholeWord: Boolean,
    val matchCase: Boolean,
)

/**
 * 查找 / 替换控制器：查找条状态 + 与 sora `EditorSearcher` 的全部交互。
 *
 * **库契约备忘（改动前先读；三条均已用 0.24.6 字节码核验）**：
 * - 搜索在库内后台线程跑，结果通过 `PublishSearchResultEvent`（主线程）通知；
 *   壳里必须把 [onResultsPublished] 放进 `postInLifecycle` 延后一帧再调——
 *   事件派发的当口直接读 `matchedPositionCount` 会拿到 0；
 * - `search()` 对正则类型**同步预编译**——语法错误会就地抛 `PatternSyntaxException`，这里能接住；
 * - `replaceAll` 的库回调在主线程、且正文替换触发的自动重搜排在它前面，
 *   用 [suppressSearchStatus] 抑制那次自动重搜的状态刷新，保证「已替换 N 处」不被覆盖。
 */
internal class EditorFindController(
    /** 状态栏输出（编辑器顶部的 status 行）。 */
    private val onStatus: (String) -> Unit,
    /** 记录输入历史（去重 / 截断由 PrefsStore 负责）。 */
    private val onHistory: (String, String) -> Unit,
    /** 只读（大文件分段）时不允许替换。 */
    private val isReadOnly: () -> Boolean,
) {

    // ---- 查找条 UI 状态（被组合读取）
    var showFind by mutableStateOf(false)
    var findText by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var useRegex by mutableStateOf(false)
    var matchCase by mutableStateOf(true)
    var wholeWord by mutableStateOf(false)
    var optionsMenuExpanded by mutableStateOf(false)

    // ---- 在飞状态（只被本类读写，不需要参与组合）
    /** 上一次真正提交的查询；条件没变时「下个」只 gotoNext，不重启搜索。 */
    private var lastQuery: SearchSpec? = null
    /** 新查询刚提交时暂存的「结果就绪后要执行的动作」（下个 / 替换）。 */
    private var pendingAfterSearch: (() -> Unit)? = null
    /** 结果是否已就绪；未就绪时不能把 `matchedPositionCount == 0` 当「找不到」。 */
    private var searchResultsReady = false
    /** 替换全部后抑制一次自动重搜的状态刷新。 */
    private var suppressSearchStatus = false

    /** 查找条选项变化后调用：让下一次操作重新提交查询。 */
    fun invalidateQuery() {
        lastQuery = null
    }

    /** 关闭查找条：停搜并清空全部在飞状态。 */
    fun close(ed: CodeEditor?) {
        showFind = false
        optionsMenuExpanded = false
        lastQuery = null
        pendingAfterSearch = null
        searchResultsReady = false
        ed?.searcher?.stopSearch()
    }

    /** 搜索结果就绪（壳里在事件回调的 `postInLifecycle` 中调用）。 */
    fun onResultsPublished(ed: CodeEditor) {
        searchResultsReady = true
        val pending = pendingAfterSearch
        if (pending != null) {
            pendingAfterSearch = null
            pending()
        } else if (suppressSearchStatus) {
            suppressSearchStatus = false
        } else {
            refreshStatus(ed)
        }
    }

    /** 「上个 / 下个」。 */
    fun jump(ed: CodeEditor, backward: Boolean) {
        if (findText.isEmpty()) {
            onStatus("请输入查找内容")
            return
        }
        val spec = spec()
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val jumpNow: () -> Unit = {
            runCatching { if (backward) ed.searcher.gotoPrevious() else ed.searcher.gotoNext() }
            refreshStatus(ed)
        }
        if (fresh) pendingAfterSearch = jumpNow else jumpNow()
        onHistory(PrefsStore.RecordKeys.EDITOR_FIND, spec.pattern)
    }

    /** 「替换」。 */
    fun replaceCurrent(ed: CodeEditor) {
        if (isReadOnly()) return
        if (findText.isEmpty()) {
            onStatus("请输入查找内容")
            return
        }
        val spec = spec()
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val replaceNow: () -> Unit = {
            // 选区正好是命中 → 替换；否则 sora 会先跳到下一个（与 MT 的操作习惯一致）
            runCatching { ed.searcher.replaceCurrentMatch(replaceText) }
            refreshStatus(ed)
        }
        if (fresh) pendingAfterSearch = replaceNow else replaceNow()
        onHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replaceText)
    }

    /** 「全部替换」。 */
    fun replaceAll(ed: CodeEditor) {
        if (isReadOnly()) return
        if (findText.isEmpty()) {
            onStatus("请输入查找内容")
            return
        }
        val spec = spec()
        val fresh = spec != lastQuery
        if (fresh && !startSearch(ed, spec)) return
        val replaceNow: () -> Unit = {
            val total = runCatching { ed.searcher.matchedPositionCount }.getOrDefault(0)
            val started = runCatching {
                ed.searcher.replaceAll(replaceText) {
                    // 库回调（主线程）：正文已替换、自动重搜已排入队列 —— 抑制其状态刷新
                    suppressSearchStatus = true
                    onStatus("已替换 $total 处")
                }
            }.isSuccess
            if (!started) refreshStatus(ed)
        }
        if (fresh) pendingAfterSearch = replaceNow else replaceNow()
        onHistory(PrefsStore.RecordKeys.EDITOR_REPLACE, replaceText)
    }

    // ------------------------------------------------------------------ 内部

    private fun spec(): SearchSpec = SearchSpec(findText, useRegex, wholeWord, matchCase)

    private fun notFoundMessage(): String = when {
        useRegex && matchCase && wholeWord -> "找不到文本（已开启正则表达式、全词匹配和区分大小写）"
        useRegex && matchCase -> "找不到文本（已开启正则表达式和区分大小写）"
        useRegex && wholeWord -> "找不到文本（已开启正则表达式和全词匹配）"
        useRegex -> "找不到文本（已开启正则表达式）"
        wholeWord && matchCase -> "找不到文本（已开启全词匹配和区分大小写）"
        wholeWord -> "找不到文本（已开启全词匹配）"
        matchCase -> "找不到文本（已开启区分大小写）"
        else -> "找不到文本"
    }

    private fun startSearch(ed: CodeEditor, spec: SearchSpec): Boolean {
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
            onStatus("正则表达式有误")
            false
        } catch (e: Exception) {
            onStatus("查找失败：${e.message}")
            false
        }
    }

    private fun refreshStatus(ed: CodeEditor) {
        val searcher = ed.searcher
        if (!searcher.hasQuery()) return
        if (!searchResultsReady) {
            // 结果还没算完：matchedPositionCount 会返回 0，不能当成「找不到」
            onStatus("查找中…")
            return
        }
        val count = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        val index = runCatching { searcher.currentMatchedPositionIndex }.getOrDefault(-1)
        onStatus(
            when {
                count == 0 -> notFoundMessage()
                index >= 0 -> "第 ${index + 1} / $count 处"
                else -> "共 $count 处"
            },
        )
    }
}
