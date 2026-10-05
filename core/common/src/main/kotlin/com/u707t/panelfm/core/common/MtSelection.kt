package com.u707t.panelfm.core.common

/**
 * MT 选择（多选）的**纯状态模型**：把「哪些项被选中 / 锚点在哪 / 正在扫过哪一段」这些
 * 语义从 Compose 与控制器里抽出来，做成不依赖界面、可在 JVM 上单测的一小块。
 *
 * ## 为什么要有它
 *
 * 旧实现的滑动选择把三件事混在了一起：
 *  1. 手势层自己用一个 `var swipeAnchor` 记锚点（**局部变量**：重组、转屏、切目录都会丢）；
 *  2. 控制器用 `longPressAnchor` / `tapAnchor` 再记两个锚点（**全局字段**：左右窗格互相串味）；
 *  3. 「区间选择」是**替换语义**（`selection = 区间`）——从已选 5 项的状态下滑两行，
 *     之前选的 5 项会被直接抹掉（MT 是「追加」：扫过去的那一段加进来）。
 *
 * 现在语义只在这里定义一次：所有键（key）都是**稳定标识**（列表项的 uri 字符串），
 * 不用下标 —— 下标会随排序 / 刷新 / 增删漂移，锚点一漂就得靠 `indexOf` 兜底。
 *
 * ## 复刻的 MT 行为（依据见 `/workspace/mt-analysis` §7.1）
 *
 * | 文案 | 行为 |
 * |---|---|
 * | `0x7f1106f3` 左右滑动文件可直接选择 | [beginSweep]：滑动即进入多选，并按下滑那一行 |
 * | `0x7f11062f` 滑动选择列表中任意两个文件，将会自动选择它们中间所有的文件 | [swept]：锚点 ↔ 当前行的闭区间 |
 * | `0x7f110630` 开启后点击列表中任意两个项…（点击连选） | [unionRange]：点击两端的闭区间 |
 * | `0x7f110631` 可通过分别长按两个项目来进行连选 | 同上，锚点由长按设置（控制器侧） |
 * | `0x7f11062b/632` 全选 / 反选 | [invert]（全选就是 [all]） |
 *
 * ## 扫选的「跟手」语义（本次重构的重点）
 *
 * 一次扫选会话 = [SelectionSweep]：
 *  - `anchorKey`：**按下那一行**（不是跨过阈值的那一刻手指所在的行 —— 手指会飘）；
 *  - `base`：手指按下时**已有的选择**（本次扫选是追加，不推翻用户之前的选择）；
 *  - 当前选择 = `base ∪ [anchorKey .. currentKey]`。
 *
 * 于是手指往回滑时区间会**收回去**（跟手：划过 = 选中，退回 = 取消），
 * 而不是像旧实现那样只增不减。
 */
object MtSelection {

    /** 一次扫选会话（手指按住期间有效；手指抬起后由控制器丢弃） */
    data class Sweep(
        /** 锚点：本次滑动**按下**的那一行 */
        val anchorKey: String,
        /** 手指按下时已有的选择（扫选结束也保留） */
        val base: Set<String> = emptySet(),
    )

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
     * 区间**追加**到现有选择（点击连选 / 长按连选）。
     * 端点失效时原样返回（宁可不选，也不要在错误的区间上乱选）。
     */
    fun unionRange(selection: Set<String>, keys: List<String>, from: String, to: String): Set<String> =
        rangeKeys(keys, from, to)?.let { selection + it } ?: selection

    /** 开始一次扫选：按下那一行立刻选中，锚点 = 该行，`base` = 按下时已有的选择 */
    fun beginSweep(selection: Set<String>, anchorKey: String): Pair<Set<String>, Sweep> =
        (selection + anchorKey) to Sweep(anchorKey = anchorKey, base = selection)

    /**
     * 扫选跟手：当前手指（或自动滚动）落在 [currentKey] 这一行时的选择集。
     *
     * 返回 null 表示**这一行已经不在列表里**（例如扫选途中目录被刷新）——
     * 调用方应保持原选择不动，等手指抬起了结这次会话。
     */
    fun swept(keys: List<String>, sweep: Sweep, currentKey: String): Set<String>? {
        val range = rangeKeys(keys, sweep.anchorKey, currentKey) ?: return null
        return sweep.base + range
    }
}
