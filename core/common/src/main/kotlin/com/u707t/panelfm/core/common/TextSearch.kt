package com.u707t.panelfm.core.common

/** 查找/替换选项。默认值与 MT 编辑器一致：区分大小写、普通文本匹配。 */
data class TextSearchOptions(
    val regex: Boolean = false,
    val matchCase: Boolean = true,
    val wholeWord: Boolean = false,
)

data class TextSearchQuery(
    val needle: String,
    val options: TextSearchOptions = TextSearchOptions(),
)

data class TextSearchMatch(val start: Int, val end: Int)

data class TextSearchMatches(
    val matches: List<TextSearchMatch>,
    val error: String? = null,
)

data class TextSearchOneResult(
    val match: TextSearchMatch? = null,
    val error: String? = null,
)

data class TextReplaceResult(
    val text: String,
    val count: Int,
    val error: String? = null,
    /** 单次替换后新文本中替换内容的长度；全部替换时不使用。 */
    val replacementLength: Int = 0,
)

/**
 * 编辑器查找/替换核心。
 *
 * UI 层只在后台线程调用这些函数：
 *  - 查找下一个只消费到目标命中，不再为了显示计数把全文扫描两遍；
 *  - 全部替换一遍扫描、一个 StringBuilder 完成，不在主线程执行；
 *  - 普通文本不走 Regex，避免每次按键/点击都重新编译正则；
 *  - 正则错误以结果返回，不把用户输入异常抛到 UI；
 *  - 全词、大小写选项在查找与替换中使用同一套语义，避免「统计命中」和「实际替换」不一致。
 */
object TextSearch {

    const val INVALID_REGEX_MESSAGE = "正则表达式有误"

    fun findAll(text: String, query: TextSearchQuery): TextSearchMatches {
        if (query.needle.isEmpty()) return TextSearchMatches(emptyList())
        val compiled = compile(query) ?: return TextSearchMatches(emptyList(), INVALID_REGEX_MESSAGE)
        return TextSearchMatches(compiled.matches(text).map { it.public }.toList())
    }

    /**
     * 从 [from] 开始查找并循环。
     * 正向查找包含起点；编辑器在选中当前命中后传入 selection.end，因此会自然跳到下一个。
     * 反向查找取起点之前的最后一个命中，没有则从文末循环。
     */
    fun findNext(
        text: String,
        query: TextSearchQuery,
        from: Int,
        backward: Boolean = false,
    ): TextSearchOneResult {
        if (query.needle.isEmpty()) return TextSearchOneResult()
        val compiled = compile(query) ?: return TextSearchOneResult(error = INVALID_REGEX_MESSAGE)
        val start = from.coerceIn(0, text.length)
        if (!backward) {
            var first: InternalMatch? = null
            for (match in compiled.matches(text)) {
                if (first == null) first = match
                if (match.start >= start) return TextSearchOneResult(match.public)
            }
            return TextSearchOneResult(first?.public)
        }

        var before: InternalMatch? = null
        var last: InternalMatch? = null
        for (match in compiled.matches(text)) {
            last = match
            if (match.start < start) before = match
        }
        return TextSearchOneResult((before ?: last)?.public)
    }

    /** 判断选区是否正好是一个命中；供「替换当前」使用。 */
    fun replaceOne(
        text: String,
        query: TextSearchQuery,
        start: Int,
        end: Int,
        replacement: String,
    ): TextReplaceResult {
        if (query.needle.isEmpty()) return TextReplaceResult(text, 0)
        val compiled = compile(query) ?: return TextReplaceResult(text, 0, INVALID_REGEX_MESSAGE)
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val match = compiled.matches(text).firstOrNull {
            it.start == safeStart && it.end == safeEnd
        } ?: return TextReplaceResult(text, 0)
        val replacementText = compiled.renderReplacement(match, replacement)
        val out = StringBuilder(text.length - (safeEnd - safeStart) + replacementText.length)
            .append(text, 0, safeStart)
            .append(replacementText)
            .append(text, safeEnd, text.length)
        return TextReplaceResult(out.toString(), 1, replacementLength = replacementText.length)
    }

    /** 一遍扫描完成全部替换；普通文本替换内容按字面量处理，正则支持 $1 / $0 分组引用。 */
    fun replaceAll(
        text: String,
        query: TextSearchQuery,
        replacement: String,
    ): TextReplaceResult {
        if (query.needle.isEmpty()) return TextReplaceResult(text, 0)
        val compiled = compile(query) ?: return TextReplaceResult(text, 0, INVALID_REGEX_MESSAGE)
        var output: StringBuilder? = null
        var cursor = 0
        var count = 0
        for (match in compiled.matches(text)) {
            val builder = output ?: StringBuilder(text.length).also { output = it }
            builder.append(text, cursor, match.start)
            builder.append(compiled.renderReplacement(match, replacement))
            cursor = match.end
            count++
        }
        val builder = output ?: return TextReplaceResult(text, 0)
        builder.append(text, cursor, text.length)
        return TextReplaceResult(builder.toString(), count)
    }

    private class CompiledQuery(
        private val query: TextSearchQuery,
        private val regex: Regex?,
    ) {
        /** 只在内部保留 MatchResult，正则替换需要读取分组；UI 只看到不可变的区间。 */
        fun matches(text: String): Sequence<InternalMatch> = sequence {
            if (regex == null) {
                var from = 0
                while (from <= text.length - query.needle.length) {
                    val start = if (query.options.matchCase) {
                        text.indexOf(query.needle, from)
                    } else {
                        indexOfIgnoreCase(text, query.needle, from)
                    }
                    if (start < 0) break
                    val end = start + query.needle.length
                    if (!query.options.wholeWord || isWholeWord(text, start, end)) {
                        yield(InternalMatch(start, end, null))
                    }
                    // 与 Regex.findAll 一样不返回重叠命中；needle 已保证非空。
                    from = end
                }
            } else {
                for (match in regex.findAll(text)) {
                    if (!query.options.wholeWord || isWholeWord(text, match.range.first, match.range.last + 1)) {
                        yield(InternalMatch(match.range.first, match.range.last + 1, match))
                    }
                }
            }
        }

        fun renderReplacement(match: InternalMatch, replacement: String): String {
            val regexMatch = match.regexMatch ?: return replacement
            return expandReplacement(regexMatch, replacement)
        }
    }

    private data class InternalMatch(
        val start: Int,
        val end: Int,
        val regexMatch: MatchResult?,
    ) {
        val public: TextSearchMatch get() = TextSearchMatch(start, end)
    }

    private fun compile(query: TextSearchQuery): CompiledQuery? {
        if (!query.options.regex) return CompiledQuery(query, null)
        return runCatching {
            val options = if (query.options.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            CompiledQuery(query, Regex(query.needle, options))
        }.getOrNull()
    }

    private fun indexOfIgnoreCase(text: String, needle: String, from: Int): Int {
        val lastStart = text.length - needle.length
        var start = from.coerceAtLeast(0)
        while (start <= lastStart) {
            if (text.regionMatches(start, needle, 0, needle.length, ignoreCase = true)) return start
            start++
        }
        return -1
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        val before = start == 0 || !isWordChar(text[start - 1])
        val after = end >= text.length || !isWordChar(text[end])
        return before && after
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    /**
     * 解析正则替换中的常用分组引用。普通文本不经过这里，因此 `$` 在普通替换中永远是字面量。
     * 支持 `$0`（完整命中）、`$1`… 和 `$&`；未知分组保留原样而不是让整个替换崩溃。
     */
    private fun expandReplacement(match: MatchResult, replacement: String): String {
        val groups = match.groupValues
        val out = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            when (val c = replacement[i]) {
                '\\' -> {
                    if (i + 1 < replacement.length) {
                        out.append(replacement[i + 1])
                        i += 2
                    } else {
                        out.append(c)
                        i++
                    }
                }
                '$' -> {
                    if (i + 1 >= replacement.length) {
                        out.append('$')
                        i++
                        continue
                    }
                    val next = replacement[i + 1]
                    if (next == '&') {
                        out.append(match.value)
                        i += 2
                        continue
                    }
                    if (!next.isDigit()) {
                        out.append('$')
                        i++
                        continue
                    }
                    var end = i + 2
                    while (end < replacement.length && replacement[end].isDigit()) end++
                    // 取「最长的存在分组编号」，例如只有第 1 组时 `$12` = 第 1 组 + 字面量 2。
                    var groupEnd = end
                    var groupIndex = replacement.substring(i + 1, groupEnd).toIntOrNull()
                    while (groupEnd > i + 2 && (groupIndex == null || groupIndex >= groups.size)) {
                        groupEnd--
                        groupIndex = replacement.substring(i + 1, groupEnd).toIntOrNull()
                    }
                    if (groupIndex != null && groupIndex in groups.indices) {
                        out.append(groups[groupIndex])
                        i = groupEnd
                    } else {
                        out.append('$')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
