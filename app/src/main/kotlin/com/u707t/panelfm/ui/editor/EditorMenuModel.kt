package com.u707t.panelfm.ui.editor

/*
 * 编辑器 ⋮ 菜单的**纯模型**（v2.0.15 · 对齐 NP管理器 `edit_menu.xml` 的分组）。
 *
 * 为什么把菜单挪出 UI：菜单项「该不该出现 / 该不该可点」的规则越加越多
 * （语言是否支持注释 / 格式化 / 校验、若干视图开关的勾选态、流畅模式的自动档…），
 * 写在 Composable 里就没法回归测试。这里只产出「分组 + 条目 + 状态」，
 * 交互（点谁、开关什么）由 `EditorMenu.kt` 负责，点下去干什么由 `EditorScreen` 负责。
 */

/** 字体大小档位（NP `font_size`；默认 [DEFAULT_FONT_SIZE] = 旧版顶栏 A- / A+ 的初值）。 */
internal val FONT_SIZES: List<Int> = listOf(10, 12, 14, 16, 18, 20, 24, 28)
internal const val DEFAULT_FONT_SIZE = 14

/** 菜单动作（= 一次点击的语义；UI 侧映射到具体回调）。 */
internal enum class EditorMenuAction {
    SAVE, RELOAD, EXIT,
    UNDO, REDO,
    COPY_LINE, CUT_LINE, DELETE_LINE, CLEAR_LINE, DUPLICATE_LINE,
    TO_UPPER, TO_LOWER, INDENT, UNINDENT, TOGGLE_COMMENT,
    FIND, REPLACE,

    /** NP `menu_edit_goto_line` / `jump2header` / `jump2end` */
    GOTO_LINE, GOTO_LINE_START, GOTO_LINE_END, GOTO_FILE_START, GOTO_FILE_END,

    /** NP `menu_language` / `menu_colors_style` / `menu_text_wordwrap` / `menu_fluent_mode` */
    LANGUAGE, THEME, WORDWRAP, FLUENT_MODE, FLUENT_AUTO,

    /** NP `menu_file_charset` → `load_reset_charset` / `save_reset_charset` */
    CHARSET_RELOAD, CHARSET_SAVE,

    /** NP「设置」子菜单：`font_size` / `blank_symbol` / `font_monospace` / `symbol_input` / `reference_line` / `magnifier` */
    FONT_SIZE, WHITESPACE, MONOSPACE, SYMBOL_BAR, INDENT_GUIDE, MAGNIFIER,

    /** 代码整理：格式化 / 压缩（去空白）/ 校验语法（NP `menu_xml_valid_check`） */
    FORMAT, COMPRESS, VALIDATE,
}

internal data class EditorMenuEntry(
    val action: EditorMenuAction,
    val label: String,
    val enabled: Boolean = true,
    /** `null` = 不是勾选项 */
    val checked: Boolean? = null,
)

internal data class EditorMenuSection(
    val title: String,
    val entries: List<EditorMenuEntry>,
    /**
     * true = 二级菜单：一级只显示一行「标题 ▸」，点进去换成子菜单内容
     * （NP 的「文件编码」「设置」都是子菜单；跳转项在 NP 里是一级平铺，这里同）。
     */
    val submenu: Boolean = false,
)

/** 菜单需要的全部输入（都来自 `EditorScreen` 的状态 / 设置）。 */
internal data class EditorMenuInput(
    val canFormat: Boolean,
    val canValidate: Boolean,
    val canToggleComment: Boolean,
    val wordwrap: Boolean,
    /** 当前**生效**的流畅模式（含「按文件大小自动」的结论） */
    val fluentMode: Boolean,
    /** 流畅模式是否是用户显式选择（false = 处于「自动」档，菜单多给一行「恢复自动」） */
    val fluentManual: Boolean,
    val showWhitespace: Boolean,
    val monospace: Boolean,
    val symbolBar: Boolean,
    val indentGuide: Boolean,
    val magnifier: Boolean,
    val fontSize: Int,
    val languageLabel: String,
    val languageManual: Boolean,
    val themeLabel: String,
)

private fun entry(
    action: EditorMenuAction,
    label: String,
    enabled: Boolean = true,
    checked: Boolean? = null,
) = EditorMenuEntry(action, label, enabled, checked)

/**
 * 编辑器菜单全量（v2.0.18 起「**常用项置顶 + 其余全部收起为二级菜单**」）：
 *
 * ```
 * 查找 / 替换 / 保存 / 重新加载      ← 最常用，一级平铺
 * 编辑 ▶  跳转 ▶  视图 ▶  编码 ▶  设置 ▶  代码整理 ▶
 * 退出编辑器
 * ```
 *
 * 为什么收起：「编辑」12 项 + 「跳转」5 项 + 「视图」4 项 + … 一级铺开有近 40 行，
 * 手机上要滚半天才找得到「退出」；频率低的组收进二级菜单（NP 的「编码 / 设置」本来就是这样）。
 * 撤销 / 重做另外还在顶栏有常驻图标（这里保留在「编辑 ▶」里，按 MT 的口径）。
 */
internal fun editorMenuSections(input: EditorMenuInput): List<EditorMenuSection> {
    return listOf(
        // ---- 常用：平铺在最上面（查找 / 替换是编辑文本时用得最多的一组）
        EditorMenuSection(
            title = "",
            entries = listOf(
                entry(EditorMenuAction.FIND, "查找"),
                entry(EditorMenuAction.REPLACE, "查找 / 替换"),
                entry(EditorMenuAction.SAVE, "保存"),
                entry(EditorMenuAction.RELOAD, "重新加载"),
            ),
        ),
        // ---- 以下全部是二级菜单（默认收起）
        EditorMenuSection(
            title = "编辑",
            submenu = true,
            entries = listOf(
                entry(EditorMenuAction.UNDO, "撤销"),
                entry(EditorMenuAction.REDO, "重做"),
                entry(EditorMenuAction.COPY_LINE, "复制行"),
                entry(EditorMenuAction.CUT_LINE, "剪切行"),
                entry(EditorMenuAction.DELETE_LINE, "删除行"),
                entry(EditorMenuAction.CLEAR_LINE, "清空行"),
                entry(EditorMenuAction.DUPLICATE_LINE, "重复行"),
                entry(EditorMenuAction.TO_UPPER, "转为大写"),
                entry(EditorMenuAction.TO_LOWER, "转为小写"),
                entry(EditorMenuAction.INDENT, "增加缩进"),
                entry(EditorMenuAction.UNINDENT, "减小缩进"),
                entry(
                    EditorMenuAction.TOGGLE_COMMENT,
                    "切换注释" + if (input.canToggleComment) "" else "（当前语言不支持）",
                    enabled = input.canToggleComment,
                ),
            ),
        ),
        EditorMenuSection(
            title = "跳转",
            submenu = true,
            entries = listOf(
                entry(EditorMenuAction.GOTO_LINE, "跳转至指定行…"),
                entry(EditorMenuAction.GOTO_LINE_START, "跳转至行首"),
                entry(EditorMenuAction.GOTO_LINE_END, "跳转至行尾"),
                entry(EditorMenuAction.GOTO_FILE_START, "跳转至文件开头"),
                entry(EditorMenuAction.GOTO_FILE_END, "跳转至文件结尾"),
            ),
        ),
        EditorMenuSection(
            title = "视图",
            submenu = true,
            entries = buildList {
                add(
                    entry(
                        EditorMenuAction.LANGUAGE,
                        "语法：${input.languageLabel}（${if (input.languageManual) "手动" else "自动"}）",
                    ),
                )
                add(entry(EditorMenuAction.THEME, "配色风格：${input.themeLabel}"))
                add(entry(EditorMenuAction.WORDWRAP, "自动换行", checked = input.wordwrap))
                add(
                    entry(
                        EditorMenuAction.FLUENT_MODE,
                        "流畅模式" +
                            when {
                                input.fluentMode -> "（已关闭语法高亮）"
                                else -> ""
                            },
                        checked = input.fluentMode,
                    ),
                )
                // 用户显式选过之后，给一条回到「按文件大小自动」的路（默认档就是自动）
                if (input.fluentManual) {
                    add(entry(EditorMenuAction.FLUENT_AUTO, "流畅模式：恢复自动（按文件大小）"))
                }
            },
        ),
        EditorMenuSection(
            title = "编码",
            submenu = true,
            entries = listOf(
                entry(EditorMenuAction.CHARSET_RELOAD, "指定编码重新加载…"),
                entry(EditorMenuAction.CHARSET_SAVE, "指定保存的编码…"),
            ),
        ),
        EditorMenuSection(
            title = "设置",
            submenu = true,
            entries = listOf(
                entry(EditorMenuAction.FONT_SIZE, "字体大小：${input.fontSize}"),
                entry(EditorMenuAction.WHITESPACE, "显示空白符号", checked = input.showWhitespace),
                entry(EditorMenuAction.MONOSPACE, "等宽字体", checked = input.monospace),
                entry(EditorMenuAction.SYMBOL_BAR, "符号输入栏", checked = input.symbolBar),
                entry(EditorMenuAction.INDENT_GUIDE, "缩进参考线", checked = input.indentGuide),
                entry(EditorMenuAction.MAGNIFIER, "放大镜", checked = input.magnifier),
            ),
        ),
        EditorMenuSection(
            title = "代码整理",
            submenu = true,
            entries = listOf(
                entry(EditorMenuAction.FORMAT, "格式化代码", enabled = input.canFormat),
                entry(EditorMenuAction.COMPRESS, "压缩代码（去空白）"),
                entry(
                    EditorMenuAction.VALIDATE,
                    "校验语法",
                    enabled = input.canValidate,
                ),
            ),
        ),
        EditorMenuSection(
            title = "",
            entries = listOf(entry(EditorMenuAction.EXIT, "退出编辑器")),
        ),
    )
}
