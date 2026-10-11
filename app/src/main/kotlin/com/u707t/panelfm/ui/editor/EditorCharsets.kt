package com.u707t.panelfm.ui.editor

/*
 * 编辑器「文件编码」两个对话框的选项表（NP `menu_load_reset_charset` / `menu_save_reset_charset`）。
 *
 * 这张表必须与 `core/common` 的 `TextEncodings` 一一对应：**用户能选到的编码 =
 * 我们真的能编码写回的编码**（旧实现里 ISO-8859-1 会静默转存 UTF-8，就是两张表漂移的结果）。
 * `EditorCharsetsTest` 逐项断言「选择表里的每个编码都能被 TextEncodings 原样吃下」。
 */
internal object EditorCharsets {

    /** 「指定编码重新加载」的首项：按 BOM / 启发式自动识别（= 普通重新加载的编码行为）。 */
    const val AUTO = ""

    /**
     * 全部可选编码（顺序 = 对话框顺序；与 `TextEncodings.decodeWith` / `encode` 的
     * 分支逐字对应）。UTF-16 / UTF-32 带 BOM 才会被识别，写回时由 `encode` 重建。
     */
    val ALL: List<String> = listOf(
        "UTF-8",
        "UTF-8 (BOM)",
        "UTF-16LE",
        "UTF-16BE",
        "UTF-32LE",
        "UTF-32BE",
        "GBK",
        "Big5",
        "ISO-8859-1",
    )

    /** 「指定编码重新加载」的项：[AUTO] 在前，其余同 [ALL]。 */
    fun reloadOptions(): List<Pair<String, String>> =
        listOf(AUTO to "自动识别") + ALL.map { it to label(it) }

    /** 「指定保存的编码」的项（没有「自动」：写回必须给一个明确编码）。 */
    fun saveOptions(): List<Pair<String, String>> = ALL.map { it to label(it) }

    /** 对话框里的显示名（编码名与 Android 的 `Charset` 名不完全一样，这里补上注解）。 */
    fun label(charset: String): String = when (charset) {
        "UTF-8 (BOM)" -> "UTF-8（带 BOM）"
        "ISO-8859-1" -> "ISO-8859-1（Latin-1）"
        AUTO -> "自动识别"
        else -> charset
    }
}
