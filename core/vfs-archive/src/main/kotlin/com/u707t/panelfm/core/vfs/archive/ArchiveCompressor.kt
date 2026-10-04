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

        companion object {
            fun ofExt(name: String): Format? = entries.firstOrNull { name.lowercase().endsWith(".${it.ext}") }
        }
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

        companion object {
            fun ofLabel(label: String): Level = entries.firstOrNull { it.label == label } ?: NORMAL
        }
    }

    /**
     * 压缩。
     *
     * @param level    压缩级别（仅存储 / 极速…极限）；tar 系忽略
     * @param password 口令；空 = 不加密。zip 走传统加密（[ZipCrypto]），7z 走 AES-256
     * @param encryptNames 「同时加密文件名」（MT 勾选项）。ZIP 传统加密**无法**加密文件名，
     *        这里只在 7z 下生效（7z 的头加密会一并隐藏文件名）；zip 请求该选项时忽略并保持数据加密。
     */
    suspend fun compress(
        sources: List<VfsUri>,
        destFile: VfsUri,
        format: Format = Format.ZIP,
        onProgress: ProgressCallback? = null,
        level: Level = Level.NORMAL,
        password: String? = null,
        encryptNames: Boolean = false,
    ) {
        val pwd = password?.takeIf { it.isNotEmpty() }
        if (pwd != null && !format.supportsPassword) {
            throw VfsException.Unsupported("${format.label} 不支持加密（仅 ZIP / 7z 支持）")
        }
        if (format == Format.SEVEN_Z) {
            // 7z 需要随机访问（先写本地临时文件，再整包回传到目标）
            compressSevenZ(sources, destFile, onProgress, level, pwd, encryptNames)
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
                    val zipKeys = pwd?.let { ZipCrypto.Keys(it) }
                    sources.forEach { source ->
                        val vfs = locator.find(source) ?: return@forEach
                        val meta = vfs.stat(source)
                        if (meta.isDirectory) {
                            entries += addDirectory(out, format, vfs, source, meta.name, zipKeys) { bytes ->
                                doneBytes += bytes
                                onProgress?.onProgress(doneBytes, -1)
                            }
                        } else {
                            addFile(out, format, vfs, source, meta.name, meta.size, zipKeys)
                            doneBytes += meta.size.coerceAtLeast(0)
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
        encryptNames: Boolean,
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
                    // 口令已在构造器给出：commons-compress 会用 AES-256 加密内容，
                    // 并默认加密文件头（等价于 MT 的「同时加密文件名」）。
                    sources.forEach { source ->
                        val vfs = locator.find(source) ?: return@forEach
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
        keys: ZipCrypto.Keys?,
        onBytes: (Long) -> Unit,
    ): Int {
        var count = 0
        @Suppress("UNCHECKED_CAST")
        (zip as ArchiveOutputStream<ArchiveEntry>).putArchiveEntry(directoryEntry(format, "$prefix/"))
        zip.closeArchiveEntry()
        vfs.list(dir).forEach { child ->
            val name = "$prefix/${child.name}"
            if (child.isDirectory) {
                count += addDirectory(zip, format, vfs, child.uri, name, keys, onBytes)
            } else {
                addFile(zip, format, vfs, child.uri, name, child.size, keys)
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

    /**
     * 写一个文件条目。
     *
     * 加密路径（[keys] 非空，仅 ZIP）必须绕开 `ZipArchiveOutputStream.putArchiveEntry`：
     * 它不知道 ZipCrypto 会多出 12 字节加密头，会把 compressedSize 算错。
     * 所以加密时**自己压缩**（Deflater），把 `compressedSize = 12 + 压缩后长度`、
     * `crc`、`method`、`generalPurposeBit(encryption)` 都填好，再走 `addRawArchiveEntry`
     * （raw = 数据已就绪，commons-compress 原样搬运，不做二次压缩）。
     */
    private suspend fun addFile(
        zip: ArchiveOutputStream<out ArchiveEntry>,
        format: Format,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
        size: Long,
        keys: ZipCrypto.Keys?,
    ) {
        if (format == Format.ZIP && keys != null) {
            addEncryptedZipFile(zip, vfs, file, entryName, keys)
            return
        }
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

    /**
     * 加密 ZIP 条目：本地读完 → 压缩（Deflater）→ ZipCrypto 加密（头 12 字节 + 数据）→ raw 写入。
     * 文件较大时全程在内存里过一遍压缩结果；单个文件通常可控（配合调用方的体积提示）。
     */
    private suspend fun addEncryptedZipFile(
        zip: ArchiveOutputStream<out ArchiveEntry>,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        file: VfsUri,
        entryName: String,
        keys: ZipCrypto.Keys,
    ) {
        // ① 读原始数据 + CRC
        val rawBytes = java.io.ByteArrayOutputStream()
        val crc = java.util.zip.CRC32()
        val reader = vfs.openRead(file)
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                crc.update(buf, 0, n)
                rawBytes.write(buf, 0, n)
            }
        } finally {
            runCatching { reader.close() }
        }
        val raw = rawBytes.toByteArray()
        val crcValue = crc.value

        // ② Deflate 压缩
        val compressed = java.io.ByteArrayOutputStream()
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
        try {
            val def = java.util.zip.DeflaterOutputStream(compressed, deflater)
            def.write(raw)
            def.finish()
        } finally {
            deflater.end()
        }
        val deflated = compressed.toByteArray()

        // ③ ZipCrypto：12 字节加密头 + 加密数据（连续加密，密钥状态跨头与数据）
        val payload = ByteArray(ZipCrypto.HEADER_LENGTH + deflated.size)
        val header = ZipCrypto.encryptHeader(keys, ZipCrypto.checkByteFor(crcValue))
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(deflated, 0, payload, header.size, deflated.size)
        keys.encrypt(payload, header.size, deflated.size)

        // ④ 元信息：compressedSize 必须含 12 字节头；crc 用**明文**的 CRC
        val entry = ZipArchiveEntry(entryName).apply {
            setSize(raw.size.toLong())
            compressedSize = payload.size.toLong()
            this.crc = crcValue
            method = ZipArchiveEntry.DEFLATED
            generalPurposeBit.useEncryption(true)
            time = System.currentTimeMillis()
        }

        @Suppress("UNCHECKED_CAST")
        (zip as ZipArchiveOutputStream).addRawArchiveEntry(entry, java.io.ByteArrayInputStream(payload))
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
