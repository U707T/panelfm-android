package com.u707t.panelfm.core.common

/**
 * 文本对比的「忽略」选项（复刻 MT 对比器菜单 `0x7f0e000a`，文案 `0x7f110144/145/142/143`）：
 *
 *  不忽略 · 忽略首尾空格 · 忽略全部空格 · 忽略空格和空行
 *
 * 另有「区分大小写」（MT `0x7f1103bc`）与「浏览模式」（自动切换 / 双列 / 单列，`0x7f1101fb/1fc/1fd/1fe`）。
 *
 * 归一化只作用于**比较键**，展示的仍是原文（与 MT 一致：忽略空格后行内容照原样显示）。
 */
enum class DiffIgnore(val label: String) {
    NONE("不忽略"),
    TRIM("忽略首尾空格"),
    ALL_SPACES("忽略全部空格"),
    SPACES_AND_BLANK("忽略空格和空行"),
    ;

    /** 把一行归一化成用于比较的键 */
    fun key(line: String, caseSensitive: Boolean): String {
        val normalized = when (this) {
            NONE -> line
            TRIM -> line.trim()
            ALL_SPACES -> line.filterNot { it == ' ' || it == '\t' }
            SPACES_AND_BLANK -> line.filterNot { it == ' ' || it == '\t' }
        }
        return if (caseSensitive) normalized else normalized.lowercase()
    }

    /** 该档位下是否要把空行整体丢掉（只有「忽略空格和空行」会） */
    val dropsBlankLines: Boolean get() = this == SPACES_AND_BLANK
}

/** 对比器的浏览模式（MT：自动切换 / 双列 / 单列） */
enum class DiffViewMode(val label: String) {
    AUTO("自动切换"),
    SIDE_BY_SIDE("双列"),
    UNIFIED("单列"),
    ;

    companion object {
        /** 自动切换：窄屏单列、宽屏双列（MT 的「自动切换」语义） */
        fun autoFor(wideEnough: Boolean): DiffViewMode = if (wideEnough) SIDE_BY_SIDE else UNIFIED
    }
}
