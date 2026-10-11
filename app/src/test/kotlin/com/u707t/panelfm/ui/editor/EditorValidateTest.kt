package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「校验语法」（NP `menu_xml_valid_check`）：JSON / XML 两种格式的通过 / 失败用例。
 *
 * 实现用的是平台解析器（org.json / javax.xml），所以这里锁的是「谁归谁管」
 * （[EditorValidate.kindOf]）与「失败必须给出可读原因」（不能吞掉错误）。
 */
class EditorValidateTest {

    @Test
    fun `语法映射：只有 JSON 与 XML 可校验`() {
        assertEquals("json", EditorValidate.kindOf("source.json"))
        assertEquals("xml", EditorValidate.kindOf("text.xml"))
        assertNull(EditorValidate.kindOf("source.kotlin"))
        assertNull(EditorValidate.kindOf(null))
    }

    @Test
    fun `合法 JSON 通过（对象与数组）`() {
        assertNull(EditorValidate.validate("""{"a":1,"b":[true,null,"x"]}""", "json"))
        assertNull(EditorValidate.validate("""[1,2,3]""", "json"))
        assertNull(EditorValidate.validate("  {\"a\": {}}  ", "json"))
        assertNull(EditorValidate.validate("""{"n":-1.5e+3,"s":"a\"b\u00e9\/c"}""", "json"))
        assertNull(EditorValidate.validate("{}", "json"))
        assertNull(EditorValidate.validate("[]", "json"))
    }

    @Test
    fun `严格模式：宽松解析器会放过的写法必须报错`() {
        // 键没引号 / 单引号字符串 / 尾逗号 / 注释 / 未定义字面量
        assertNotNull(EditorValidate.validate("{a:1}", "json"))
        assertNotNull(EditorValidate.validate("{'a':1}", "json"))
        assertNotNull(EditorValidate.validate("[1,]", "json"))
        assertNotNull(EditorValidate.validate("{\"a\":1,}", "json"))
        assertNotNull(EditorValidate.validate("[1] // 注释", "json"))
        assertNotNull(EditorValidate.validate("NaN", "json"))
        assertNotNull(EditorValidate.validate("01", "json"))
    }

    @Test
    fun `非法 JSON 报错（缺逗号·缺引号·空内容）并给出行列`() {
        val problem = EditorValidate.validate("{\n  \"a\": 1\n  \"b\": 2\n}", "json")
        assertNotNull(problem)
        assertTrue("错误信息应带行列号：$problem", problem!!.contains("第 3 行"))

        assertNotNull(EditorValidate.validate("", "json"))
        assertNotNull(EditorValidate.validate("{", "json"))
        assertNotNull(EditorValidate.validate("{\"a\":}", "json"))
    }

    @Test
    fun `合法 XML 通过（含声明·注释·属性·自闭合）`() {
        assertNull(
            EditorValidate.validate(
                """<?xml version="1.0" encoding="UTF-8"?>
                   |<!-- 注释 -->
                   |<root a="1"><child/><child>text</child></root>
                """.trimMargin(),
                "xml",
            ),
        )
    }

    @Test
    fun `非法 XML 报错并给出行号`() {
        val bad = "<root>\n  <child>\n</root>"
        val problem = EditorValidate.validate(bad, "xml")
        assertNotNull(problem)
        assertTrue("错误信息应带行列号：$problem", problem!!.contains("行"))

        assertNotNull(EditorValidate.validate("", "xml"))
        assertNotNull(EditorValidate.validate("<a><b></a>", "xml"))
    }

    @Test
    fun `不支持的格式给出明确提示而非静默通过`() {
        val problem = EditorValidate.validate("whatever", "kotlin")
        assertNotNull(problem)
        assertTrue(problem!!.contains("暂不支持"))
    }
}
