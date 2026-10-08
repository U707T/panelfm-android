package com.u707t.panelfm.ui.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 语法资产一致性（JVM 测试直接读 `src/main/assets/textmate` 目录）：
 *  - `languages.json` 的每个条目：语法文件与语言配置都存在、JSON 合法（配置必须是**严格 JSON**）、
 *    scopeName 与语法文件声明的一致；
 *  - 「语法」菜单（[EditorLanguages.selectable]）与清单**完全一致** —— 新增语言只加资产不加菜单、
 *    或只加菜单不加资产都会在这里失败。
 *
 * 为什么需要它：这类资产出错既不会编译失败，也不会被别的测试发现，只会在真机上
 * 「高亮悄悄没了」；上一轮新增 10 种语言正是靠这个测试兜底（含 VS Code 上游的
 * 注释 / 尾逗号清洗检查）。
 */
class TextMateAssetsTest {

    private val assetsDir: File = listOf(
        File("src/main/assets/textmate"),
        File("app/src/main/assets/textmate"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到 textmate 资产目录（单测工作目录应为 app/，实际：${File(".").absolutePath}）")

    /** languages.json 里的路径相对 **assets 根**（如 `textmate/kotlin/...`），不是相对 textmate/ */
    private val assetsRoot: File = assetsDir.parentFile

    private fun languages(): List<JSONObject> {
        val root = JSONObject(assetsDir.resolve("languages.json").readText())
        val array = root.getJSONArray("languages")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `languages json 与实际资产一致`() {
        val langs = languages()
        assertTrue("语言数量不应少于 20（当前 ${langs.size}）", langs.size >= 20)
        val names = mutableSetOf<String>()
        val scopes = mutableSetOf<String>()
        langs.forEach { lang ->
            val name = lang.getString("name")
            val scope = lang.getString("scopeName")
            assertTrue("语言名重复：$name", names.add(name))
            assertTrue("scopeName 重复：$scope", scopes.add(scope))

            val grammar = assetsRoot.resolve(lang.getString("grammar"))
            assertTrue("语法文件缺失或为空：$grammar", grammar.isFile && grammar.length() > 0)
            if (grammar.name.endsWith(".json")) {
                val parsed = JSONObject(grammar.readText())
                assertEquals("scopeName 与清单不一致：$name", scope, parsed.getString("scopeName"))
            } else {
                // plist 语法（如 Kotlin）：不是 JSON，只校验声明了同一个 scope
                assertTrue("plist 语法里找不到 $scope：$grammar", grammar.readText().contains(scope))
            }

            val config = assetsRoot.resolve(lang.getString("languageConfiguration"))
            assertTrue("语言配置缺失：$config", config.isFile && config.length() > 0)
            // 严格 JSON：上游 VS Code 文件常带注释 / 尾逗号，清洗后必须能被解析
            JSONObject(config.readText())
        }
    }

    @Test
    fun `语法选择菜单与清单一致`() {
        val scopes = languages().map { it.getString("scopeName") }.toSet()
        val selectable = EditorLanguages.selectable.map { it.first }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            "「语法」菜单应与 languages.json 完全一致（新增语言别忘登记 ORDER / LABELS）",
            scopes,
            selectable,
        )
    }
}
