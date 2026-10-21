package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.common.FileSearch

/** 内容搜索每个文件最多读取的字节数（够覆盖绝大多数文本的关键信息，又不至于把大文件整个拉下来）。 */
internal const val CONTENT_SCAN_BYTES: Long = 512L * 1024

/** 内容搜索单文件上限：已知超过这个大小的文件不读。 */
internal const val CONTENT_SCAN_MAX_FILE: Long = 2L * 1024 * 1024

/** 内容搜索整次搜索的总读取预算：防止「8,000 个文件 × 512 KB」把远端读穿。 */
internal const val CONTENT_SCAN_MAX_TOTAL: Long = 64L * 1024 * 1024

/**
 * 搜索结果预算（纯逻辑，单测锁定「继续搜索」真的能扩大预算）。
 *
 * 旧实现把 `limit` 当不可变硬上限，却又在同一个数值上弹「你确定继续搜索？」——
 * 用户点「继续」之后仍然立刻停在 300 条，按钮等于没用。
 */
internal class SearchBudget(initial: Int, private val step: Int) {

    var limit: Int = initial
        private set

    /** 达到预算且没有可用的询问通道：结果被截断，需要如实告知用户。 */
    var capped: Boolean = false
        private set

    fun reached(count: Int): Boolean = count >= limit

    /** 用户确认继续：预算至少放到「当前结果数 + 步长」。 */
    fun continueAfterAsk(count: Int) {
        limit = maxOf(limit, count) + (if (step > 0) step else DEFAULT_STEP)
    }

    fun markCapped() {
        capped = true
    }

    private companion object {
        const val DEFAULT_STEP = 300
    }
}

/**
 * 内容搜索是否值得读这个条目。
 *
 * `size == -1` 是「未知」而不是「超限」（远程 / 压缩包条目常见）：不能跳过，
 * 读取本身已经有 [CONTENT_SCAN_BYTES] 上限；只有**已知**超过 [CONTENT_SCAN_MAX_FILE] 才放弃。
 */
internal fun contentScanWorthReading(size: Long, extension: String): Boolean =
    (size < 0 || size <= CONTENT_SCAN_MAX_FILE) && FileSearch.worthContentScan(extension)
