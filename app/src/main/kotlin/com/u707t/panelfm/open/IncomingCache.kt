package com.u707t.panelfm.open

import java.io.File

/**
 * `cache/incoming` 的清理策略（纯文件逻辑，可单测）。
 *
 * 规则：先按年龄删（7 天），再按容量删（512 MB，最旧优先）；
 * 超过 1 小时的 `.part` 是上次中断的残片，单独清掉。
 */
internal object IncomingCache {

    const val MAX_BYTES = 512L * 1024 * 1024
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    const val PART_MAX_AGE_MS = 3600L * 1000

    /** @return 删除的文件数（仅用于日志/测试断言）。 */
    fun cleanup(
        dir: File,
        now: Long = System.currentTimeMillis(),
        maxBytes: Long = MAX_BYTES,
        maxAgeMs: Long = MAX_AGE_MS,
        partMaxAgeMs: Long = PART_MAX_AGE_MS,
    ): Int {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        var deleted = 0
        var total = 0L
        // 最新的在前：容量超限时保留新的、删旧的。
        files.sortedByDescending { it.lastModified() }.forEachIndexed { index, f ->
            val age = now - f.lastModified()
            if (f.name.endsWith(".part") && age > partMaxAgeMs) {
                if (f.delete()) deleted++
                return@forEachIndexed
            }
            if (age > maxAgeMs) {
                if (f.delete()) deleted++
                return@forEachIndexed
            }
            total += f.length()
            // index == 0 是刚收到的最新文件：即使它自己就超过 maxBytes 也不删
            // （删掉用户正要打开的东西，比缓存暂时超限更糟）。
            if (index > 0 && total > maxBytes) {
                // 删掉后把体积从累计里扣掉，否则一旦超限会把后面所有旧文件全删光。
                total -= f.length()
                if (f.delete()) deleted++
            }
        }
        return deleted
    }
}
