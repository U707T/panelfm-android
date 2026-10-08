package com.u707t.panelfm.core.common

/**
 * Office 文档（只读预览）支持矩阵。
 *
 * 预览引擎是「WebView + 前端渲染库」三件套（见 `docs/OFFICE-PREVIEW.md`）：
 *  - `.docx`（含 `.docm` / `.dotx` / `.dotm`：同一 OOXML 包，宏不会被执行）→ docx-preview（Apache-2.0）
 *  - `.xls` / `.xlsx`（含 `.xlsm` / `.xltx` / `.xltm`）→ SheetJS CE（Apache-2.0，含旧 BIFF 格式）
 *  - `.ods` / `.fods` → 同走 SheetJS（OpenDocument 表格，实测 bundled 0.18.5 可直接解析）
 *  - `.pptx`（含 `.pptm` / `.ppsx` / `.potx` / `.potm`）→ @aiden0z/pptx-renderer（Apache-2.0）
 *
 * **不支持的**：`.doc` / `.ppt` 是旧二进制格式，前端生态没有可用的渲染器 ——
 * 仍给「文档预览」入口，但页内直接给一段说明，引导用「打开方式…」交给 WPS / Office。
 */
object OfficeFormats {

    /** 预览器种类（与 `assets/office/viewer.js` 的 `kind` 参数一一对应） */
    const val KIND_DOCX = "docx"
    const val KIND_XLSX = "xlsx"
    const val KIND_PPTX = "pptx"

    /** 走「文档预览」入口的后缀（与 [MimeTypes.Kind.DOCUMENT] 的集合一致） */
    val EXTENSIONS = setOf(
        "doc", "docx", "docm", "dotx", "dotm",
        "xls", "xlsx", "xlsm", "xltx", "xltm", "ods", "fods",
        "ppt", "pptx", "pptm", "ppsx", "potx", "potm",
    )

    fun isOffice(extension: String): Boolean = extension.lowercase() in EXTENSIONS

    /**
     * 后缀 → 预览器种类；**空串 = 该后缀可以进文档预览，但格式不支持渲染**（.doc / .ppt）。
     */
    fun viewerKindOf(extension: String): String = when (extension.lowercase()) {
        "docx", "docm", "dotx", "dotm" -> KIND_DOCX
        "xls", "xlsx", "xlsm", "xltx", "xltm", "ods", "fods" -> KIND_XLSX
        "pptx", "pptm", "ppsx", "potx", "potm" -> KIND_PPTX
        else -> ""
    }

    /** 不支持的旧格式（.doc / .ppt）给用户看的说明。 */
    fun unsupportedMessage(extension: String): String = when (extension.lowercase()) {
        "doc" -> "旧版 .doc（二进制）格式暂不支持内置预览。\n\n请用「打开方式…」交给 WPS / Office 等应用查看。"
        "ppt" -> "旧版 .ppt（二进制）格式暂不支持内置预览。\n\n请用「打开方式…」交给 WPS / Office 等应用查看。"
        else -> "该格式暂不支持内置预览。\n\n请用「打开方式…」交给其它应用查看。"
    }
}
