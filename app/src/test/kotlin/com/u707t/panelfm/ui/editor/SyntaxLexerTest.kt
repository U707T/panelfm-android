package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语法高亮词法分析器：各语言的关键 token 用例（纯 JVM 测试）。 */
class SyntaxLexerTest {

    private fun of(text: String, type: TokenType, lang: SyntaxLanguage): List<String> =
        SyntaxLexer.highlight(text, lang).filter { it.type == type }.map { text.substring(it.start, it.end) }

    // ------------------------------------------------------------------ 语言识别

    @Test
    fun languageDetectionByExtension() {
        assertEquals(SyntaxLanguage.KOTLIN, SyntaxLanguage.ofFileName("Main.kt"))
        assertEquals(SyntaxLanguage.KOTLIN, SyntaxLanguage.ofFileName("build.gradle.kts"))
        assertEquals(SyntaxLanguage.JAVA, SyntaxLanguage.ofFileName("App.java"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.ofFileName("index.js"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, SyntaxLanguage.ofFileName("main.ts"))
        assertEquals(SyntaxLanguage.JSON, SyntaxLanguage.ofFileName("data.json"))
        assertEquals(SyntaxLanguage.XML, SyntaxLanguage.ofFileName("layout.xml"))
        assertEquals(SyntaxLanguage.HTML, SyntaxLanguage.ofFileName("index.html"))
        assertEquals(SyntaxLanguage.PYTHON, SyntaxLanguage.ofFileName("tool.py"))
        assertEquals(SyntaxLanguage.SHELL, SyntaxLanguage.ofFileName("run.sh"))
        assertEquals(SyntaxLanguage.PROPERTIES, SyntaxLanguage.ofFileName("app.properties"))
        assertEquals(SyntaxLanguage.INI, SyntaxLanguage.ofFileName("config.ini"))
        assertEquals(SyntaxLanguage.YAML, SyntaxLanguage.ofFileName("ci.yml"))
        assertEquals(SyntaxLanguage.MARKDOWN, SyntaxLanguage.ofFileName("README.md"))
        assertEquals(SyntaxLanguage.CSS, SyntaxLanguage.ofFileName("style.css"))
        assertEquals(SyntaxLanguage.PLAIN, SyntaxLanguage.ofFileName("notes.txt"))
        assertEquals(SyntaxLanguage.PLAIN, SyntaxLanguage.ofFileName("photo.png"))
    }

    // ------------------------------------------------------------------ Kotlin

    @Test
    fun kotlinBasics() {
        val text = "fun main() {\n    val answer = 42 // 答案\n    println(\"hi\")\n}"
        assertEquals(listOf("fun", "val"), of(text, TokenType.KEYWORD, SyntaxLanguage.KOTLIN))
        assertEquals(listOf("42"), of(text, TokenType.NUMBER, SyntaxLanguage.KOTLIN))
        assertEquals(listOf("\"hi\""), of(text, TokenType.STRING, SyntaxLanguage.KOTLIN))
        assertTrue(of(text, TokenType.COMMENT, SyntaxLanguage.KOTLIN).any { it.contains("答案") })
    }

    @Test
    fun kotlinAnnotationAndBlockComment() {
        val text = "@Composable\nfun Foo() { /* 注释 */ }"
        assertEquals(listOf("@Composable"), of(text, TokenType.ANNOTATION, SyntaxLanguage.KOTLIN))
        assertEquals(listOf("/* 注释 */"), of(text, TokenType.COMMENT, SyntaxLanguage.KOTLIN))
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun jsonKeysValuesAndConstants() {
        val text = """{"name": "PanelFM", "count": 3, "ok": true}"""
        assertEquals(
            listOf("\"name\"", "\"count\"", "\"ok\""),
            of(text, TokenType.KEY, SyntaxLanguage.JSON),
        )
        assertEquals(listOf("\"PanelFM\""), of(text, TokenType.STRING, SyntaxLanguage.JSON))
        assertEquals(listOf("3"), of(text, TokenType.NUMBER, SyntaxLanguage.JSON))
        assertEquals(listOf("true"), of(text, TokenType.KEYWORD, SyntaxLanguage.JSON))
    }

    // ------------------------------------------------------------------ XML / HTML

    @Test
    fun xmlTagAttributeString() {
        val text = """<a href="https://x.y">link</a>"""
        val tags = of(text, TokenType.TAG, SyntaxLanguage.XML)
        assertTrue(tags.contains("<a"))
        assertTrue(tags.contains("</a"))
        assertEquals(listOf("href"), of(text, TokenType.ATTR, SyntaxLanguage.XML))
        assertEquals(listOf("\"https://x.y\""), of(text, TokenType.STRING, SyntaxLanguage.XML))
    }

    @Test
    fun xmlCommentAndDeclaration() {
        val text = "<?xml version=\"1.0\"?>\n<!-- hi -->\n<root/>"
        assertTrue(of(text, TokenType.TAG, SyntaxLanguage.XML).any { it.startsWith("<?xml") })
        assertEquals(listOf("<!-- hi -->"), of(text, TokenType.COMMENT, SyntaxLanguage.XML))
    }

    @Test
    fun markupWithLoneAngleBracketDoesNotCrash() {
        val text = "a < b and c > d"
        assertTrue(SyntaxLexer.highlight(text, SyntaxLanguage.HTML).isEmpty())
    }

    // ------------------------------------------------------------------ YAML

    @Test
    fun yamlKeysAndConstants() {
        val text = "name: PanelFM\nenabled: true\nlist:\n  - item: 1\n"
        assertEquals(listOf("name", "enabled", "list", "item"), of(text, TokenType.KEY, SyntaxLanguage.YAML))
        assertEquals(listOf("true"), of(text, TokenType.KEYWORD, SyntaxLanguage.YAML))
        assertEquals(listOf("1"), of(text, TokenType.NUMBER, SyntaxLanguage.YAML))
    }

    // ------------------------------------------------------------------ Properties / INI

    @Test
    fun propertiesKeys() {
        val text = "server.host=192.168.1.1\nport=8080\n# 注释\n"
        assertEquals(listOf("server.host", "port"), of(text, TokenType.KEY, SyntaxLanguage.PROPERTIES))
        assertTrue(of(text, TokenType.COMMENT, SyntaxLanguage.PROPERTIES).any { it.contains("注释") })
    }

    @Test
    fun iniSection() {
        val text = "[main]\nkey = value\n"
        assertEquals(listOf("[main]"), of(text, TokenType.TAG, SyntaxLanguage.INI))
        // 属性/INI：值里的数字不上色时也允许（此处仅断言键名）
        assertTrue(of(text, TokenType.KEY, SyntaxLanguage.INI).contains("key"))
    }

    // ------------------------------------------------------------------ Python / Shell

    @Test
    fun pythonBasics() {
        val text = "def f(x):\n    return \"a\\\"b\" # 注释\n"
        assertEquals(listOf("def", "return"), of(text, TokenType.KEYWORD, SyntaxLanguage.PYTHON))
        assertEquals(listOf("\"a\\\"b\""), of(text, TokenType.STRING, SyntaxLanguage.PYTHON))
        assertTrue(of(text, TokenType.COMMENT, SyntaxLanguage.PYTHON).any { it.contains("注释") })
    }

    @Test
    fun pythonTripleQuotedString() {
        val text = "doc = \"\"\"多行\n字符串\"\"\"\n"
        assertTrue(of(text, TokenType.STRING, SyntaxLanguage.PYTHON).any { it.startsWith("\"\"\"") })
    }

    @Test
    fun shellVariablesAndComments() {
        val text = "#!/bin/sh\n# note\necho \$HOME\n"
        assertTrue(of(text, TokenType.COMMENT, SyntaxLanguage.SHELL).contains("#!/bin/sh"))
        assertEquals(listOf("\$HOME"), of(text, TokenType.KEY, SyntaxLanguage.SHELL))
    }

    // ------------------------------------------------------------------ Markdown

    @Test
    fun markdownHeadingCodeAndFence() {
        val text = "# 标题\n\n`code`\n\n```kt\nval a = 1\n```\n"
        assertEquals(listOf("# 标题"), of(text, TokenType.HEADING, SyntaxLanguage.MARKDOWN))
        val code = of(text, TokenType.CODE, SyntaxLanguage.MARKDOWN)
        assertTrue(code.contains("`code`"))
        assertTrue(code.any { it.startsWith("```kt") })
    }

    // ------------------------------------------------------------------ CSS

    @Test
    fun cssAtRuleAndColor() {
        val text = "@media (min-width: 100px) { .x { color: #fff; } }"
        assertTrue(of(text, TokenType.KEYWORD, SyntaxLanguage.CSS).contains("@media"))
        assertTrue(of(text, TokenType.NUMBER, SyntaxLanguage.CSS).contains("#fff"))
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun unterminatedStringStopsAtLineEnd() {
        val text = "val s = \"abc\nval t = 2"
        assertEquals(listOf("\"abc"), of(text, TokenType.STRING, SyntaxLanguage.KOTLIN))
        assertEquals(listOf("2"), of(text, TokenType.NUMBER, SyntaxLanguage.KOTLIN))
    }

    @Test
    fun spanCountIsCapped() {
        val text = List(30_000) { "\"a\"" }.joinToString(" ")
        assertEquals(SyntaxLexer.MAX_SPANS, SyntaxLexer.highlight(text, SyntaxLanguage.JSON).size)
    }

    @Test
    fun emptyAndPlainReturnNothing() {
        assertTrue(SyntaxLexer.highlight("", SyntaxLanguage.KOTLIN).isEmpty())
        assertTrue(SyntaxLexer.highlight("hello", SyntaxLanguage.PLAIN).isEmpty())
    }
}
