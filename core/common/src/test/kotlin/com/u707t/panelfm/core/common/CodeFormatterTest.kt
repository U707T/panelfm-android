package com.u707t.panelfm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「格式化代码」（MT 菜单 0x7f0e001b · 0x7f110411）：JSON / XML 两种安全实现 */
class CodeFormatterTest {

    @Test
    fun `JSON 紧凑转缩进`() {
        val out = CodeFormatter.formatJson("""{"a":1,"b":[1,2]}""")
        assertEquals(
            """
            {
              "a": 1,
              "b": [
                1,
                2
              ]
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `JSON 字符串里的括号逗号不被破坏`() {
        val out = CodeFormatter.formatJson("""{"k":"a,{b}:[c]"}""")
        assertEquals(
            """
            {
              "k": "a,{b}:[c]"
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `JSON 空容器不展开`() {
        assertEquals("{}", CodeFormatter.formatJson("{}"))
        // 空容器本身不展开，但外层对象仍按规则缩进
        assertEquals("{\n  \"a\": {}\n}", CodeFormatter.formatJson("""{ "a": {} }"""))
    }

    @Test
    fun `JSON 转义引号不打断字符串状态`() {
        val out = CodeFormatter.formatJson("""{"k":"say \"hi\""}""")
        assertEquals(
            """
            {
              "k": "say \"hi\""
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `XML 按标签深度缩进`() {
        val out = CodeFormatter.formatXml("<a><b>t</b><c/></a>")
        assertEquals(
            """
            <a>
              <b>t</b>
              <c/>
            </a>
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `XML 声明与注释原样保留`() {
        val out = CodeFormatter.formatXml("<?xml version=\"1.0\"?><a><!-- x --><b/></a>")
        assertEquals(
            """
            <?xml version="1.0"?>
            <a>
              <!-- x -->
              <b/>
            </a>
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `XML 属性里的尖括号不会截断标签`() {
        val out = CodeFormatter.formatXml("<a title=\"1>0\"><b/></a>")
        assertEquals("<a title=\"1>0\">\n  <b/>\n</a>", out)
    }

    @Test
    fun `不支持的语言返回 null`() {
        assertNull(CodeFormatter.format("x=1", "kotlin"))
        assertEquals(true, CodeFormatter.supports("json"))
        assertEquals(true, CodeFormatter.supports("XML"))
    }
}
