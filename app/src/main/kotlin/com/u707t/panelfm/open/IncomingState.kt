package com.u707t.panelfm.open

import com.u707t.panelfm.core.vfs.VfsUri

/**
 * 外部收文件的状态机。
 *
 * 只依赖 [VfsUri]，不碰任何 Android 类型 —— 这样状态判定（[LocatePolicy] 与相关测试）
 * 能在纯 JVM 上验证，不需要模拟器或 ContentResolver。
 */
sealed interface IncomingState {
    data object Idle : IncomingState

    /** 正在复制到接收目录（[total] < 0 = 来源没报大小）。 */
    data class Copying(val name: String, val copied: Long, val total: Long) : IncomingState

    /** 已复制到接收目录，可以预览 / 另存。 */
    data class Ready(val uri: VfsUri, val name: String, val mime: String?, val size: Long) : IncomingState

    data class Error(val message: String) : IncomingState

    /**
     * 外部文件反查到了**可浏览的真实路径**：不复制，直接定位原文件。
     *
     * [uri] 是原文件的 VfsUri。`size` / `mime` 来自 provider，仅用于文案与后续预览，
     * 不再参与落盘。
     */
    data class Located(
        val uri: VfsUri,
        val name: String,
        val mime: String?,
        val size: Long,
    ) : IncomingState

    /**
     * 一次收到多个文件（分享面板多选）。
     *
     * [items] 里能定位的直接定位、不能定位的已复制到接收目录；[failed] 是连复制都失败的项。
     * UI 负责列出来让用户挑一个处理。
     */
    data class Batch(
        val items: List<BatchItem>,
        val failed: List<String>,
    ) : IncomingState

    data class BatchItem(
        val name: String,
        /** 能定位时是原文件；不能定位时是接收目录里的副本。 */
        val uri: VfsUri,
        /** true = 这是接收目录里的副本（原文件不可定位或无法读取）。 */
        val isCopy: Boolean,
        val mime: String?,
        val size: Long,
    )
}
