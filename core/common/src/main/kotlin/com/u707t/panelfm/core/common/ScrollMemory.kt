package com.u707t.panelfm.core.common

/**
 * 目录滚动位置的记忆（复刻 MT 的「进子目录再返回，列表停在原地」）。
 *
 * ## 为什么需要它
 *
 * 原先每个窗格只有**一个** `LazyListState`，进子目录时列表内容整体换掉、返回上级时又换回来：
 * Compose 的 `LazyListState` 只能记住「**当前这一份**内容」的滚动位置，所以返回时位置已经丢了，
 * 表现就是「跳回顶部重新加载」。
 *
 * ## MT 的行为
 *
 * MT 的每个目录都记住自己上次的滚动位置（含返回上级时**刚刚离开的那个子目录**也要记住，
 * 下次再进去还在原地）。实现上就是一张 `路径 → 位置` 的表。
 *
 * ## 设计要点
 *
 * 1. **纯逻辑 / 无副作用**：便于单测，也便于 UI 侧按需取用。
 * 2. **键用「去连接参数的 uri 字符串」**（`VfsUris.stripped(uri).toString()`）：
 *    同一路径在不同连接下的位置天然隔离，同时避免 query 里的临时参数造成 key 抖动。
 * 3. **有上限**：只保留最近 [MAX_ENTRIES] 个目录，避免长时间浏览后无限增长。
 * 4. **只有「真的滚动过」才记**（`index > 0 || offset > 0`）：停在顶部的目录不占名额，
 *    也让「返回上级后列表本来就在顶部」的情况不产生多余条目。
 */
class ScrollMemory(private val maxEntries: Int = MAX_ENTRIES) {

    /** 路径 → (首个可见项下标, 该项的像素偏移)；LinkedHashMap 维持「最久未用在前」 */
    private val entries = LinkedHashMap<String, Entry>()

    /** 一条滚动位置 */
    data class Entry(val index: Int, val offset: Int)

    /**
     * 记下某个路径的滚动位置。
     *
     * @param index 首个可见项下标（**含 `..` 行的列表下标**，由调用方用
     *              [toListIndex] 换算，保证两侧一致）
     * @param offset 该项的像素偏移
     */
    fun remember(key: String, index: Int, offset: Int) {
        if (key.isEmpty()) return
        if (index <= 0 && offset <= 0) {
            // 在顶部 → 不需要记忆（返回时本来就是顶部，等价）
            entries.remove(key)
            return
        }
        // 重新插入以刷新 LRU 顺序
        entries.remove(key)
        entries[key] = Entry(index.coerceAtLeast(0), offset)
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** 取某个路径上次的滚动位置；没有则 null（= 停在顶部） */
    fun recall(key: String): Entry? = entries[key]

    /** 显式遗忘某个路径（如「刷新」时想回到顶部） */
    fun forget(key: String) {
        entries.remove(key)
    }

    /** 清空（切换连接 / 退出时用） */
    fun clear() = entries.clear()

    /** 当前记住的目录数（测试 / 调试用） */
    val size: Int get() = entries.size

    companion object {
        /** 最多记住多少个目录（够覆盖一个正常浏览会话的返回链） */
        const val MAX_ENTRIES = 64

        /**
         * 把「列表下标」换算成「含 `..` 行的下标」。
         *
         * MT 的列表首行是 `..`（返回上级），Compose 的 `LazyListState` 下标把这一行也算进去；
         * 而 `PaneState.items` 不含 `..`，所以两侧换算必须一致，否则返回时会偏一行。
         */
        fun toListIndex(itemIndex: Int, hasParentRow: Boolean): Int =
            (itemIndex + if (hasParentRow) 1 else 0).coerceAtLeast(0)

        /** 反向换算：列表下标 → `items` 下标（`..` 行返回 -1） */
        fun toItemIndex(listIndex: Int, hasParentRow: Boolean): Int =
            if (hasParentRow) listIndex - 1 else listIndex
    }
}
