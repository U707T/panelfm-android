package com.u707t.panelfm.core.model

enum class TransferOp { COPY, MOVE }

/** 同名冲突策略（MT 语义：覆盖 / 跳过 / 保留两者 / 每次都问）。 */
enum class ConflictPolicy { OVERWRITE, SKIP, KEEP_BOTH, ASK }

/** 冲突弹窗的返回值 */
data class ConflictDecision(val policy: ConflictPolicy, val applyAll: Boolean = false)

data class ConflictInfo(
    val index: Int,
    val total: Int,
    val sourceName: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val destName: String,
    val destSize: Long,
    val destModified: Long,
    val isDirectory: Boolean,
)

enum class VerifyMode { NONE, SIZE, HASH }
