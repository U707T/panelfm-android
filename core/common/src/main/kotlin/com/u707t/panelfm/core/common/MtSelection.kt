package com.u707t.panelfm.core.common

/**
 * MT 选择（多选）的**纯状态模型**：把「哪些项被选中 / 滑动锚点在哪」这些语义从 Compose
 * 与控制器里抽出来，做成不依赖界面、可在 JVM 上单测的一小块。
 *
 * ## 复刻的 MT 行为（用户实机确认，见 `docs/SELECTION-MODEL.md`）
 *
 * | 操作 | 行为 |
 * |---|---|
 * | 长按一项 | **直接弹该项的二级菜单**（不改选择、不进多选） |
 * | 左/右滑动一项 | 震动 + 动效 → 进入多选并选中该行（[swipe]：无锚点时只选它） |
 * | 滑动一项后再滑动另一项 | 两项之间的**闭区间全部选中**（[swipe]：有锚点时连成区间） |
 * | 滑动一项后**点击**另一项 | 只是加选这两项（不连区间；点击会清掉滑动锚点） |
 * | 多选态点击 | 加/减选（[toggle]） |
 * | 底栏 | 全选 / 反选 / 类选（[all] / [invert]） |
 * | 「点击连选」设置开启后点击两项 | 区间（[unionRange]，控制器侧配 `tapAnchor`） |
 *
 * ⚠️ 早期实现把 `0x7f1106f3`「左右滑动文件可直接选择」做成了「按住一路刷的跟手扫选」，
 * 用户实机确认那是误触来源、MT 不是这样；`0x7f110697`「右滑列表项可进行更多操作」与
 * `0x7f110631`「可通过分别长按两个项目来进行连选」也都不在文件列表上（后者与
 * `0x7f11062e`「可通过分别长按两个类来进行连选」是同一套、属于按类分组的列表）。
 *
 * 所有键（key）都是**稳定标识**（列表项的 uri 字符串），不用下标 —— 下标会随排序 /
 * 刷新 / 增删漂移，锚点一漂就得靠 `indexOf` 兜底。
 */
object MtSelection {

    /** 切换单项（多选态单击） */
    fun toggle(selection: Set<String>, key: String): Set<String> =
        if (selection.contains(key)) selection - key else selection + key

    /** 全部键（「全选」） */
    fun all(keys: Collection<String>): Set<String> = keys.toSet()

    /** 反选（MT `0x7f110632`）：注意结果是相对**当前列表**的，已不在列表里的游离键会被清掉 */
    fun invert(selection: Set<String>, keys: Collection<String>): Set<String> =
        keys.filterNot { selection.contains(it) }.toSet()

    /**
     * 闭区间 [from, to] 的键；任一端点已不在列表里（刷新 / 换目录后锚点失效）返回 null。
     * 端点相同时返回单元素集合（点两下同一项 = 只选它，不会误扩成整段）。
     */
    fun rangeKeys(keys: List<String>, from: String, to: String): Set<String>? {
        val a = keys.indexOf(from)
        val b = keys.indexOf(to)
        if (a < 0 || b < 0) return null
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return keys.subList(lo, hi + 1).toSet()
    }

    /**
     * 区间**追加**到现有选择（点击连选 / 滑动连选）。
     * 端点失效时原样返回（宁可不选，也不要在错误的区间上乱选）。
     */
    fun unionRange(selection: Set<String>, keys: List<String>, from: String, to: String): Set<String> =
        rangeKeys(keys, from, to)?.let { selection + it } ?: selection

    /**
     * **滑动选中**（MT `0x7f1106f3` 左右滑动文件可直接选择 / `0x7f11062f` 滑动选择任意两个文件）：
     *
     *  - 没有滑动锚点（或锚点就是这一行）：只选它，锚点 = 它；
     *  - 有锚点且是另一行：锚点 ↔ 这一行的闭区间**追加**进选择，锚点挪到这一行
     *    （第三次滑动可以继续延伸）。
     *
     * 返回 (新选择, 新锚点)。锚点失效（列表刷新过）时退化为「只选这一行」。
     */
    fun swipe(
        selection: Set<String>,
        keys: List<String>,
        key: String,
        anchor: String?,
    ): Pair<Set<String>, String> {
        val range = if (anchor != null && anchor != key) rangeKeys(keys, anchor, key) else null
        val next = if (range == null) selection + key else selection + range
        return next to key
    }
}
