package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.NaturalOrder
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec

/**
 * 统一列目录排序（所有协议共用，避免各实现复制粘贴导致行为不一致）。
 *
 * 顺序很重要：
 *  1. 先按「方向」排序（升序 / 降序）；
 *  2. 最后再「文件夹置顶」——反过来会把文件夹在降序时翻到最底部（历史 bug）。
 *
 * `sortedWith` / `sortedByDescending` 均为稳定排序：同序元素保持原有相对顺序。
 */
fun sortFileItems(items: List<FileMetadata>, spec: SortSpec): List<FileMetadata> {
    val cmp: Comparator<FileMetadata> = when (spec.by) {
        // 名称用自然序：`2.txt` 排在 `10.txt` 前（旧实现是纯字符串比较）
        SortBy.NAME -> Comparator { x, y -> NaturalOrder.compare(x.name, y.name) }
        SortBy.SIZE -> compareBy<FileMetadata> { if (it.isDirectory) -1L else it.size }
        SortBy.TIME -> compareBy<FileMetadata> { it.lastModified }
        // 类型：先按扩展名，再按名称自然序 —— 同扩展名的一批不再「顺序随机」
        SortBy.TYPE -> Comparator { x, y ->
            val byExt = x.extension.lowercase().compareTo(y.extension.lowercase())
            if (byExt != 0) byExt else NaturalOrder.compare(x.name, y.name)
        }
    }
    val directed = if (spec.ascending) items.sortedWith(cmp) else items.sortedWith(cmp).reversed()
    return if (spec.dirsFirst) directed.sortedByDescending { it.isDirectory } else directed
}
