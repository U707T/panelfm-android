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
    /** **目标**是否文件夹（历史命名；与 [sourceIsDirectory] 组成冲突框的四象限） */
    val isDirectory: Boolean,
    /** MT 的冲突框按「复制 / 移动」给出不同措辞（复制并替换 vs 移动并替换） */
    val isMove: Boolean = false,
    /** 源是否文件夹 —— 文件→文件夹的「替换」= 递归删除整个文件夹，文案与默认项都必须区分 */
    val sourceIsDirectory: Boolean = false,
)

/** [ConflictInfo] 的解说文案：按「源类型 × 目标类型」四象限分派（对话框与测试共用）。 */
fun ConflictInfo.explanationText(): String = when {
    isDirectory && sourceIsDirectory -> "目标是一个文件夹：替换将递归合并，同名文件按覆盖处理。"
    isDirectory -> "目标是一个文件夹：替换将删除该文件夹及其全部内容，然后写入文件。"
    sourceIsDirectory -> "目标是一个文件：替换将删除该文件并创建文件夹。"
    else -> "替换会覆盖目标文件的内容。"
}

/**
 * 冲突对话框的默认预选项。
 *
 * 只有「文件 → 文件夹」改默认：「替换」的实质是递归删除整个文件夹，破坏面最大、
 * 而「合并」的直觉在此根本不成立 —— 默认「跳过」，不让顺手点「确定」毁掉一个目录。
 * （目录 → 目录的替换 = 合并，保持 MT 习惯的默认「替换」。）
 */
fun ConflictInfo.defaultPolicy(): ConflictPolicy =
    if (isDirectory && !sourceIsDirectory) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE

enum class VerifyMode { NONE, SIZE, HASH }
