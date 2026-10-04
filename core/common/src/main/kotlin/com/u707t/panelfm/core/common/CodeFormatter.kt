package com.u707t.panelfm.core.common

/**
 * 「格式化代码」（复刻 MT 编辑器菜单 `0x7f0e001b` 的「格式化代码」`0x7f110411`）。
 *
 * 本项目只做**文件管理 + 预览**，不做完整的语言工具链，所以这里只覆盖
 * 两种「结构清晰、格式化无歧义」的格式：
 *  - **JSON**：按缩进重排（字符串字面量内的括号/逗号不参与）
 *  - **XML**：按标签深度重排（`<pre>` / CDATA / 注释 / 自闭合 / 属性原样保留）
 *
 * 其余语言返回 null（UI 侧提示「暂不支持该语言的格式化」，不静默失败）。
 */
object CodeFormatter {

    /** 该语言是否支持格式化 */
    fun supports(language: String): Boolean = language.lowercase() in setOf("json", "xml")

    /** 格式化入口；失败或语言不支持返回 null */
    fun format(text: String, language: String): String? = when (language.lowercase()) {
        "json" -> runCatching { formatJson(text) }.getOrNull()
        "xml" -> runCatching { formatXml(text) }.getOrNull()
        else -> null
    }

    // ------------------------------------------------------------------ JSON

    /**
     * JSON 缩进重排（2 空格）。
     * 只按字符扫描，不解析结构 —— 因此**不会**破坏字符串里的 `{}[],:`。
     */
    fun formatJson(text: String, indentUnit: String = "  "): String {
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        var i = 0
        val n = text.length

        fun newline() {
            sb.append('\n')
            repeat(depth) { sb.append(indentUnit) }
        }

        while (i < n) {
            val c = text[i]
            when {
                inString -> {
                    sb.append(c)
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                }
                c == '"' -> {
                    inString = true
                    sb.append(c)
                }
                c == '{' || c == '[' -> {
                    // 空的 {} / [] 不换行
                    val next = nextNonSpace(text, i + 1)
                    sb.append(c)
                    depth++
                    if (next < n && (text[next] == '}' || text[next] == ']')) {
                        // 空容器：跳到闭合符由后续分支处理
                    } else {
                        newline()
                    }
                }
                c == '}' || c == ']' -> {
                    val prev = prevNonSpace(text, i - 1)
                    depth = (depth - 1).coerceAtLeast(0)
                    if (prev >= 0 && (text[prev] == '{' || text[prev] == '[')) {
                        sb.append(c)
                    } else {
                        newline()
                        sb.append(c)
                    }
                }
                c == ',' -> {
                    sb.append(c)
                    newline()
                }
                c == ':' -> sb.append(": ")
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                    // 丢弃原有空白（由格式化重新生成）
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trimEnd()
    }

    private fun nextNonSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun prevNonSpace(text: String, from: Int): Int {
        var i = from
        while (i >= 0 && text[i].isWhitespace()) i--
        return i
    }

    // ------------------------------------------------------------------ XML

    /**
     * XML 缩进重排（2 空格）。
     * 原样保留：XML 声明、注释、CDATA、DOCTYPE、属性与文本节点内容。
     * 不做属性换行（MT 也不做）。
     *
     * 「开标签 + 文本 + 对应闭标签」会**合并到一行**（`<b>t</b>`），与 MT / 常见格式化器一致；
     * 只有元素之间才换行。
     */
    fun formatXml(text: String, indentUnit: String = "  "): String {
        val tokens = mergeInlineText(tokenizeXml(text))
        val sb = StringBuilder()
        var depth = 0
        var first = true
        tokens.forEach { tok ->
            val raw = tok.trim()
            if (raw.isEmpty()) return@forEach
            val isClose = raw.startsWith("</")
            val isSelfClose = raw.endsWith("/>")
            val isDecl = raw.startsWith("<?") || raw.startsWith("<!")
            // 已合并的 `<b>t</b>` 是自平衡的：不再改变缩进深度
            val balanced = !isClose && raw.contains("</")
            if (isClose) depth = (depth - 1).coerceAtLeast(0)
            if (!first) sb.append('\n')
            first = false
            repeat(depth) { sb.append(indentUnit) }
            sb.append(raw)
            if (!isClose && !isSelfClose && !isDecl && !balanced && raw.startsWith("<")) depth++
        }
        return sb.toString()
    }

    /** 把「开标签 + 纯文本 + 对应闭标签」合成一个 token（`<b>t</b>` 不拆行） */
    private fun mergeInlineText(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val cur = tokens[i]
            val isOpen = cur.startsWith("<") && !cur.startsWith("</") && !cur.startsWith("<?") &&
                !cur.startsWith("<!") && !cur.endsWith("/>")
            if (isOpen && i + 2 < tokens.size &&
                !tokens[i + 1].startsWith("<") &&
                tokens[i + 2].startsWith("</")
            ) {
                out.add(cur + tokens[i + 1] + tokens[i + 2])
                i += 3
            } else {
                out.add(cur)
                i++
            }
        }
        return out
    }

    /** 把 XML 切成「标签 / 文本节点」序列（保留 CDATA / 注释 / 声明整体） */
    private fun tokenizeXml(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotBlank()) out.add(buf.toString().trim())
            buf.clear()
        }

        while (i < n) {
            when {
                text.startsWith("<!--", i) -> {
                    flushText()
                    val end = text.indexOf("-->", i)
                    val stop = if (end < 0) n else end + 3
                    out.add(text.substring(i, stop))
                    i = stop
                }
                text.startsWith("<![CDATA[", i) -> {
                    flushText()
                    val end = text.indexOf("]]>", i)
                    val stop = if (end < 0) n else end + 3
                    out.add(text.substring(i, stop))
                    i = stop
                }
                text[i] == '<' -> {
                    flushText()
                    // 找到标签结束的 '>'（跳过引号内的 '>'）
                    var j = i + 1
                    var quote: Char? = null
                    while (j < n) {
                        val c = text[j]
                        when {
                            quote != null -> if (c == quote) quote = null
                            c == '"' || c == '\'' -> quote = c
                            c == '>' -> break
                        }
                        j++
                    }
                    val stop = (j + 1).coerceAtMost(n)
                    out.add(text.substring(i, stop).replace(Regex("\\s+"), " "))
                    i = stop
                }
                else -> {
                    buf.append(text[i])
                    i++
                }
            }
        }
        flushText()
        return out
    }
}
