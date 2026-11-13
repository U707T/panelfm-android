package com.u707t.panelfm.core.common

/**
 * 自然排序：连续数字段按**数值**比较，其余按小写码点比较。
 *
 * `2.txt` 排在 `10.txt` 前面；`file1` < `file2` < `file10`。
 *
 * 几个刻意的选择：
 *  - **不用 `Collator`**：它依赖平台 ICU 数据，单测在 JVM 与真机上结果可能不同，
 *    而且每次比较都有成本（排序 10 万项时是热点）。中文文件名仍按码点排，
 *    与旧实现（`lowercase()` 字符串比较）完全一致 —— 本类只改「数字段」。
 *  - **数字段用「去前导零后的长度 + 字典序」**，不转 `Long`：
 *    文件名里出现 30 位数字时不会溢出，也不会抛异常。
 *  - 数字值相等时**前导零少的在前**（`7` < `007`），保证排序确定、可预期。
 *  - 只把 ASCII `0..9` 当数字段（不用 `Char.isDigit()`：那会把阿拉伯-印度数字
 *    也当成数字段，而它们按码点比较根本不是数值序）。
 *
 * 比较器无状态、线程安全。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val digitsA = a.substring(startA, i)
                val digitsB = b.substring(startB, j)
                // 去前导零：长度不同 → 数值大小已定（长度大的一定更大）
                val numA = digitsA.trimStart('0')
                val numB = digitsB.trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val byDigits = numA.compareTo(numB)
                if (byDigits != 0) return byDigits
                // 数值相等：前导零少的在前（"7" < "007"），保证全序
                if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
                continue
            }
            val la = ca.lowercaseChar()
            val lb = cb.lowercaseChar()
            if (la != lb) return la.compareTo(lb)
            i++
            j++
        }
        // 前缀相同：短的在前
        return (a.length - i) - (b.length - j)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
