package com.u707t.panelfm.ui.preview

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 预览用的统一输入流：**每次调用都开一条新流**（图片两遍解码 / SVG 解析各自重新打开）。
 *
 *  - 本地文件直读 [FileInputStream]（少一层 VFS 适配）；
 *  - 其余（压缩包内 / 远程 / 会话已回收）走 [vfsInputStream] 的顺序读桥接。
 */
internal fun openPreviewStream(container: AppContainer, uri: VfsUri): InputStream {
    if (uri.scheme == "local") {
        val path = runCatching { container.localVfs.absolutePath(uri) }.getOrNull()
        if (path != null && File(path).exists()) return FileInputStream(path)
    }
    return vfsInputStream(container, uri)
}

/**
 * 把统一 VFS 的顺序读适配成 InputStream（`BitmapFactory.decodeStream` / AndroidSVG 用）。
 *
 * 说明：
 *  - `read()` 是**阻塞式**的（`InputStream` 的契约如此），调用方已在
 *    `Dispatchers.IO` 上执行，所以这里用 `runBlocking` 把 suspend 的 VFS 读取桥接过来；
 *  - `skip()` **不借用共享缓冲区**：早期实现复用 `buf` 与 `read()` 抢同一块内存，
 *    并发或嵌套调用时会读到脏数据（表现为图片偶发解码失败）；
 *  - 支持随机访问的 VFS 直接用 `seek` 跳过，避免把整段数据读进来丢掉；
 *  - `available()` 返回 `min(剩余, Int.MAX_VALUE)`：部分解码器会用它估算缓冲，
 *    恒返回 0 会让它们退化（虽然多数情况下仍能工作）。
 */
private fun vfsInputStream(container: AppContainer, uri: VfsUri): InputStream {
    val vfs = container.locator.find(uri) ?: throw IllegalStateException("会话不可用")
    return object : InputStream() {
        private val reader = vfs.openRead(uri)
        private val total = reader.size
        private var consumed = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = kotlinx.coroutines.runBlocking { reader.read(one, 0, 1) }
            return if (n <= 0) -1 else {
                consumed += n
                one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = kotlinx.coroutines.runBlocking { reader.read(b, off, len) }
            if (n > 0) consumed += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            // 能 seek 的 VFS 直接跳（远程 / 本地都支持），不浪费带宽与内存
            if (reader.supportsSeek) {
                val target = (consumed + n).coerceAtMost(total?.takeIf { it >= 0 } ?: Long.MAX_VALUE)
                val delta = target - consumed
                if (delta <= 0) return 0
                runCatching { kotlinx.coroutines.runBlocking { reader.seek(target) } }
                    .onSuccess { consumed = target; return delta }
            }
            // 兜底：逐块读掉（用**独立**缓冲区，不与 read() 共享）
            val scratch = ByteArray(16 * 1024)
            var left = n
            while (left > 0) {
                val want = minOf(scratch.size.toLong(), left).toInt()
                val got = kotlinx.coroutines.runBlocking { reader.read(scratch, 0, want) }
                if (got <= 0) break
                consumed += got
                left -= got
            }
            return n - left
        }

        override fun available(): Int {
            val t = total ?: return 0
            if (t < 0) return 0
            return (t - consumed).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() = runCatching { reader.close() }.let { Unit }
    }
}
