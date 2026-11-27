package com.u707t.panelfm.core.common

/** 差异行的类别（+ 新增 / - 删除 / 未变） */
enum class DiffLineKind { SAME, ADD, REMOVE }

/** 一行差异（[leftNo] / [rightNo] 为 1 起的行号，缺失侧为 null） */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val leftNo: Int?,
    val rightNo: Int?,
)

/**
 * 文本对比引擎（复刻 MT 对比器 `0x7f0e000a` 的「忽略」四档 + 「区分大小写」）。
 *
 * 关键语义（与 MT 一致）：
 *  - **归一化只影响比较**，展示的始终是原文；
 *  - 「忽略空格和空行」会把空行整体丢掉（行号仍指原文行号）；
 *  - 行数与矩阵分别受预算约束；超出矩阵预算明确拒绝，不给出不完整的相等结论。
 */
object TextDiffEngine {
    /** 约 4 MiB 的 Int 矩阵；行数组开销另计。调用方不能抬高此硬上限。 */
    const val MAX_MATRIX_CELLS = 1_000_000L

    data class DiffResult(
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val truncated: Boolean,
    ) {
        val sameCount: Int get() = lines.count { it.kind == DiffLineKind.SAME }

        /** 差异块数量（连续 ADD/REMOVE 视为一块）——用于「上一个 / 下一个差异」 */
        val hunks: List<Int> get() = buildList {
            var inHunk = false
            lines.forEachIndexed { index, line ->
                if (line.kind == DiffLineKind.SAME) inHunk = false
                else if (!inHunk) { add(index); inHunk = true }
            }
        }
    }

    fun diff(
        leftText: String,
        rightText: String,
        ignore: DiffIgnore = DiffIgnore.NONE,
        caseSensitive: Boolean = true,
        maxLines: Int = 20000,
        checkCancelled: () -> Unit = {},
    ): DiffResult {
        require(maxLines in 1..20000) { "对比行数上限必须在 1..20000" }
        checkCancelled()
        // Only materialize one lookahead line beyond the cap, even for newline-heavy input.
        val leftRaw = leftText.splitToSequence('\n').take(maxLines + 1).toList()
        val rightRaw = rightText.splitToSequence('\n').take(maxLines + 1).toList()
        val leftTruncated = leftRaw.size > maxLines
        val rightTruncated = rightRaw.size > maxLines

        // (原文, 原文行号) —— 行号始终按原文计（丢空行也不影响编号）
        val a = leftRaw.take(maxLines).mapIndexedNotNull { i, line ->
            if (ignore.dropsBlankLines && ignore.key(line, caseSensitive).isEmpty()) null else line to (i + 1)
        }
        val b = rightRaw.take(maxLines).mapIndexedNotNull { i, line ->
            if (ignore.dropsBlankLines && ignore.key(line, caseSensitive).isEmpty()) null else line to (i + 1)
        }
        val ka = a.map { ignore.key(it.first, caseSensitive) }
        val kb = b.map { ignore.key(it.first, caseSensitive) }

        val n = a.size
        val m = b.size
        require((n + 1L) * (m + 1L) <= MAX_MATRIX_CELLS) {
            "文本对比规模过大（矩阵上限 $MAX_MATRIX_CELLS 单元），请减少参与对比的行数"
        }
        val dp = Array(n + 1) { checkCancelled(); IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            checkCancelled()
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (ka[i] == kb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            checkCancelled()
            when {
                ka[i] == kb[j] -> {
                    out.add(DiffLine(DiffLineKind.SAME, a[i].first, a[i].second, b[j].second)); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(DiffLine(DiffLineKind.REMOVE, a[i].first, a[i].second, null)); i++
                }
                else -> {
                    out.add(DiffLine(DiffLineKind.ADD, b[j].first, null, b[j].second)); j++
                }
            }
        }
        while (i < n) { out.add(DiffLine(DiffLineKind.REMOVE, a[i].first, a[i].second, null)); i++ }
        while (j < m) { out.add(DiffLine(DiffLineKind.ADD, b[j].first, null, b[j].second)); j++ }

        return DiffResult(
            lines = out,
            added = out.count { it.kind == DiffLineKind.ADD },
            removed = out.count { it.kind == DiffLineKind.REMOVE },
            truncated = leftTruncated || rightTruncated,
        )
    }
}
