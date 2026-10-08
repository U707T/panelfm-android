package com.u707t.panelfm.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `新增语言后缀映射`() {
        assertEquals("source.c", EditorLanguages.scopeOf("main.c"))
        assertEquals("source.c", EditorLanguages.scopeOf("util.h"))
        assertEquals("source.cpp", EditorLanguages.scopeOf("main.cpp"))
        assertEquals("source.cpp", EditorLanguages.scopeOf("widget.hpp"))
        assertEquals("source.cs", EditorLanguages.scopeOf("Program.cs"))
        assertEquals("source.go", EditorLanguages.scopeOf("main.go"))
        assertEquals("source.rust", EditorLanguages.scopeOf("lib.rs"))
        assertEquals("source.php", EditorLanguages.scopeOf("index.php"))
        assertEquals("source.ruby", EditorLanguages.scopeOf("app.rb"))
        assertEquals("source.lua", EditorLanguages.scopeOf("init.lua"))
        assertEquals("source.batchfile", EditorLanguages.scopeOf("start.bat"))
        assertEquals("source.batchfile", EditorLanguages.scopeOf("clean.cmd"))
        assertEquals("source.diff", EditorLanguages.scopeOf("fix.patch"))
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
        assertEquals("source.rust", EditorLanguages.scopeOf("SRC/Lib.RS"))
    }

    @Test
    fun `注释前缀按 scope 给`() {
        assertEquals("//", commentPrefixOf("source.kotlin"))
        assertEquals("//", commentPrefixOf("source.ts"))
        assertEquals("//", commentPrefixOf("source.cpp"))
        assertEquals("//", commentPrefixOf("source.cs"))
        assertEquals("//", commentPrefixOf("source.go"))
        assertEquals("//", commentPrefixOf("source.rust"))
        assertEquals("//", commentPrefixOf("source.php"))
        assertEquals("#", commentPrefixOf("source.python"))
        assertEquals("#", commentPrefixOf("source.yaml"))
        assertEquals("#", commentPrefixOf("source.ruby"))
        assertEquals("--", commentPrefixOf("source.lua"))
        assertEquals("REM ", commentPrefixOf("source.batchfile"))
        assertNull(commentPrefixOf("source.json"))
        assertNull(commentPrefixOf("source.diff"))
        assertNull(commentPrefixOf(null))
    }

    @Test
    fun `css 行注释按扩展名区分`() {
        assertNull(commentPrefixOf("source.css"))
        assertNull(commentPrefixOf("source.css", "css"))
        assertEquals("//", commentPrefixOf("source.css", "scss"))
        assertEquals("//", commentPrefixOf("source.css", "less"))
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
        listOf(
            "source.kotlin", "source.json", "text.xml", "text.html.markdown",
            "source.c", "source.cpp", "source.cs", "source.go", "source.rust",
            "source.php", "source.ruby", "source.lua", "source.batchfile", "source.diff",
        ).forEach {
            assertEquals("$it 应在选择清单里", true, scopes.contains(it))
        }
        assertEquals(scopes.size, scopes.distinct().size)
    }

    @Test
    fun `状态栏语言名映射`() {
        assertEquals("Kotlin", EditorLanguages.labelOf("source.kotlin"))
        assertEquals("JSON", EditorLanguages.labelOf("source.json"))
        assertEquals("C++", EditorLanguages.labelOf("source.cpp"))
        assertEquals("C#", EditorLanguages.labelOf("source.cs"))
        assertEquals("Rust", EditorLanguages.labelOf("source.rust"))
        assertEquals("Batch", EditorLanguages.labelOf("source.batchfile"))
        assertNull(EditorLanguages.labelOf(null))
        assertNull(EditorLanguages.labelOf("source.unknown"))
    }

    @Test
    fun `每个可选语言都有状态栏名字`() {
        EditorLanguages.selectable.forEach { (scope, label) ->
            if (scope.isNotEmpty()) {
                assertEquals("$scope 缺状态栏名字", label, EditorLanguages.labelOf(scope))
                assertTrue("$scope 名字不应等于 scope 本身", label != scope)
            }
        }
    }
}
