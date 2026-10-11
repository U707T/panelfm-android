package com.u707t.panelfm.ui.editor

/*
 * 符号输入栏的符号表（NP `menu_symbol_input` / `symbol_input_view`，sora 的
 * `SymbolInputView` 只负责渲染与插入，符号集由应用给）。
 *
 * 选词标准：手机输入法里**难打**但写文本 / 代码常用的符号。Tab 的特殊性见下：
 * sora 的 `SymbolInputView` 对 `"\t"` 有特判（走编辑器自己的缩进 / 代码片段跳位），
 * 所以这里保留 `⇥ to "\t"` 这一项，插入行为与键盘 Tab 键一致。
 */

/** 显示文本 to 插入文本（顺序 = 栏内顺序；常用符号靠前）。 */
internal val EDITOR_SYMBOLS: List<Pair<String, String>> = listOf(
    "⇥" to "\t",
    "{" to "{",
    "}" to "}",
    "(" to "(",
    ")" to ")",
    "[" to "[",
    "]" to "]",
    "<" to "<",
    ">" to ">",
    "\"" to "\"",
    "'" to "'",
    "=" to "=",
    "+" to "+",
    "-" to "-",
    "*" to "*",
    "/" to "/",
    "\\" to "\\",
    "|" to "|",
    "&" to "&",
    "!" to "!",
    "?" to "?",
    "#" to "#",
    "@" to "@",
    "$" to "$",
    "%" to "%",
    "^" to "^",
    "~" to "~",
    "_" to "_",
    ";" to ";",
    ":" to ":",
    "," to ",",
    "." to ".",
)

/** 展示文本数组（sora `SymbolInputView.addSymbols` 的第一个参数）。 */
internal val EDITOR_SYMBOL_LABELS: Array<String> = EDITOR_SYMBOLS.map { it.first }.toTypedArray()

/** 插入文本数组（sora `SymbolInputView.addSymbols` 的第二个参数）。 */
internal val EDITOR_SYMBOL_VALUES: Array<String> = EDITOR_SYMBOLS.map { it.second }.toTypedArray()
