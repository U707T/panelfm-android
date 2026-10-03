package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import java.io.OutputStream

/**
 * 压缩：把选中的文件/目录打成 zip 写到目标位置（目标可以是本地，也可以是网络 VFS）。
 * 因为写侧是统一的 [com.u707t.panelfm.core.vfs.VfsWriter]，所以「压缩到网络」天然可用。
 */
class ArchiveCompressor(private val locator: VfsLocator) {

    suspend fun compress(
        sources: List<VfsUri>,
        destFile: VfsUri,
        onProgress: ProgressCallback? = null,
    ) {
        val destVfs = locator.find(destFile) ?: throw VfsException.Unsupported("目标不可用")
        val writer = destVfs.openWrite(destFile, size = null, offset = 0L)
        var doneBytes = 0L
        var entries = 0
        try {
            withContext(Dispatchers.IO) {
                ZipArchiveOutputStream(SuspendOutputStream(writer)).use { zip ->
                    zip.setUseZip64(Zip64Mode.AsNeeded)
                    zip.setEncoding("UTF-8")
                    sources.forEach { source ->
                        val vfs = locator.find(source) ?: return@forEach
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) {
                            entries += addDirectory(zip, vfs, source, meta.name) { bytes ->
                                doneBytes += bytes
                                onProgress?.onProgress(doneBytes, -1)
                            }
                        } else {
                            addFile(zip, vfs, source, meta.name, meta.size)
                            doneBytes += meta.size.coerceAtLeast(0)
                            entries++
                        }
                    }
                    zip.finish()
                }
            }
            writer.commit()
            Logx.i("ArchiveCompressor", "compressed $entries entries → $destFile")
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw if (e is VfsException) e else VfsException.Io("压缩失败：${e.message}", e)
        }
    }

    private suspend fun addDirectory(
        zip: ZipArchiveOutputStream,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        dir: VfsUri,
        prefix: String,
        onBytes: (Long) -> Unit,
    ): Int {
        var count = 0
        zip.putArchiveEntry(ZipArchiveEntry("$prefix/").apply { time = System.currentTimeMillis() })
        zip.closeArchiveEntry()
        vfs.list(dir).forEach { child ->
            val name = "$prefix/${child.name}"
            if (child.isDirectory) {
                count += addDirectory(zip, vfs, child.uri, name, onBytes)
            } else {
                addFile(zip, vfs, child.uri, name, child.size)
                onBytes(child.size.coerceAtLeast(0))
                count++
            }
        }
        return count
    }

    private suspend fun addFile(
        zip: ZipArchiveOutputStream,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
        size: Long,
    ) {
        val entry = ZipArchiveEntry(entryName)
        if (size > 0) entry.size = size      // 已知大小就写上；未知则走流式（DEFLATED + 数据描述符）
        zip.putArchiveEntry(entry)
        val reader = vfs.openRead(file)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                zip.write(buffer, 0, n)
            }
        } finally {
            runCatching { reader.close() }
            zip.closeArchiveEntry()
        }
    }

    /** 把 suspend 的 VfsWriter 适配成 java.io.OutputStream（阻塞写，运行在 IO 线程） */
    private class SuspendOutputStream(
        private val writer: com.u707t.panelfm.core.vfs.VfsWriter,
    ) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            kotlinx.coroutines.runBlocking { writer.write(b, off, len) }
        }

        override fun flush() {
            kotlinx.coroutines.runBlocking { writer.flush() }
        }
    }

    companion object {
        fun zipNameFor(sources: List<VfsUri>): String {
            val base = when {
                sources.size == 1 -> sources.first().name.substringBeforeLast('.', sources.first().name)
                else -> "archive"
            }
            return "$base.zip"
        }

        fun contentTypeOf(name: String): String = MimeTypes.of(name.substringAfterLast('.', "")) ?: "application/zip"
    }
}
