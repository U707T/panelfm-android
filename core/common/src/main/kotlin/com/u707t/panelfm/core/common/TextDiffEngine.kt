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
 *  - 大文件先截断到 [maxLines]（避免 LCS 的 O(n·m) 内存爆掉），并在返回的 [DiffResult.truncated] 里标记。
 */
object TextDiffEngine {

    data class DiffResult(
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val truncated: Boolean,
    ) {
        val sameCount: Int get() = lines.count { it.kind == DiffLineKind.SAME }

        /** 差异块数量（连续 ADD/REMOVE 视为一块）——用于「上一个 / 下一个差异」 */
        val hunks: List<Int> get() = lines.withIndex()
            .filter { it.value.kind != DiffLineKind.SAME }
            .map { it.index }
            .fold(emptyList()) { acc, idx ->
                if (acc.isNotEmpty() && acc.last() + 1 == idx) acc else acc + idx
            }
    }

    fun diff(
        leftText: String,
        rightText: String,
        ignore: DiffIgnore = DiffIgnore.NONE,
        caseSensitive: Boolean = true,
        maxLines: Int = 20000,
    ): DiffResult {
        val leftRaw = leftText.split('\n')
        val rightRaw = rightText.split('\n')
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
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (ka[i] == kb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
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
