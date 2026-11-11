package com.u707t.panelfm.core.vfs

import android.media.MediaDataSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * 把 [VfsReader] 桥成 `android.media.MediaDataSource`，给 `MediaMetadataRetriever` 用。
 *
 * 为什么必须桥：`setDataSource(path)` 只认本地文件；网络视频（WebDAV / SFTP / SMB / S3）
 * 要出首帧，就得让解复用器在**不下载整部片**的前提下随机读 —— 这正是 `readFullyAt` 的用途。
 *
 * ## 三层保护（远端一个坏源不能把取帧流水线拖死）
 *
 * 1. **窗口合并**：拿到 `readAt` 不一定只读几十字节，解复用器读 moov 时是成百上千次小读。
 *    WebDAV 的 `readFullyAt` 每次都是一发 HTTP Range，逐字节直通就是几百发请求；这里按
 *    [windowBytes] 对齐抓一段、后续小读全部命中窗口。
 * 2. **预算**：单张缩略图最多读 [budgetBytes]。遇到需要顺序扫全片的格式（TS / 损坏容器）
 *    会在这里快速失败，回退类型图标，而不是把 500 MB 拖下来。
 * 3. **单次读超时**：`readAt` 是**阻塞回调**（由解复用器在自己的线程上同步调用），
 *    外层协程超时取消不了它 —— 所以每次读自己带 [readTimeoutMs]，超时抛 IOException，
 *    让 retriever 自己走失败路径。
 *
 * 线程约定：[readAt] 用 `runBlocking` 等异步 VFS，因此**不允许在 Main 上调用**。
 * 生命周期：[close] 只置关闭标记，[reader] 由调用方负责关闭（retriever 释放后再关）。
 */
class VfsMediaDataSource(
    private val reader: VfsReader,
    private val totalSize: Long,
    /** 每次抓取的窗口大小；越大请求越少、首帧延迟越高 */
    private val windowBytes: Int = DEFAULT_WINDOW_BYTES,
    /** 单张缩略图的读取总预算 */
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    /** 单次窗口抓取超时 */
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) : MediaDataSource() {

    private var window = ByteArray(0)
    private var windowStart = 0L
    private var readBytes = 0L
    private var closed = false

    override fun getSize(): Long = totalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("数据源已关闭")
        if (position < 0 || length <= 0) return 0
        if (position >= totalSize) return -1

        // 命中已有窗口：远端小读不再发请求
        val cached = hit(position, buffer, offset, length)
        if (cached >= 0) return cached

        val start = position / windowBytes * windowBytes
        val want = minOf(windowBytes.toLong(), totalSize - start).toInt()
        if (readBytes + want > budgetBytes) {
            throw IOException("读取超出预算（已读 $readBytes + $want > $budgetBytes）")
        }
        val bytes = runCatching { fetch(start, want) }.getOrElse { e ->
            throw IOException("窗口抓取失败 @$start+$want：${e.message}", e)
        }
        if (bytes.isEmpty()) return -1
        window = bytes
        windowStart = start
        readBytes += bytes.size

        val again = hit(position, buffer, offset, length)
        if (again >= 0) return again
        // 抓回来的窗口短于请求（服务器返回了更少的字节）且仍没覆盖到 position —— 视为不可读
        throw IOException("窗口未覆盖请求位置：$position 不在 $windowStart+${window.size}")
    }

    /** 命中窗口则拷贝并返回拷贝字节数，未命中返回 -1。 */
    private fun hit(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val skip = position - windowStart
        if (skip < 0 || skip >= window.size) return -1
        val n = minOf(length.toLong(), window.size - skip).toInt()
        System.arraycopy(window, skip.toInt(), buffer, offset, n)
        return n
    }

    private fun fetch(start: Long, want: Int): ByteArray = runBlocking {
        try {
            withTimeout(readTimeoutMs) { reader.readFullyAt(start, want) }
        } catch (e: TimeoutCancellationException) {
            throw IOException("读取超时（${readTimeoutMs}ms）")
        }
    }

    override fun close() {
        closed = true
        window = ByteArray(0)
    }

    companion object {
        /** 512 KB：一个 1080p 的 moov + 首帧通常落在 1–3 个窗口内 */
        const val DEFAULT_WINDOW_BYTES = 512 * 1024

        /** 单张缩略图最多读 8 MB —— 正常 MP4 用不到 1/4，超了说明这个源不该取帧 */
        const val DEFAULT_BUDGET_BYTES = 8L * 1024 * 1024

        /** 单次窗口抓取 4 秒；比整张缩略图的超时短，失败得更早 */
        const val DEFAULT_READ_TIMEOUT_MS = 4_000L
    }
}
