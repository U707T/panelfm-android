package com.u707t.panelfm.core.common

/**
 * 「渲染预览」支持矩阵（Markdown / CSV）。
 *
 * 与 Office 预览共用同一套 WebView 页面（见 `docs/OFFICE-PREVIEW.md`），
 * 只是 kind = `markdown` / `csv`：
 *  - `.md` / `.markdown` → marked（MIT）+ DOMPurify（Apache-2.0 / MPL-2.0）渲染与清洗；
 *  - `.csv` → SheetJS（已在 vendor 里）按表格渲染。
 *
 * 注意：**默认打开方式不变**（文本查看器）——渲染预览是预览页菜单 / 「打开方式…」里的可选项，
 * 用户也可以把它设成 `.md` 的默认打开方式。
 */
object RenderFormats {

    const val KIND_MARKDOWN = "markdown"
    const val KIND_CSV = "csv"

    /** 可渲染预览的后缀 */
    val EXTENSIONS = setOf("md", "markdown", "csv")

    fun isRenderable(extension: String): Boolean = extension.lowercase() in EXTENSIONS

    /** 后缀 → 渲染器种类；空串 = 不可渲染 */
    fun viewerKindOf(extension: String): String = when (extension.lowercase()) {
        "md", "markdown" -> KIND_MARKDOWN
        "csv" -> KIND_CSV
        else -> ""
    }
}
