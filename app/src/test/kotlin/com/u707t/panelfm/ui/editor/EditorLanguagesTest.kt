package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 后缀 → TextMate scope 的映射（纯 JVM 测试）。 */
class EditorLanguagesTest {

    @Test
    fun `常见后缀映射到对应 scope`() {
        assertEquals("source.kotlin", EditorLanguages.scopeOf("Main.kt"))
        assertEquals("source.kotlin", EditorLanguages.scopeOf("build.gradle.kts"))
        assertEquals("source.java", EditorLanguages.scopeOf("App.java"))
        assertEquals("source.js", EditorLanguages.scopeOf("index.js"))
        assertEquals("source.ts", EditorLanguages.scopeOf("main.ts"))
        assertEquals("source.json", EditorLanguages.scopeOf("data.json"))
        assertEquals("text.xml", EditorLanguages.scopeOf("layout.xml"))
        assertEquals("text.html.basic", EditorLanguages.scopeOf("index.html"))
        assertEquals("source.python", EditorLanguages.scopeOf("tool.py"))
        assertEquals("source.shell", EditorLanguages.scopeOf("run.sh"))
        assertEquals("source.yaml", EditorLanguages.scopeOf("ci.yml"))
        assertEquals("text.html.markdown", EditorLanguages.scopeOf("README.md"))
        assertEquals("source.css", EditorLanguages.scopeOf("style.css"))
    }

    @Test
    fun `未登记的后缀与无后缀返回纯文本`() {
        assertNull(EditorLanguages.scopeOf("notes.txt"))
        assertNull(EditorLanguages.scopeOf("photo.png"))
        assertNull(EditorLanguages.scopeOf("Makefile"))
        assertNull(EditorLanguages.scopeOf("app.properties"))
    }

    @Test
    fun `大小写与路径不影响识别`() {
        assertEquals("source.kotlin", EditorLanguages.scopeOf("SRC/Main.KT"))
        assertEquals("source.json", EditorLanguages.scopeOf("/a/b/c/Data.JSON"))
    }

    @Test
    fun `注释前缀按 scope 给`() {
        assertEquals("//", commentPrefixOf("source.kotlin"))
        assertEquals("//", commentPrefixOf("source.ts"))
        assertEquals("#", commentPrefixOf("source.python"))
        assertEquals("#", commentPrefixOf("source.yaml"))
        assertNull(commentPrefixOf("source.json"))
        assertNull(commentPrefixOf(null))
    }

    @Test
    fun `长行文本默认自动换行，代码默认关`() {
        assertEquals(true, defaultWordwrap(null))
        assertEquals(true, defaultWordwrap("text.html.markdown"))
        assertEquals(false, defaultWordwrap("source.kotlin"))
        assertEquals(false, defaultWordwrap("source.json"))
    }

    @Test
    fun `语法选择菜单含纯文本与全部语言`() {
        val scopes = EditorLanguages.selectable.map { it.first }
        assertEquals("", scopes.first())
        assertEquals(listOf("纯文本"), EditorLanguages.selectable.take(1).map { it.second })
        listOf("source.kotlin", "source.json", "text.xml", "text.html.markdown").forEach {
            assertEquals("$it 应在选择清单里", true, scopes.contains(it))
        }
        assertEquals(scopes.size, scopes.distinct().size)
    }

    @Test
    fun `状态栏语言名映射`() {
        assertEquals("Kotlin", EditorLanguages.labelOf("source.kotlin"))
        assertEquals("JSON", EditorLanguages.labelOf("source.json"))
        assertNull(EditorLanguages.labelOf(null))
        assertNull(EditorLanguages.labelOf("source.unknown"))
    }
}
