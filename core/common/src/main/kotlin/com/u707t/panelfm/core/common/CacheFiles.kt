package com.u707t.panelfm.core.common

import java.io.File

/**
 * 物化缓存（远端 PDF / APK 先落盘才能交给系统 API）的**统一清理**。
 *
 * 为什么收成一处：APK 与 PDF 两条链路各写了一份一模一样的「按最后修改时间删超龄文件」，
 * 连 3 天的窗口都写死成 `3L * 24 * 3600 * 1000`。缓存策略只有一份才好调 ——
 * 以前改一边忘另一边，就会出现「PDF 清了、APK 没清」的存储投诉。
 *
 * @param keep 本次要用的目标文件（永不删除；null = 全按超龄删）
 * @param maxAgeMs 保留窗口，默认 3 天
 * @return 删掉的文件数
 */
fun sweepStaleFiles(
    dir: File,
    keep: File? = null,
    maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
): Int {
    val cutoff = System.currentTimeMillis() - maxAgeMs
    var removed = 0
    runCatching {
        dir.listFiles()?.forEach { f ->
            if (f != keep && f.lastModified() < cutoff && runCatching { f.delete() }.getOrDefault(false)) {
                removed++
            }
        }
    }
    return removed
}

/** 物化缓存的默认保留窗口（3 天） */
const val DEFAULT_MAX_AGE_MS: Long = 3L * 24 * 3600 * 1000
