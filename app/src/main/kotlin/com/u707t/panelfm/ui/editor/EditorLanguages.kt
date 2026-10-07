package com.u707t.panelfm.ui.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sora-editor 的语法 / 主题注册（进程级单例，首次用编辑器时后台加载一次）。
 *
 * - 语法：`assets/textmate/languages.json` 登记 12 种语言（TextMate 语法，来源见
 *   `third_party/THIRD-PARTY-NOTICES.md`）；
 * - 主题：只挑两套（浅色 `quietlight` / 深色 `darcula`），底色在创建配色时对齐应用背景，
 *   避免「编辑器一块底色、应用另一块底色」；
 * - 未登记的后缀返回 null → 纯文本（sora 用 null 语言 = 不高亮）。
 *
 * ⚠️ sora-editor 为 LGPL-2.1：`third_party/sora-editor/LICENSE-LGPL-2.1.txt`。
 */
internal object EditorLanguages {

    private const val THEME_LIGHT = "quietlight"
    private const val THEME_DARK = "darcula"

    private val initialized = AtomicBoolean(false)

    /** 首次调用做全量加载；之后是空操作。请在 IO 线程调用（读资源 + 编译语法）。 */
    fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val registry = ThemeRegistry.getInstance()
        val providers = FileProviderRegistry.getInstance()
        providers.addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        listOf(THEME_LIGHT, THEME_DARK).forEach { name ->
            val path = "textmate/$name.json"
            registry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(providers.tryGetInputStream(path), path, null),
                    name,
                ).apply { isDark = name == THEME_DARK },
            )
        }
        registry.setTheme(THEME_LIGHT)
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    /** 文件名 → TextMate scope；未登记（或纯文本）返回 null。 */
    fun scopeOf(fileName: String): String? {
        val name = fileName.substringAfterLast('/').lowercase()
        return when (name.substringAfterLast('.', "")) {
            "kt", "kts" -> "source.kotlin"
            "java" -> "source.java"
            "js", "mjs", "cjs", "jsx" -> "source.js"
            "ts", "tsx", "mts", "cts" -> "source.ts"
            "json" -> "source.json"
            "xml", "svg", "plist", "xsd", "xsl" -> "text.xml"
            "html", "htm", "xhtml" -> "text.html.basic"
            "py", "pyw" -> "source.python"
            "sh", "bash", "zsh", "ksh", "env" -> "source.shell"
            "yml", "yaml" -> "source.yaml"
            "md", "markdown" -> "text.html.markdown"
            "css", "scss", "less" -> "source.css"
            else -> null
        }
    }

    /** 给编辑器设置语言（[scope] 为 null 或库未加载好时退化为纯文本）。 */
    fun applyLanguage(editor: CodeEditor, scope: String?) {
        val language = scope?.let { runCatching { TextMateLanguage.create(it, true) }.getOrNull() }
        editor.setEditorLanguage(language)
    }

    /** 状态栏显示用的语言名（未登记的后缀返回 null）。 */
    fun labelOf(scope: String?): String? = scope?.let { LABELS[it] }

    private val ORDER = listOf(
        "source.kotlin", "source.java", "source.js", "source.ts",
        "source.json", "text.xml", "text.html.basic", "source.css",
        "source.python", "source.shell", "source.yaml", "text.html.markdown",
    )

    private val LABELS = mapOf(
        "source.kotlin" to "Kotlin",
        "source.java" to "Java",
        "source.js" to "JavaScript",
        "source.ts" to "TypeScript",
        "source.json" to "JSON",
        "text.xml" to "XML",
        "text.html.basic" to "HTML",
        "source.python" to "Python",
        "source.shell" to "Shell",
        "source.yaml" to "YAML",
        "text.html.markdown" to "Markdown",
        "source.css" to "CSS",
    )

    /**
     * 「语法」选择菜单的全部条目（scope → 显示名）：
     * 空串 = 纯文本；其余为 TextMate scope（顺序固定，便于用户找）。
     */
    val selectable: List<Pair<String, String>> = listOf("" to "纯文本") + ORDER.map { it to (LABELS[it] ?: it) }

    /**
     * 创建配色：以主题为底，把背景 / 行号栏 / 当前行对齐应用配色
     * （sora 的主题背景与应用底色不同，不覆盖会出现「补丁块」）。
     */
    fun createColorScheme(
        dark: Boolean,
        background: Int,
        gutterText: Int,
        currentLine: Int,
    ): TextMateColorScheme {
        val registry = ThemeRegistry.getInstance()
        registry.setTheme(if (dark) THEME_DARK else THEME_LIGHT)
        return TextMateColorScheme.create(registry).apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, background)
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, background)
            setColor(EditorColorScheme.LINE_NUMBER, gutterText)
            setColor(EditorColorScheme.CURRENT_LINE, currentLine)
        }
    }
}

/**
 * 长行文本（Markdown / 纯文本 / 未识别）默认自动换行；代码默认关。
 */
internal fun defaultWordwrap(scope: String?): Boolean = scope == null || scope == "text.html.markdown"

/**
 * 按 scope（与扩展名）给行注释前缀；不支持的语言返回 null（菜单会置灰）。
 *
 * CSS 只有块注释，`//` 是 scss / less 的语法——所以 css 系用扩展名区分：
 * 纯 css 返回 null，scss / less 返回 `//`（2026-10-08 重审 §3 · 🔵7）。
 */
internal fun commentPrefixOf(scope: String?, ext: String = ""): String? = when (scope) {
    "source.kotlin", "source.java", "source.js", "source.ts" -> "//"
    "source.css" -> if (ext == "scss" || ext == "less") "//" else null
    "source.python", "source.shell", "source.yaml" -> "#"
    else -> null
}
