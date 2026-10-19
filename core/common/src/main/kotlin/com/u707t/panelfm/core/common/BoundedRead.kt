package com.u707t.panelfm.core.common

import java.io.ByteArrayOutputStream

/**
 * 「带上限的顺序读」的唯一实现（编辑器 / 预览 / 压缩包条目共用）。
 *
 * ## 为什么必须收成一处
 *
 * 「读到一个上限就停」这个动作在本仓有三份手写实现（编辑器 `readUpTo`、
 * 预览 `readPreviewBytes`、EPUB `readArchiveEntry`），而且**只有编辑器那份是对的**：
 * 另外两处把「读满上限」当成读完，于是远端 VFS 报 `size = -1`（大小未知）时，
 * 用户拿到的是被截断的文档 / 章节 —— 没有报错、没有提示，看起来就是「文件内容不对」。
 *
 * ## 契约
 *
 *  - 读完（遇到 EOF）→ 返回全部字节；
 *  - **读到的字节数超过 [max]** → 返回 null（调用方必须明确拒绝或提示，不能当全文用）；
 *  - 正好等于 [max] → 返回该内容（不多读 1 字节无法区分这两种情况，所以实现会多读 1 个）。
 *
 * [read] 用 lambda 注入，便于单测（真实实现传 `VfsReader::read`）。
 */
suspend fun readBounded(
    max: Long,
    read: suspend (ByteArray, Int, Int) -> Int,
): ByteArray? {
    if (max < 0) return null
    val out = ByteArrayOutputStream(if (max in 1..(1L shl 20)) max.toInt() else 64 * 1024)
    val buf = ByteArray(64 * 1024)
    var total = 0L
    var emptyReads = 0
    while (true) {
        // 多读 1 字节：能读到就说明「比上限大」
        val want = minOf(buf.size.toLong(), max + 1 - total)
        if (want <= 0) break
        val n = read(buf, 0, want.toInt())
        if (n < 0) break
        if (n == 0) {
            // 契约允许返回 0（个别远端流在 EOF 边缘会这样），但不能因此空转成死循环
            if (++emptyReads >= 3) break
            continue
        }
        emptyReads = 0
        out.write(buf, 0, n)
        total += n
        if (total > max) return null
    }
    return out.toByteArray()
}

/**
 * 文件大小是否「确定超过上限」。
 *
 * [size] < 0 表示未知（远端 / 压缩包条目常见）—— **不能据此判定超限**，
 * 必须继续走 [readBounded] 边读边判。这一条是三处截断 bug 的共同根因，
 * 所以做成显式函数，调用点不再自己写 `size > max`。
 */
fun definitelyExceeds(size: Long, max: Long): Boolean = size >= 0 && size > max
