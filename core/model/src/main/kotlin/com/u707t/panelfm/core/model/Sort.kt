package com.u707t.panelfm.core.model

enum class SortBy { NAME, SIZE, TIME, TYPE }

data class SortSpec(
    val by: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val dirsFirst: Boolean = true,
) {
    companion object {
        val Default = SortSpec()
    }
}
