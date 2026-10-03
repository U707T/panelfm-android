package com.u707t.panelfm.ui.editor

/**
 * 轻量语法词法分析器（零依赖，可在 JVM 上单测）。
 *
 *  - 覆盖内置编辑器的主要语言：JSON / XML / HTML / Kotlin / Java / JavaScript / TypeScript /
 *    Python / Shell / Properties / INI / YAML / Markdown / CSS
 *  - 只做「够用、够快」的高亮：注释 / 字符串 / 数字 / 关键字 / 键名 / 标签 / 属性 / 注解 / 标题 / 代码
 *  - 输出 [TokenSpan] 列表，由 UI 侧映射成颜色（本文件不依赖 Compose / Android，便于单元测试）
 *  - 单次线性扫描；span 数量有硬上限（防御超长压缩 JSON 等病态输入）
 */
enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, KEY, TAG, ATTR, ANNOTATION, HEADING, CODE }

/** 高亮区间（[start, end) 为字符下标） */
data class TokenSpan(val start: Int, val end: Int, val type: TokenType)

/** 编辑器支持的语法（按文件名/后缀识别） */
enum class SyntaxLanguage(val label: String) {
    PLAIN("纯文本"),
    KOTLIN("Kotlin"),
    JAVA("Java"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    JSON("JSON"),
    XML("XML"),
    HTML("HTML"),
    PYTHON("Python"),
    SHELL("Shell"),
    PROPERTIES("Properties"),
    INI("INI"),
    YAML("YAML"),
    MARKDOWN("Markdown"),
    CSS("CSS");

    companion object {
        fun ofFileName(fileName: String): SyntaxLanguage {
            val name = fileName.substringAfterLast('/').lowercase()
            return when (name.substringAfterLast('.', "")) {
                "kt", "kts" -> KOTLIN
                "java" -> JAVA
                "js", "mjs", "cjs", "jsx" -> JAVASCRIPT
                "ts", "tsx", "mts", "cts" -> TYPESCRIPT
                "json" -> JSON
                "xml", "svg", "plist", "xsd", "xsl" -> XML
                "html", "htm", "xhtml" -> HTML
                "py", "pyw" -> PYTHON
                "sh", "bash", "zsh", "ksh", "env" -> SHELL
                "properties", "props" -> PROPERTIES
                "ini", "cfg", "conf", "toml" -> INI
                "yml", "yaml" -> YAML
                "md", "markdown" -> MARKDOWN
                "css", "scss", "less" -> CSS
                else -> PLAIN
            }
        }
    }
}

object SyntaxLexer {

    /** span 数量上限（超出后停止扫描，只保证前缀高亮） */
    const val MAX_SPANS = 20_000

    fun highlight(text: String, lang: SyntaxLanguage): List<TokenSpan> {
        if (text.isEmpty() || lang == SyntaxLanguage.PLAIN) return emptyList()
        val out = ArrayList<TokenSpan>()
        when (lang) {
            SyntaxLanguage.KOTLIN -> scanCLike(text, out, CLike(keywords = KOTLIN_KEYWORDS, annotations = true))
            SyntaxLanguage.JAVA -> scanCLike(text, out, CLike(keywords = JAVA_KEYWORDS, annotations = true))
            SyntaxLanguage.JAVASCRIPT -> scanCLike(text, out, CLike(keywords = JS_KEYWORDS, backtick = true))
            SyntaxLanguage.TYPESCRIPT -> scanCLike(text, out, CLike(keywords = JS_KEYWORDS + TS_KEYWORDS, backtick = true, annotations = true))
            SyntaxLanguage.PYTHON -> scanCLike(
                text, out,
                CLike(keywords = PYTHON_KEYWORDS, lineComment = "#", blockComment = null, tripleQuotes = true, annotations = true),
            )
            SyntaxLanguage.SHELL -> scanCLike(
                text, out,
                CLike(keywords = SHELL_KEYWORDS, lineComment = "#", blockComment = null, shellVars = true),
            )
            SyntaxLanguage.CSS -> scanCLike(
                text, out,
                CLike(lineComment = null, blockComment = "/*" to "*/", atKeywords = true, hashNumber = true),
            )
            SyntaxLanguage.JSON -> scanJson(text, out)
            SyntaxLanguage.XML -> scanMarkup(text, out)
            SyntaxLanguage.HTML -> scanMarkup(text, out)
            SyntaxLanguage.PROPERTIES -> scanKeyValue(text, out, ini = false)
            SyntaxLanguage.INI -> scanKeyValue(text, out, ini = true)
            SyntaxLanguage.YAML -> scanYaml(text, out)
            SyntaxLanguage.MARKDOWN -> scanMarkdown(text, out)
            SyntaxLanguage.PLAIN -> Unit
        }
        return out
    }

    // ------------------------------------------------------------------ 通用工具

    private fun isIdentStart(c: Char): Boolean = Character.isLetter(c) || c == '_' || c == '$'

    private fun isIdentPart(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || c == '$'

    private fun scanIdent(s: String, start: Int): Int {
        var i = start
        while (i < s.length && isIdentPart(s[i])) i++
        return i
    }

    /** 数字（十进制 / 十六进制 / 二进制 / 下划线分隔 / 小数 / 指数 / 常见后缀） */
    private fun scanNumber(s: String, start: Int): Int {
        var i = start
        if (s[i] == '0' && i + 1 < s.length) {
            when (s[i + 1]) {
                'x', 'X' -> {
                    i += 2
                    while (i < s.length && (s[i].isDigit() || s[i] in 'a'..'f' || s[i] in 'A'..'F' || s[i] == '_')) i++
                    return i
                }
                'b', 'B' -> {
                    i += 2
                    while (i < s.length && (s[i] == '0' || s[i] == '1' || s[i] == '_')) i++
                    return i
                }
            }
        }
        while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
        if (i < s.length && s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()) {
            i++
            while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            var k = i + 1
            if (k < s.length && (s[k] == '+' || s[k] == '-')) k++
            if (k < s.length && s[k].isDigit()) {
                i = k
                while (i < s.length && s[i].isDigit()) i++
            }
        }
        while (i < s.length && s[i] in "fFlLuU") i++
        return i
    }

    /** 字符串字面量；[start] 指向起始引号；未闭合时止于行尾（三引号止于文件尾） */
    private fun scanStringLiteral(s: String, start: Int, quote: Char, triple: Boolean = false): Int {
        var i = start + if (triple) 3 else 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (triple) {
                if (c == quote && i + 2 < s.length && s[i + 1] == quote && s[i + 2] == quote) return i + 3
            } else {
                if (c == quote) return i + 1
                if (c == '\n') return i
            }
            i++
        }
        return s.length
    }

    private fun add(out: MutableList<TokenSpan>, start: Int, end: Int, type: TokenType): Boolean {
        if (end <= start) return true
        if (out.size >= MAX_SPANS) return false
        out.add(TokenSpan(start, end, type))
        return true
    }

    // ------------------------------------------------------------------ C 系语言（Kotlin/Java/JS/TS/Python/Shell/CSS）

    private class CLike(
        val keywords: Set<String> = emptySet(),
        val lineComment: String? = "//",
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val stringQuotes: Set<Char> = setOf('"', '\''),
        val backtick: Boolean = false,
        val tripleQuotes: Boolean = false,
        val annotations: Boolean = false,
        val atKeywords: Boolean = false,
        val shellVars: Boolean = false,
        val hashNumber: Boolean = false,
    )

    private fun scanCLike(s: String, out: MutableList<TokenSpan>, cfg: CLike) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                cfg.lineComment != null && s.startsWith(cfg.lineComment, i) -> {
                    var e = s.indexOf('\n', i)
                    if (e < 0) e = s.length
                    if (!add(out, i, e, TokenType.COMMENT)) return
                    i = e
                }
                cfg.blockComment != null && s.startsWith(cfg.blockComment.first, i) -> {
                    val close = s.indexOf(cfg.blockComment.second, i + cfg.blockComment.first.length)
                    val e = if (close < 0) s.length else close + cfg.blockComment.second.length
                    if (!add(out, i, e, TokenType.COMMENT)) return
                    i = e
                }
                (cfg.annotations || cfg.atKeywords) && c == '@' && i + 1 < s.length && isIdentStart(s[i + 1]) -> {
                    val e = scanIdent(s, i + 1)
                    if (!add(out, i, e, if (cfg.annotations) TokenType.ANNOTATION else TokenType.KEYWORD)) return
                    i = e
                }
                cfg.shellVars && c == '$' -> {
                    var e = i + 1
                    if (e < s.length && s[e] == '{') {
                        val close = s.indexOf('}', e)
                        e = if (close < 0) s.length else close + 1
                    } else {
                        while (e < s.length && isIdentPart(s[e])) e++
                    }
                    if (!add(out, i, e, TokenType.KEY)) return
                    i = e
                }
                c in cfg.stringQuotes || (cfg.backtick && c == '`') -> {
                    val triple = cfg.tripleQuotes && (c == '"' || c == '\'') && s.startsWith("$c$c$c", i)
                    val e = scanStringLiteral(s, i, c, triple)
                    if (!add(out, i, e, TokenType.STRING)) return
                    i = e
                }
                cfg.hashNumber && c == '#' && i + 1 < s.length && isHex(s[i + 1]) -> {
                    var e = i + 1
                    while (e < s.length && isHex(s[e])) e++
                    val len = e - i - 1
                    if (len == 3 || len == 4 || len == 6 || len == 8) {
                        if (!add(out, i, e, TokenType.NUMBER)) return
                        i = e
                    } else {
                        i++
                    }
                }
                c.isDigit() -> {
                    val e = scanNumber(s, i)
                    if (!add(out, i, e, TokenType.NUMBER)) return
                    i = e
                }
                isIdentStart(c) -> {
                    val e = scanIdent(s, i)
                    if (s.substring(i, e) in cfg.keywords) {
                        if (!add(out, i, e, TokenType.KEYWORD)) return
                    }
                    i = e
                }
                else -> i++
            }
        }
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    // ------------------------------------------------------------------ JSON

    private fun scanJson(s: String, out: MutableList<TokenSpan>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    val e = scanStringLiteral(s, i, '"')
                    var j = e
                    while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                    val isKey = j < s.length && s[j] == ':'
                    if (!add(out, i, e, if (isKey) TokenType.KEY else TokenType.STRING)) return
                    i = e
                }
                c == '-' && i + 1 < s.length && s[i + 1].isDigit() -> {
                    val e = scanNumber(s, i + 1)
                    if (!add(out, i, e, TokenType.NUMBER)) return
                    i = e
                }
                c.isDigit() -> {
                    val e = scanNumber(s, i)
                    if (!add(out, i, e, TokenType.NUMBER)) return
                    i = e
                }
                isIdentStart(c) -> {
                    val e = scanIdent(s, i)
                    if (s.substring(i, e) in JSON_CONSTANTS) {
                        if (!add(out, i, e, TokenType.KEYWORD)) return
                    }
                    i = e
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ XML / HTML

    private fun scanMarkup(s: String, out: MutableList<TokenSpan>) {
        var i = 0
        while (i < s.length) {
            if (s.startsWith("<!--", i)) {
                val close = s.indexOf("-->", i + 4)
                val e = if (close < 0) s.length else close + 3
                if (!add(out, i, e, TokenType.COMMENT)) return
                i = e
                continue
            }
            if (s[i] != '<') {
                i++
                continue
            }
            // <!DOCTYPE ...> / <?xml ... ?> / <tag ...> / </tag>
            when {
                s.startsWith("<!", i) -> {
                    val close = s.indexOf('>', i)
                    val e = if (close < 0) s.length else close + 1
                    if (!add(out, i, e, TokenType.TAG)) return
                    i = e
                }
                s.startsWith("<?", i) -> {
                    val close = s.indexOf("?>", i)
                    val e = if (close < 0) s.length else close + 2
                    if (!add(out, i, e, TokenType.TAG)) return
                    i = e
                }
                else -> {
                    var j = i + 1
                    if (j < s.length && s[j] == '/') j++
                    val nameStart = j
                    while (j < s.length && (isIdentPart(s[j]) || s[j] == ':' || s[j] == '-' || s[j] == '.')) j++
                    if (j == nameStart) {
                        i++
                        continue
                    }
                    // 标签名（含 <、</ 前缀）
                    if (!add(out, i, j, TokenType.TAG)) return
                    // 属性与属性值
                    while (j < s.length && s[j] != '>' && !s.startsWith("/>", j)) {
                        val c = s[j]
                        when {
                            c == ' ' || c == '\t' || c == '\n' || c == '\r' -> j++
                            isIdentStart(c) -> {
                                val e = scanIdent(s, j)
                                if (!add(out, j, e, TokenType.ATTR)) return
                                j = e
                            }
                            c == '=' -> {
                                j++
                                while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                                if (j < s.length && (s[j] == '"' || s[j] == '\'')) {
                                    val e = scanStringLiteral(s, j, s[j])
                                    if (!add(out, j, e, TokenType.STRING)) return
                                    j = e
                                }
                            }
                            else -> j++
                        }
                    }
                    // 跳过 '>'
                    val close = s.indexOf('>', j)
                    i = if (close < 0) s.length else close + 1
                }
            }
        }
    }

    // ------------------------------------------------------------------ Properties / INI

    private fun scanKeyValue(s: String, out: MutableList<TokenSpan>, ini: Boolean) {
        var i = 0
        var atLineStart = true
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\n' -> {
                    atLineStart = true
                    i++
                }
                atLineStart && (c == ' ' || c == '\t' || c == '\r') -> i++
                c == '#' || c == ';' || (c == '!' && atLineStart) -> {
                    var e = s.indexOf('\n', i)
                    if (e < 0) e = s.length
                    if (!add(out, i, e, TokenType.COMMENT)) return
                    i = e
                    atLineStart = false
                }
                atLineStart && ini && c == '[' -> {
                    val close = s.indexOf(']', i)
                    val e = if (close < 0) s.length else close + 1
                    if (!add(out, i, e, TokenType.TAG)) return
                    i = e
                    atLineStart = false
                }
                atLineStart -> {
                    var j = i
                    while (j < s.length && s[j] != '=' && s[j] != ':' && s[j] != '\n') j++
                    val sepFound = j < s.length && (s[j] == '=' || s[j] == ':')
                    var keyEnd = j
                    while (keyEnd > i && (s[keyEnd - 1] == ' ' || s[keyEnd - 1] == '\t' || s[keyEnd - 1] == '\r')) keyEnd--
                    if (sepFound && keyEnd > i) {
                        if (!add(out, i, keyEnd, TokenType.KEY)) return
                    }
                    i = if (sepFound) j + 1 else j
                    atLineStart = false
                }
                else -> when {
                    c == '"' || c == '\'' -> {
                        val e = scanStringLiteral(s, i, c)
                        if (!add(out, i, e, TokenType.STRING)) return
                        i = e
                    }
                    c.isDigit() && (i == 0 || !isIdentPart(s[i - 1])) -> {
                        val e = scanNumber(s, i)
                        if (!add(out, i, e, TokenType.NUMBER)) return
                        i = e
                    }
                    else -> i++
                }
            }
        }
    }

    // ------------------------------------------------------------------ YAML

    private fun scanYaml(s: String, out: MutableList<TokenSpan>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '#' && (i == 0 || s[i - 1] == '\n' || s[i - 1] == ' ' || s[i - 1] == '\t') -> {
                    var e = s.indexOf('\n', i)
                    if (e < 0) e = s.length
                    if (!add(out, i, e, TokenType.COMMENT)) return
                    i = e
                }
                c == '"' || c == '\'' -> {
                    val e = scanStringLiteral(s, i, c)
                    if (!add(out, i, e, TokenType.STRING)) return
                    i = e
                }
                c.isDigit() && (i == 0 || !isIdentPart(s[i - 1])) -> {
                    val e = scanNumber(s, i)
                    if (!add(out, i, e, TokenType.NUMBER)) return
                    i = e
                }
                isIdentStart(c) -> {
                    val e = scanIdent(s, i)
                    val word = s.substring(i, e)
                    when {
                        isYamlKeyPosition(s, i) -> {
                            var j = e
                            while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                            if (j < s.length && s[j] == ':' && (j + 1 >= s.length || s[j + 1] != ':')) {
                                if (!add(out, i, e, TokenType.KEY)) return
                            }
                        }
                        word in YAML_CONSTANTS -> if (!add(out, i, e, TokenType.KEYWORD)) return
                    }
                    i = e
                }
                else -> i++
            }
        }
    }

    /** YAML 键名位置：行首 / `- ` 之后 */
    private fun isYamlKeyPosition(s: String, start: Int): Boolean {
        var j = start - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) j--
        if (j < 0 || s[j] == '\n') return true
        if (s[j] == '-') {
            var k = j - 1
            while (k >= 0 && (s[k] == ' ' || s[k] == '\t')) k--
            return k < 0 || s[k] == '\n'
        }
        return false
    }

    // ------------------------------------------------------------------ Markdown

    private fun scanMarkdown(s: String, out: MutableList<TokenSpan>) {
        var i = 0
        var lineStart = true
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') {
                lineStart = true
                i++
                continue
            }
            if (lineStart) {
                // 围栏代码块 ``` / ~~~
                if (s.startsWith("```", i) || s.startsWith("~~~", i)) {
                    val fence = s.substring(i, i + 3)
                    val end = findFenceEnd(s, i + 3, fence)
                    if (!add(out, i, end, TokenType.CODE)) return
                    i = end
                    lineStart = end > 0 && s[end - 1] == '\n'
                    continue
                }
                // 标题 # ... ######
                var h = 0
                var j = i
                while (j < s.length && s[j] == '#') {
                    h++
                    j++
                }
                if (h in 1..6 && j < s.length && (s[j] == ' ' || s[j] == '\t')) {
                    var e = s.indexOf('\n', j)
                    if (e < 0) e = s.length
                    if (!add(out, i, e, TokenType.HEADING)) return
                    i = e
                    continue
                }
                lineStart = false
            }
            when {
                c == '`' -> {
                    val close = s.indexOf('`', i + 1)
                    if (close < 0) {
                        i++
                    } else {
                        if (!add(out, i, close + 1, TokenType.CODE)) return
                        i = close + 1
                    }
                }
                c == ']' && i + 1 < s.length && s[i + 1] == '(' -> {
                    val close = s.indexOf(')', i + 2)
                    val e = if (close < 0) s.length else close
                    if (!add(out, i + 2, e, TokenType.STRING)) return
                    i = if (close < 0) s.length else close + 1
                }
                else -> i++
            }
        }
    }

    /** 围栏代码块结束行（行首再次出现围栏）；返回块尾下标（不含换行） */
    private fun findFenceEnd(s: String, from: Int, fence: String): Int {
        var i = from
        while (i < s.length) {
            var lineEnd = s.indexOf('\n', i)
            if (lineEnd < 0) lineEnd = s.length
            if (s.startsWith(fence, i)) return lineEnd
            i = lineEnd + 1
        }
        return s.length
    }

    // ------------------------------------------------------------------ 关键字表

    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "typealias",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue", "throw", "try",
        "catch", "finally", "is", "in", "as", "by", "where", "null", "true", "false", "this", "super",
        "private", "public", "protected", "internal", "open", "abstract", "sealed", "data", "enum",
        "annotation", "companion", "init", "constructor", "override", "lateinit", "const", "suspend",
        "inline", "noinline", "crossinline", "reified", "operator", "infix", "tailrec", "external",
        "vararg", "out", "dynamic", "expect", "actual", "get", "set", "field", "it",
    )

    private val JAVA_KEYWORDS = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private",
        "protected", "static", "final", "abstract", "synchronized", "volatile", "transient", "native",
        "strictfp", "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
        "if", "else", "switch", "case", "default", "for", "while", "do", "break", "continue", "return",
        "try", "catch", "finally", "throw", "throws", "new", "this", "super", "instanceof", "assert",
        "null", "true", "false", "var", "record", "sealed", "permits", "yield",
    )

    private val JS_KEYWORDS = setOf(
        "function", "var", "let", "const", "if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "return", "new", "this", "super", "try", "catch", "finally",
        "throw", "typeof", "instanceof", "in", "of", "class", "extends", "import", "export", "from",
        "as", "async", "await", "yield", "static", "get", "set", "delete", "void", "with", "debugger",
        "null", "undefined", "true", "false", "NaN", "Infinity",
    )

    private val TS_KEYWORDS = setOf(
        "type", "interface", "namespace", "declare", "readonly", "abstract", "implements", "private",
        "public", "protected", "enum", "keyof", "infer", "is", "asserts", "satisfies", "any", "string",
        "number", "boolean", "symbol", "unknown", "never", "object", "void",
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "pass",
        "break", "continue", "try", "except", "finally", "raise", "with", "lambda", "global", "nonlocal",
        "yield", "assert", "del", "in", "is", "not", "and", "or", "None", "True", "False", "async",
        "await", "match", "case", "self", "print",
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "function", "in", "return", "break", "continue", "local", "export", "readonly", "declare",
        "source", "alias", "unset", "shift", "exit", "set", "trap", "eval", "exec", "time",
    )

    private val JSON_CONSTANTS = setOf("true", "false", "null")

    private val YAML_CONSTANTS = setOf("true", "false", "null", "True", "False", "Null", "yes", "no", "on", "off", "Yes", "No", "On", "Off")
}
