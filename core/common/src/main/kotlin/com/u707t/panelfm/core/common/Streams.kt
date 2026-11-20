package com.u707t.panelfm.core.common

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 「把输入流整段写进输出流」的唯一实现（java.io 侧）。
 *
 * 与 [com.u707t.panelfm.core.vfs.copyTo] 分工：那边是 `VfsReader`（挂起读，远端协议），
 * 这边是普通 `InputStream`（zip 条目、本地文件）。两边都做同一件容易写错的事：
 *
 *  - 每块都 `ensureActive()`：用户关掉对话框 / 取消任务时要立刻停，不能把 200 MB 白读完；
 *  - 连续 3 次 0 字节读视为「源不响应」并抛 [IOException]（而不是死循环）。
 *
 * @param onProgress 每块回调一次，参数是**累计**字节数
 * @return 总字节数
 */
suspend fun InputStream.copyStreamTo(
    output: OutputStream,
    bufferSize: Int = 128 * 1024,
    onProgress: ((bytes: Long) -> Unit)? = null,
): Long {
    require(bufferSize > 0) { "bufferSize 必须为正数" }
    val buf = ByteArray(bufferSize)
    var total = 0L
    var emptyReads = 0
    while (true) {
        coroutineContext.ensureActive()
        val n = read(buf)
        if (n < 0) break
        if (n == 0) {
            if (++emptyReads >= 3) throw IOException("读取无进展：源已停止响应（已读 $total 字节）")
            continue
        }
        emptyReads = 0
        output.write(buf, 0, n)
        total += n
        onProgress?.invoke(total)
    }
    return total
}
