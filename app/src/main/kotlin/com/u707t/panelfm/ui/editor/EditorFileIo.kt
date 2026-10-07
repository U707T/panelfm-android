package com.u707t.panelfm.ui.editor

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.TextEncodings
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/*
 * 编辑器文件层的读写基础设施（与 Compose 无关，可单测）。
 *
 * 分页不变式：对任意相邻两页，`displayEnd(pageN) == displayStart(pageN+1)`——
 * 页头、页尾各在「名义边界外的 SLACK 窗口」内对齐同一条行边界，页与页既不重叠也不漏内容；
 * 只有超长行（SLACK 窗口内没有换行）才退回名义边界切断。
 * 回归测试见 `EditorFileIoTest`。
 */

/** 可直接编辑的文件大小上限（超过进只读分段浏览） */
internal const val MAX_EDIT_SIZE = 2L * 1024 * 1024

/** 大文件分段大小与行对齐冗余 */
internal const val PAGE_SIZE = 512L * 1024
internal const val PAGE_SLACK = 64L * 1024

/** 语法高亮字符上限（超过则退化为纯文本，保证输入流畅） */
internal const val HL_MAX_CHARS = 640_000

internal data class PageSlice(val text: String, val charset: String, val start: Long, val end: Long)

/** 一页在缓冲区里的显示窗口：[skip, tail)（相对缓冲区起点）。 */
internal data class PageWindow(val skip: Int, val tail: Int)

/**
 * 计算一页的显示窗口。纯函数（可单测）；[pageSize] / [slack] 仅供测试注入。
 *
 * 相邻页不变式：`(page+1)*pageSize + next.skip == page*pageSize + window.tail`。
 * 为使其成立，页尾与下一页页头**用同一个窗口**（名义终点起 [slack] 字节内）找同一条行边界。
 */
internal fun pageWindow(
    buf: ByteArray,
    off: Int,
    page: Int,
    fileSize: Long,
    charset: String,
    pageSize: Long = PAGE_SIZE,
    slack: Long = PAGE_SLACK,
): PageWindow {
    val start = page.toLong() * pageSize
    // 页头：非首页在前一个 slack 窗口内找行边界（与上一页页尾同一判定）
    var skip = 0
    if (page > 0) {
        val headLimit = minOf(slack, off.toLong()).toInt()
        val nl = lineBoundaryAfter(buf, headLimit, charset, from = 0)
        if (nl >= 0) skip = nl
    }
    // 页尾：非末页在「名义终点起 slack」窗口内找行边界；找不到（超长行）就切在名义终点
    var tail = off
    if (start + pageSize < fileSize) {
        val from = pageSize.toInt().coerceAtMost(off)
        val nl = if (from < off) lineBoundaryAfter(buf, off, charset, from = from) else -1
        tail = if (nl >= 0) nl else from
    }
    return PageWindow(skip, tail)
}

/**
 * 在 [from, limit) 内找「换行之后」的下标；找不到返回 -1。
 * UTF-16 按 2 字节码元识别（`0A 00` / `00 0A`），避免单字节误判与奇偏移错位。
 */
internal fun lineBoundaryAfter(buf: ByteArray, limit: Int, charset: String, from: Int): Int {
    val lim = minOf(limit, buf.size)
    return when (charset) {
        "UTF-16LE" -> {
            var i = from
            while (i + 1 < lim) {
                if (buf[i] == 0x0A.toByte() && buf[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            -1
        }
        "UTF-16BE" -> {
            var i = from
            while (i + 1 < lim) {
                if (buf[i] == 0.toByte() && buf[i + 1] == 0x0A.toByte()) return i + 2
                i += 2
            }
            -1
        }
        else -> {
            for (i in from until lim) if (buf[i] == 0x0A.toByte()) return i + 1
            -1
        }
    }
}

/**
 * 读取一页：从 [page]×PAGE_SIZE 起读 PAGE_SIZE + SLACK（冗余用于行对齐）。
 * 首页先侦测编码（编码名与保存写回共用 `TextEncodings` 一张表），再按编码对齐窗口。
 */
internal suspend fun loadPage(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    page: Int,
    fileSize: Long,
    charset: String?,
): PageSlice = withContext(Dispatchers.IO) {
    val start = page.toLong() * PAGE_SIZE
    if (start >= fileSize) return@withContext PageSlice("", charset ?: "UTF-8", start, start)
    val readEnd = minOf(start + PAGE_SIZE + PAGE_SLACK, fileSize)
    val reader = vfs.openRead(uri, offset = start)
    try {
        val len = (readEnd - start).toInt().coerceAtLeast(0)
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = reader.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        // 首页：先按本节缓冲区侦测编码（后续页 charset 已知）
        val detected = if (charset == null) TextEncodings.decode(buf.copyOfRange(0, off)) else null
        val cs = charset ?: detected!!.charset
        val window = pageWindow(buf, off, page, fileSize, cs)
        val text = TextEncodings.decodeWith(cs, buf.copyOfRange(window.skip, window.tail))
        PageSlice(text, cs, start + window.skip, start + window.tail)
    } finally {
        runCatching { reader.close() }
    }
}

/** 读至多 [max] 字节（≤2 MB 的可编辑路径用）。 */
internal suspend fun readAtMost(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    max: Long,
): ByteArray = withContext(Dispatchers.IO) {
    val reader = vfs.openRead(uri)
    try {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = reader.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.toByteArray()
    } finally {
        runCatching { reader.close() }
    }
}

/**
 * 保存：[text] 按 [charset] 编码写回（BOM 重建 / 无法表示字符回退见 `TextEncodings.encode`），
 * 备份先做且成败都进状态栏；会话断开时自动重连一次（与其余入口一致）。
 */
internal suspend fun saveText(
    container: AppContainer,
    uri: VfsUri,
    text: String,
    charset: String,
    originalMode: Int?,
    onStatus: (String?) -> Unit,
    onDone: (Boolean) -> Unit,
) {
    try {
        val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
        val encoded = TextEncodings.encode(text, charset)
        val bytes = encoded.bytes
        // MT「保存文件时自动将原文件重命名为 .bak 备份文件」：备份先做，且必须能看见成败
        val backupEnabled = container.settings.value.backupOnSave
        val backupName = if (backupEnabled) backupBeforeSave(container, vfs, uri) else null
        withContext(Dispatchers.IO) {
            val writer = vfs.openWrite(uri, size = bytes.size.toLong(), offset = 0L)
            writer.write(bytes, 0, bytes.size)
            writer.commit()
            // 保留原有权限（本地/SFTP/FTP 支持 chmod 时）
            if (originalMode != null && vfs.capabilities.permissions) {
                runCatching { vfs.setPermissions(uri, originalMode) }
            }
        }
        val transcoded = if (encoded.charset != charset) "·转存自 $charset" else ""
        val backupNote = when {
            !backupEnabled -> ""
            backupName != null -> "，原文件已备份为 $backupName"
            else -> "；⚠️ 备份失败（本次未生成备份）"
        }
        onStatus("已保存（${encoded.charset}$transcoded，${bytes.size} 字节$backupNote）")
        onDone(true)
    } catch (e: Exception) {
        onStatus("保存失败：${e.message}")
        onDone(false)
    }
}

/**
 * 「保存前自动 .bak 备份」的实际实现。
 *
 * 复制到不冲突的 `.bak` / `.bak.1` / …（复用 [AppContainer.uniqueChild]），
 * 返回备份名；失败返回 null，由调用方在状态里明确提示。
 */
internal suspend fun backupBeforeSave(
    container: AppContainer,
    vfs: VirtualFileSystem,
    uri: VfsUri,
): String? {
    val parent = uri.parent ?: return null
    return runCatching {
        val dest = container.uniqueChild(parent, uri.name + ".bak")
        // 服务端复制优先（本地 / S3 是秒级）；不支持时退化为「读流 → 写流」
        if (vfs.capabilities.serverSideCopy && vfs.serverSideCopy(uri, dest)) {
            return@runCatching dest.name
        }
        val reader = vfs.openRead(uri)
        try {
            val writer = vfs.openWrite(dest, size = reader.size, offset = 0L)
            try {
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) break
                    writer.write(buf, 0, n)
                }
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
        } finally {
            runCatching { reader.close() }
        }
        dest.name
    }.getOrNull()
}
