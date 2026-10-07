package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
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

        /** 是否支持加密（zip 传统加密 / 7z AES-256） */
        val supportsPassword: Boolean get() = this == ZIP || this == SEVEN_Z
    }

    /**
     * 压缩级别（MT 的「压缩级别」下拉，取自 `0x7f030020` 数组：仅存储→极限压缩）。
     * [deflateLevel] 直接喂给 Deflater；7z 用 [sevenZMethod] 区分「仅存储 / 压缩」。
     */
    enum class Level(val label: String, val deflateLevel: Int, val store: Boolean = false) {
        STORE("仅存储", 0, store = true),
        FASTEST("极速压缩", 1),
        FAST("快速压缩", 3),
        NORMAL("标准压缩", 6),
        MAXIMUM("最大压缩", 9),
        ULTRA("极限压缩", 9),
        ;
    }

    /**
     * 压缩。
     *
     * @param level    压缩级别（仅存储 / 极速…极限）；tar 系忽略
     * @param password 口令；空 = 不加密。zip 走传统加密（[ZipCrypto]），7z 走 AES-256。
     *        注意（第 5 批实测修正）：commons-compress 1.27.1 的 7z 写侧**不加密文件头**，
     *        条目名始终可见 —— 本实现无法提供 MT 的「同时加密文件名」。
     */
    suspend fun compress(
        sources: List<VfsUri>,
        destFile: VfsUri,
        format: Format = Format.ZIP,
        onProgress: ProgressCallback? = null,
        level: Level = Level.NORMAL,
        password: String? = null,
    ) {
        val pwd = password?.takeIf { it.isNotEmpty() }
        if (pwd != null && !format.supportsPassword) {
            throw VfsException.Unsupported("${format.label} 不支持加密（仅 ZIP / 7z 支持）")
        }
        if (format == Format.SEVEN_Z) {
            // 7z 需要随机访问（先写本地临时文件，再整包回传到目标）
            compressSevenZ(sources, destFile, onProgress, level, pwd)
            return
        }
        if (format == Format.ZIP && pwd != null) {
            // 加密 ZIP 走 [EncryptedZipWriter]：commons-compress 的 addRawArchiveEntry
            // 见到 encryption 标志就抛 UnsupportedZipFeatureException，根本写不出来。
            compressEncryptedZip(sources, destFile, onProgress, level, pwd)
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
                        if (level.store) setMethod(ZipArchiveOutputStream.STORED) else setLevel(level.deflateLevel)
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
                        val vfs = locator.find(source)
                            ?: throw VfsException.Unsupported("源位置不可用（会话可能已断开）：${source.name}")
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) {
                            entries += addDirectory(out, format, vfs, source, meta.name) { bytes ->
                                doneBytes += bytes
                                onProgress?.onProgress(doneBytes, -1)
                            }
                        } else {
                            // 逐块上报：单个大文件也能看见进度、中途取消
                            addFile(out, format, vfs, source, meta.name, meta.size) { n ->
                                doneBytes += n
                                onProgress?.onProgress(doneBytes, -1)
                            }
                            entries++
                        }
                    }
                    out.finish()
                }
            }
            writer.commit()
            Logx.i(
                "ArchiveCompressor",
                "compressed $entries entries → $destFile (level=${level.label}, encrypted=${pwd != null})",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消（长操作状态条的「取消」）：半成品交给 abort() 清掉，CancellationException 原样上抛。
            // 旧实现会把它包成「压缩失败：Job was cancelled」——与审计 U5 的教训一致，取消不是失败。
            runCatching { writer.abort() }
            throw e
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw if (e is VfsException) e else VfsException.Io("压缩失败：${e.message}", e)
        }
    }

    private suspend fun compressSevenZ(
        sources: List<VfsUri>,
        destFile: VfsUri,
        onProgress: ProgressCallback?,
        level: Level,
        password: String?,
    ) {
        val destVfs = locator.find(destFile) ?: throw VfsException.Unsupported("目标不可用")
        val tmp = File.createTempFile("panelfm-7z-", ".7z")
        try {
            withContext(Dispatchers.IO) {
                val out = if (password != null) {
                    SevenZOutputFile(tmp, password.toCharArray())
                } else {
                    SevenZOutputFile(tmp)
                }
                out.use { sevenZ ->
                    // 级别：仅存储 → COPY；其余用 7z 默认（LZMA2）
                    if (level.store) sevenZ.setContentCompression(SevenZMethod.COPY)
                    // 口令已在构造器给出：commons-compress 用 AES-256 加密**内容**。
                    // （第 5 批实测修正：1.27.1 的 SevenZOutputFile 不加密文件头 —— 无口令也能
                    //   列出条目名；「同时加密文件名」在本库无法实现，旧注释「默认加密文件头」不成立。）
                    sources.forEach { source ->
                        val vfs = locator.find(source)
                            ?: throw VfsException.Unsupported("源位置不可用（会话可能已断开）：${source.name}")
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) addSevenZDirectory(sevenZ, vfs, source, meta.name, onProgress)
                        else addSevenZFile(sevenZ, vfs, source, meta.name)
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
                // 逐块上报（累计由 onBytes 的调用方维护）；单文件也能在中途取消
                addFile(zip, format, vfs, child.uri, name, child.size) { n -> onBytes(n) }
                count++
            }
        }
        return count
    }

    private fun directoryEntry(format: Format, name: String): ArchiveEntry = when (format) {
        Format.ZIP -> ZipArchiveEntry(name).apply { time = System.currentTimeMillis() }
        else -> TarArchiveEntry(name).apply { modTime = java.util.Date() }
    }

    /** 写一个文件条目（非加密路径；加密 ZIP 见 [compressEncryptedZip]）。[onChunk] = 每写入 n 字节回调一次（累计由调用方维护）。 */
    private suspend fun addFile(
        zip: ArchiveOutputStream<out ArchiveEntry>,
        format: Format,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
        size: Long,
        onChunk: ((Long) -> Unit)? = null,
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
                onChunk?.invoke(n.toLong())
            }
        } finally {
            runCatching { reader.close() }
            zip.closeArchiveEntry()
        }
    }

    /**
     * 加密 ZIP（ZipCrypto）：整包交给 [EncryptedZipWriter] 手写容器格式。
     *
     * 为什么不能复用 [compress] 的 ZipArchiveOutputStream：commons-compress 的
     * `addRawArchiveEntry` 内部会做 `checkRequestedFeatures`，只要 entry 带 encryption 标志就抛
     * `UnsupportedZipFeatureException`；而 `putArchiveEntry` 又不知道 ZipCrypto 会多出 12 字节加密头，
     * 会把 compressedSize 算错。所以加密 ZIP 必须整包走自研写侧。
     *
     * 目录条目不加密（与主流实现一致）；文件按 [Level] 决定 STORED / DEFLATE。
     */
    private suspend fun compressEncryptedZip(
        sources: List<VfsUri>,
        destFile: VfsUri,
        onProgress: ProgressCallback?,
        level: Level,
        password: String,
    ) {
        val destVfs = locator.find(destFile) ?: throw VfsException.Unsupported("目标不可用")
        val writer = destVfs.openWrite(destFile, size = null, offset = 0L)
        var doneBytes = 0L
        var entries = 0
        try {
            withContext(Dispatchers.IO) {
                val zip = EncryptedZipWriter(
                    out = SuspendOutputStream(writer),
                    password = password,
                    deflateLevel = level.deflateLevel,
                    store = level.store,
                )
                suspend fun addOne(vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem, uri: VfsUri, name: String) {
                    val meta = vfs.stat(uri)
                    if (meta.isDirectory) {
                        zip.putDirectory(name, System.currentTimeMillis())
                        entries++
                        vfs.list(uri).forEach { child ->
                            addOne(vfs, child.uri, "$name/${child.name}")
                        }
                    } else {
                        val reader = vfs.openRead(uri)
                        try {
                            zip.putFileStreaming(
                                name = name,
                                epochMillis = meta.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis(),
                                uncompressedSize = meta.size.coerceAtLeast(0),
                            ) { buf ->
                                // 阻塞式读取（已在本模块的 IO 上下文里）
                                val n = kotlinx.coroutines.runBlocking { reader.read(buf, 0, buf.size) }
                                if (n > 0) {
                                    // 逐块上报：大文件中途也能取消 / 看见进度
                                    doneBytes += n
                                    onProgress?.onProgress(doneBytes, -1)
                                }
                                if (n <= 0) -1 else n
                            }
                        } finally {
                            runCatching { reader.close() }
                        }
                        entries++
                    }
                }
                sources.forEach { source ->
                    val vfs = locator.find(source)
                        ?: throw VfsException.Unsupported("源位置不可用（会话可能已断开）：${source.name}")
                    addOne(vfs, source, vfs.stat(source).name)
                }
                zip.finish()
            }
            writer.commit()
            Logx.i(
                "ArchiveCompressor",
                "compressed $entries entries → $destFile (level=${level.label}, encrypted=true, zipcrypto)",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { writer.abort() }
            throw e
        } catch (e: Exception) {
            runCatching { writer.abort() }
            throw if (e is VfsException) e else VfsException.Io("压缩失败：${e.message}", e)
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
    }
}
