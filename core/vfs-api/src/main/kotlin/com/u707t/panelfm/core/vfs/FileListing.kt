package com.u707t.panelfm.core.vfs

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
        SortBy.NAME -> compareBy<FileMetadata> { it.name.lowercase() }
        SortBy.SIZE -> compareBy<FileMetadata> { if (it.isDirectory) -1L else it.size }
        SortBy.TIME -> compareBy<FileMetadata> { it.lastModified }
        SortBy.TYPE -> compareBy<FileMetadata> { it.extension.ifEmpty { it.name.lowercase() } }
    }
    val directed = if (spec.ascending) items.sortedWith(cmp) else items.sortedWith(cmp).reversed()
    return if (spec.dirsFirst) directed.sortedByDescending { it.isDirectory } else directed
}
