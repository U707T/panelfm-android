package com.u707t.panelfm.core.common

/**
 * 文本「行操作」（复刻 MT 编辑器菜单 0x7f0e001b 的一批命令）。
 *
 * 约定：所有函数都在「光标所在行」的语义上工作（MT 的菜单是行级操作），
 * 只处理传入的整段文本并返回新文本，不改动光标（由 UI 侧决定）。
 */
object LineOps {

    /** 把文本按行切分（保留行内容，不保留换行符） */
    private fun lines(text: String): List<String> = text.split('\n')

    /** 定位 [offset] 所在行的行号（0 基） */
    fun lineIndexOf(text: String, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { it == '\n' }

    /** 第 [lineIndex] 行在文本中的起始偏移 */
    fun lineStartOffset(text: String, lineIndex: Int): Int {
        var line = 0
        text.forEachIndexed { i, c ->
            if (line == lineIndex) return i
            if (c == '\n') line++
        }
        return text.length
    }

    /** 复制行：在第 [lineIndex] 行下方插入一份相同内容 */
    fun duplicateLine(text: String, lineIndex: Int): String {
        val ls = lines(text)
        if (lineIndex !in ls.indices) return text
        val out = ls.toMutableList()
        out.add(lineIndex + 1, ls[lineIndex])
        return out.joinToString("\n")
    }

    /** 删除行 */
    fun deleteLine(text: String, lineIndex: Int): String {
        val ls = lines(text)
        if (lineIndex !in ls.indices) return text
        return ls.filterIndexed { i, _ -> i != lineIndex }.joinToString("\n")
    }

    /** 清空行（保留行本身，内容置空） */
    fun clearLine(text: String, lineIndex: Int): String {
        val ls = lines(text)
        if (lineIndex !in ls.indices) return text
        val out = ls.toMutableList()
        out[lineIndex] = ""
        return out.joinToString("\n")
    }

    /** 剪切行（返回「新文本 to 被剪掉的内容」） */
    fun cutLine(text: String, lineIndex: Int): Pair<String, String> {
        val ls = lines(text)
        if (lineIndex !in ls.indices) return text to ""
        val cut = ls[lineIndex]
        return deleteLine(text, lineIndex) to cut
    }

    /** 转为大写 / 小写（整段文本） */
    fun toUpperCase(text: String): String = text.uppercase()
    fun toLowerCase(text: String): String = text.lowercase()

    /**
     * 增加 / 减小缩进（整段文本）。
     * MT 的缩进单位跟随语法设置（Tab 或 N 空格），这里统一用 [indent] 作为单位。
     */
    fun indent(text: String, indent: String = "    "): String =
        // 只含空白的行保持原样，避免产生「只有缩进的空行」
        lines(text).joinToString("\n") { if (it.isBlank()) it else indent + it }

    fun unindent(text: String, indent: String = "    "): String =
        lines(text).joinToString("\n") { line ->
            when {
                line.startsWith(indent) -> line.removePrefix(indent)
                line.startsWith("\t") -> line.removePrefix("\t")
                // 不足一个单位时，剥掉行首连续的空白（更接近用户预期）
                else -> line.dropWhile { it == ' ' }
            }
        }

    /**
     * 切换行注释（MT 的「切换注释」）。
     * [prefix] 由语言决定：`//`、`#`、`--`、`<!--` 等。
     * 规则：所有非空行都已注释 → 取消注释；否则统一加注释。
     */
    fun toggleComment(text: String, prefix: String): String {
        val ls = lines(text)
        val targets = ls.withIndex().filter { it.value.isNotBlank() }
        if (targets.isEmpty()) return text
        val allCommented = targets.all { it.value.trimStart().startsWith(prefix) }
        val out = ls.toMutableList()
        targets.forEach { (i, line) ->
            out[i] = if (allCommented) {
                val idx = line.indexOf(prefix)
                // 连同前缀后的一个空格一起去掉，避免反复切换累积空格
                val after = line.substring(idx + prefix.length).removePrefix(" ")
                line.substring(0, idx) + after
            } else {
                val leading = line.takeWhile { it == ' ' || it == '\t' }
                leading + prefix + " " + line.substring(leading.length)
            }
        }
        return out.joinToString("\n")
    }

    /**
     * 去除首尾空白行 + 行尾空白（MT 的「压缩代码」的保守实现）。
     * 真正的「压缩代码」按语言做（去掉换行/注释），这里只做安全的空白规整，
     * 避免破坏字符串字面量里的内容。
     */
    fun trimTrailingWhitespace(text: String): String =
        lines(text)
            .joinToString("\n") { it.trimEnd() }
            // 去掉首尾的空行（含只剩空白的行），保留中间空行
            .let { t ->
                val ls = lines(t)
                val from = ls.indexOfFirst { it.isNotBlank() }
                if (from < 0) return@let ""
                val to = ls.indexOfLast { it.isNotBlank() }
                ls.subList(from, to + 1).joinToString("\n")
            }

    /** 把 [offset] 附近的行区间算出来（供 UI 高亮/滚动定位） */
    fun lineRangeOf(text: String, offset: Int): IntRange {
        val idx = lineIndexOf(text, offset)
        val ls = lines(text)
        return idx.coerceIn(0, (ls.size - 1).coerceAtLeast(0))..idx.coerceIn(0, (ls.size - 1).coerceAtLeast(0))
    }
}
