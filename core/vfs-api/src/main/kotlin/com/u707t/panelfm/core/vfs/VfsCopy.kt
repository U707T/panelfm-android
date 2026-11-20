package com.u707t.panelfm.core.vfs

import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 把 VFS 文件整段写进本地输出流（「物化落盘」的唯一实现）。
 *
 * ## 为什么必须收成一处
 *
 * 「打开 → 循环 read → 写 OutputStream」在本仓有 9 处手写副本（APK / PDF / 字体预览、
 * 压缩包挂载、编辑器、导出、内置 HTTP 服务…）。它们有三个共同要求，手抄时最容易漏：
 *
 *  1. **可取消**：每块 `ensureActive()`，用户关掉页面 / 取消任务要立刻停；
 *  2. **0 字节读不能空转**：远端偶发 0 读是正常的，连续 3 次就是源没进展，抛错而不是死循环；
 *  3. **不吞 EOF**：`read` 返回负数才是读完，不能把「读满缓冲」当结束（那会截断文件）。
 *
 * @param onProgress 每块回调一次，参数是**累计**字节数（节流由调用方负责）
 * @return 总字节数
 */
suspend fun VfsReader.copyTo(
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
        val n = read(buf, 0, buf.size)
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
