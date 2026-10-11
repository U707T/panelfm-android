package com.u707t.panelfm.ui.editor

import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/*
 * 「校验语法」（NP `edit_menu.xml` 的 `menu_xml_valid_check`）。
 *
 * panelfm 不做完整语言工具链（见 `CodeFormatter` 的口径）：只校验两种**有确定语法**的格式，
 * 且复用平台自带的解析器（Android 上是 org.json / Expat 系 XML 解析），比手写更可信：
 *  - JSON：org.json（顶层对象 / 数组都收；报错带偏移）；
 *  - XML：`DocumentBuilderFactory`（只做「格式良好」校验，不做 DTD / 外部实体解析）。
 *
 * 其余语言返回 null → 菜单里的「校验语法」置灰（不静默失败）。
 */
internal object EditorValidate {

    /** 当前语法能否校验；不能则返回 null。 */
    fun kindOf(scope: String?): String? = when (scope) {
        "source.json" -> "json"
        "text.xml" -> "xml"
        else -> null
    }

    /** 菜单里显示的名字（如「校验 JSON 语法」）。 */
    fun labelOf(kind: String): String = when (kind) {
        "json" -> "JSON"
        "xml" -> "XML"
        else -> kind.uppercase()
    }

    /** 校验通过返回 null；失败返回可读原因（含行 / 列或偏移）。 */
    fun validate(text: String, kind: String): String? = when (kind) {
        "json" -> validateJson(text)
        "xml" -> validateXml(text)
        else -> "暂不支持该语言的校验"
    }

    // ------------------------------------------------------------------ JSON

    /**
     * JSON 校验：**严格模式**（自己扫，不用 org.json）。
     *
     * 为什么不用 org.json：它宽容到会把 `{a:1}`（键没引号）、`[1,]`（尾逗号）都接受——
     * 作为「校验」会给出与 JSON 规范相反的回答。这里逐字符扫，并按规范报
     * 「第 N 行，第 M 列」，用户能直接跳到问题处。
     */
    private fun validateJson(text: String): String? {
        val scanner = JsonScanner(text)
        return try {
            scanner.parseValue()
            scanner.skipWhitespace()
            if (!scanner.atEnd()) throw scanner.error("多余的字符")
            null
        } catch (e: JsonError) {
            "${e.message}（第 ${e.line} 行，第 ${e.column} 列）"
        }
    }

    private class JsonError(message: String, val index: Int, val line: Int, val column: Int) :
        Exception(message)

    /** JSON 递归深度上限（`[[[[…` 这种病态输入不能把「校验」变成 StackOverflow 崩溃）。 */
    private const val MAX_JSON_DEPTH = 256

    /** 严格 JSON 递归下降扫描器（只做校验，不建对象树）。 */
    private class JsonScanner(private val text: String) {
        private var pos = 0

        /** 递归深度上限：病态输入（几万个 `[`）不能把「校验」变成 StackOverflow 崩溃。 */
        private var depth = 0

        fun atEnd(): Boolean = pos >= text.length

        private fun peek(): Char = text[pos]

        fun error(message: String, at: Int = pos): JsonError {
            var line = 1
            var col = 1
            for (i in 0 until at.coerceAtMost(text.length)) {
                if (text[i] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return JsonError(message, at, line, col)
        }

        fun skipWhitespace() {
            while (!atEnd() && text[pos] in " \t\r\n") pos++
        }

        fun parseValue() {
            if (++depth > MAX_JSON_DEPTH) throw error("嵌套层级过深（超过 $MAX_JSON_DEPTH 层）")
            try {
                skipWhitespace()
                if (atEnd()) throw error("内容不完整：缺少值")
                when (val c = peek()) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> expect("true")
                    'f' -> expect("false")
                    'n' -> expect("null")
                    else -> if (c == '-' || c in '0'..'9') parseNumber() else throw error("意外的字符 '$c'")
                }
            } finally {
                depth--
            }
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) throw error("无效的字面量（应为 $literal）")
            pos += literal.length
        }

        private fun parseObject() {
            pos++ // {
            skipWhitespace()
            if (!atEnd() && peek() == '}') {
                pos++
                return
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || peek() != '"') throw error("对象的键必须是双引号字符串")
                parseString()
                skipWhitespace()
                if (atEnd() || peek() != ':') throw error("键后面缺少 ':'")
                pos++
                parseValue()
                skipWhitespace()
                if (atEnd()) throw error("对象没有闭合（缺少 '}'）")
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (!atEnd() && peek() == '}') throw error("尾逗号后多了一个 '}'")
                    }
                    '}' -> {
                        pos++
                        return
                    }
                    else -> throw error("对象里缺少 ',' 或 '}'")
                }
            }
        }

        private fun parseArray() {
            pos++ // [
            skipWhitespace()
            if (!atEnd() && peek() == ']') {
                pos++
                return
            }
            while (true) {
                parseValue()
                skipWhitespace()
                if (atEnd()) throw error("数组没有闭合（缺少 ']'）")
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (!atEnd() && peek() == ']') throw error("尾逗号后多了一个 ']'")
                    }
                    ']' -> {
                        pos++
                        return
                    }
                    else -> throw error("数组里缺少 ',' 或 ']'")
                }
            }
        }

        private fun parseString() {
            pos++ // 开引号
            while (true) {
                if (atEnd()) throw error("字符串没有闭合")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return
                    }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw error("转义符后面没有字符")
                        when (val esc = text[pos]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> pos++
                            'u' -> {
                                if (pos + 4 >= text.length) throw error("\\u 转义不完整")
                                repeat(4) { i ->
                                    if (text[pos + 1 + i] !in '0'..'9' && text[pos + 1 + i] !in 'a'..'f' &&
                                        text[pos + 1 + i] !in 'A'..'F'
                                    ) {
                                        throw error("\\u 转义不是 4 位十六进制")
                                    }
                                }
                                pos += 5
                            }
                            else -> throw error("无效的转义 '\\$esc'")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw error("字符串里不允许控制字符")
                        pos++
                    }
                }
            }
        }

        private fun parseNumber() {
            val start = pos
            if (peek() == '-') pos++
            if (atEnd()) throw error("数字不完整")
            when {
                peek() == '0' -> pos++
                peek() in '1'..'9' -> while (!atEnd() && peek() in '0'..'9') pos++
                else -> throw error("数字格式错误")
            }
            if (!atEnd() && peek() == '.') {
                pos++
                if (atEnd() || peek() !in '0'..'9') throw error("小数点后缺少数字")
                while (!atEnd() && peek() in '0'..'9') pos++
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++
                if (atEnd() || peek() !in '0'..'9') throw error("指数部分缺少数字")
                while (!atEnd() && peek() in '0'..'9') pos++
            }
            if (pos == start) throw error("数字格式错误")
        }
    }

    // ------------------------------------------------------------------ XML

    private fun validateXml(text: String): String? {
        if (text.isBlank()) return "内容是空的"
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            // 不解析外部实体 / DTD（离线校验，不做网络请求；也避免 XXE）
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            var problem: String? = null
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) {
                    if (problem == null) problem = describe(e)
                }

                override fun fatalError(e: SAXParseException) {
                    if (problem == null) problem = describe(e)
                }
            })
            builder.parse(InputSource(StringReader(text)))
            problem
        } catch (e: SAXParseException) {
            describe(e)
        } catch (e: Exception) {
            e.message ?: "XML 解析失败"
        }
    }

    private fun describe(e: SAXParseException): String =
        (e.message ?: "XML 解析失败") + "（第 ${e.lineNumber} 行，第 ${e.columnNumber} 列）"
}
