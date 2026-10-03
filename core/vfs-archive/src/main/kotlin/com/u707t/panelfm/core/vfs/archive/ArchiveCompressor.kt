package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.OutputStream

/**
 * 压缩：把选中的文件/目录打成 zip 写到目标位置（目标可以是本地，也可以是网络 VFS）。
 * 因为写侧是统一的 [com.u707t.panelfm.core.vfs.VfsWriter]，所以「压缩到网络」天然可用。
 */
class ArchiveCompressor(private val locator: VfsLocator) {

    /** MT 支持的创建格式：zip / 7z / tar / tar.gz / tar.bz2 */
    enum class Format(val id: String, val label: String, val ext: String) {
        ZIP("zip", "ZIP", "zip"),
        SEVEN_Z("7z", "7z", "7z"),
        TAR("tar", "TAR", "tar"),
        TAR_GZ("targz", "tar.gz", "tar.gz"),
        TAR_BZ2("tarbz2", "tar.bz2", "tar.bz2"),
        ;

        companion object {
            fun ofExt(name: String): Format? = entries.firstOrNull { name.lowercase().endsWith(".${it.ext}") }
        }
    }

    suspend fun compress(
        sources: List<VfsUri>,
        destFile: VfsUri,
        format: Format = Format.ZIP,
        onProgress: ProgressCallback? = null,
    ) {
        if (format == Format.SEVEN_Z) {
            // 7z 需要随机访问（先写本地临时文件，再整包回传到目标）
            compressSevenZ(sources, destFile, onProgress)
            return
        }
        val destVfs = locator.find(destFile) ?: throw VfsException.Unsupported("目标不可用")
        val writer = destVfs.openWrite(destFile, size = null, offset = 0L)
        var doneBytes = 0L
        var entries = 0
        try {
            withContext(Dispatchers.IO) {
                val raw = SuspendOutputStream(writer)
                val archive: ArchiveOutputStream<out ArchiveEntry> = when (format) {
                    Format.ZIP -> ZipArchiveOutputStream(raw).apply {
                        setUseZip64(Zip64Mode.AsNeeded)
                        setEncoding("UTF-8")
                    }
                    Format.TAR -> TarArchiveOutputStream(raw).apply { setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX) }
                    Format.TAR_GZ -> TarArchiveOutputStream(GzipCompressorOutputStream(raw))
                        .apply { setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX) }
                    Format.TAR_BZ2 -> TarArchiveOutputStream(BZip2CompressorOutputStream(raw))
                        .apply { setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX) }
                    Format.SEVEN_Z -> error("unreachable")
                }
                archive.use { out ->
                    sources.forEach { source ->
                        val vfs = locator.find(source) ?: return@forEach
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) {
                            entries += addDirectory(out, format, vfs, source, meta.name) { bytes ->
                                doneBytes += bytes
                                onProgress?.onProgress(doneBytes, -1)
                            }
                        } else {
                            addFile(out, format, vfs, source, meta.name, meta.size)
                            doneBytes += meta.size.coerceAtLeast(0)
                            entries++
                        }
                    }
                    out.finish()
                }
            }
            writer.commit()
            Logx.i("ArchiveCompressor", "compressed $entries entries → $destFile")
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw if (e is VfsException) e else VfsException.Io("压缩失败：${e.message}", e)
        }
    }

    private suspend fun compressSevenZ(sources: List<VfsUri>, destFile: VfsUri, onProgress: ProgressCallback?) {
        val destVfs = locator.find(destFile) ?: throw VfsException.Unsupported("目标不可用")
        val tmp = File.createTempFile("panelfm-7z-", ".7z")
        try {
            withContext(Dispatchers.IO) {
                SevenZOutputFile(tmp).use { out ->
                    sources.forEach { source ->
                        val vfs = locator.find(source) ?: return@forEach
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) addSevenZDirectory(out, vfs, source, meta.name, onProgress)
                        else addSevenZFile(out, vfs, source, meta.name)
                    }
                }
            }
            val writer = destVfs.openWrite(destFile, size = tmp.length(), offset = 0L)
            try {
                withContext(Dispatchers.IO) {
                    val buf = ByteArray(256 * 1024)
                    tmp.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            writer.write(buf, 0, n)
                        }
                    }
                }
                writer.commit()
            } catch (e: Exception) {
                runCatching { writer.abort() }
                throw e
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private suspend fun addSevenZFile(
        out: SevenZOutputFile,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
    ) {
        val entry = SevenZArchiveEntry().apply {
            name = entryName
            size = vfs.stat(file).size.coerceAtLeast(0)
            lastModifiedDate = java.util.Date()
        }
        out.putArchiveEntry(entry)
        val reader = vfs.openRead(file)
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { reader.close() }
            out.closeArchiveEntry()
        }
    }

    private suspend fun addSevenZDirectory(
        out: SevenZOutputFile,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        dir: VfsUri,
        prefix: String,
        onProgress: ProgressCallback?,
    ) {
        var bytes = 0L
        vfs.list(dir).forEach { child ->
            val name = "$prefix/${child.name}"
            if (child.isDirectory) addSevenZDirectory(out, vfs, child.uri, name, onProgress)
            else {
                addSevenZFile(out, vfs, child.uri, name)
                bytes += child.size.coerceAtLeast(0)
                onProgress?.onProgress(bytes, -1)
            }
        }
    }

    private suspend fun addDirectory(
        zip: ArchiveOutputStream<out ArchiveEntry>,
        format: Format,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        dir: VfsUri,
        prefix: String,
        onBytes: (Long) -> Unit,
    ): Int {
        var count = 0
        @Suppress("UNCHECKED_CAST")
        (zip as ArchiveOutputStream<ArchiveEntry>).putArchiveEntry(directoryEntry(format, "$prefix/"))
        zip.closeArchiveEntry()
        vfs.list(dir).forEach { child ->
            val name = "$prefix/${child.name}"
            if (child.isDirectory) {
                count += addDirectory(zip, format, vfs, child.uri, name, onBytes)
            } else {
                addFile(zip, format, vfs, child.uri, name, child.size)
                onBytes(child.size.coerceAtLeast(0))
                count++
            }
        }
        return count
    }

    private fun directoryEntry(format: Format, name: String): ArchiveEntry = when (format) {
        Format.ZIP -> ZipArchiveEntry(name).apply { time = System.currentTimeMillis() }
        else -> TarArchiveEntry(name).apply { modTime = java.util.Date() }
    }

    private suspend fun addFile(
        zip: ArchiveOutputStream<out ArchiveEntry>,
        format: Format,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
        size: Long,
    ) {
        val entry: ArchiveEntry = if (format == Format.ZIP) {
            ZipArchiveEntry(entryName).apply { if (size > 0) setSize(size) }
        } else {
            TarArchiveEntry(entryName).apply { setSize(if (size > 0) size else 0L) }
        }
        @Suppress("UNCHECKED_CAST")
        (zip as ArchiveOutputStream<ArchiveEntry>).putArchiveEntry(entry)
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
