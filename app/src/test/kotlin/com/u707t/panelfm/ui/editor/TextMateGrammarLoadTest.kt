package com.u707t.panelfm.ui.editor

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真·注册表加载（不是只读 JSON）：用与 App 完全相同的 sora 注册表把 `languages.json`
 * 全量加载一遍，并对每种语言跑一行样例分词。
 *
 * 为什么必须有：语法文件与语言配置里的正则走的是 sora 内置的 Oniguruma 实现（Joni），
 * 有些「JS 正则合法」的写法它直接报错 —— 而 `loadGrammars` 是**整批加载**的，
 * 一个语言出错会让**整个注册表**失败（真机表现 = 所有语言的高亮都没了）。
 * 上一轮新增 10 种语言时，`go/language-configuration.json` 的 `[]`（空字符类）
 * 就是被这个测试思路提前抓出来的；这里把它固化成回归。
 *
 * JVM 可跑：加载路径不碰 Android API（TM4E 的日志会走 android.util.Log 桩，
 * 单测里 `isReturnDefaultValues = true` 已兜住）。
 */
class TextMateGrammarLoadTest {

    private val assetsRoot: File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到 assets 目录（单测工作目录应为 app/，实际：${File(".").absolutePath}）")

    @Test
    fun `全量语法可加载且每种语言都能分词`() {
        FileProviderRegistry.getInstance().addFileProvider(
            FileResolver { path ->
                val file = File(assetsRoot, path)
                if (file.isFile) file.inputStream() else null
            },
        )
        val grammars = GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        assertEquals("加载数量应与 languages.json 一致", 22, grammars.size)

        // 每种语言一行样例：既验证注册，也验证语法正则能真的跑起来（Joni 兼容性）
        val samples = mapOf(
            "source.kotlin" to "val x = 1",
            "source.java" to "int x = 1;",
            "source.c" to "int add(int a, int b) { return a + b; }",
            "source.cpp" to "int main() { std::vector<int> v; return 0; }",
            "source.cs" to "public class A { void B() { var x = 1; } }",
            "source.go" to "func main() { fmt.Println(\"hi\") }",
            "source.rust" to "fn main() { let x: i32 = 1; }",
            "source.js" to "const a = () => 1;",
            "source.ts" to "const a: number = 1;",
            "source.json" to "{\"a\": 1}",
            "text.xml" to "<a b=\"c\">d</a>",
            "text.html.basic" to "<div class=\"a\">x</div>",
            "source.css" to "a { color: red; }",
            "source.python" to "def f(x):\n    return x",
            "source.php" to "<?php echo \"hi\"; \$a = new Foo(); ?>",
            "source.ruby" to "def foo(x); puts \"hi\"; end",
            "source.lua" to "local function f(x) return x + 1 end",
            "source.shell" to "if [ -f x ]; then echo hi; fi",
            "source.batchfile" to "@echo off\nif \"%X%\"==\"1\" echo hi",
            "source.yaml" to "a: 1\nb:\n  - c",
            "text.html.markdown" to "# Title\n\n- item",
            "source.diff" to "--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y",
        )
        assertEquals("样例应与语言清单一一对应", grammars.size, samples.size)

        samples.forEach { (scope, text) ->
            val grammar = GrammarRegistry.getInstance().findGrammar(scope)
            assertNotNull("$scope 未注册", grammar)
            val result = grammar!!.tokenizeLine(text)
            assertTrue("$scope 分词结果为空", result.tokens.isNotEmpty())
        }
    }
}
