package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

class ArchiveDuplicateEntryTest {
    private fun env(dir: File) = VfsEnv(AppDirs(dir.path, dir.path), PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default))
    private val entries = listOf("../bad" to "BAD", "dup.txt" to "OLD", "dup.txt" to "NEW")

    private fun make(dir: File, kind: ArchiveVfs.ArchiveKind): File {
        val file = File(dir, "duplicate.${kind.id}")
        when (kind) {
            ArchiveVfs.ArchiveKind.AR -> ArArchiveOutputStream(file.outputStream()).use { out ->
                entries.drop(1).forEach { (name, text) ->
                    out.putArchiveEntry(ArArchiveEntry(name, 3))
                    out.write(text.toByteArray()); out.closeArchiveEntry()
                }
            }
            ArchiveVfs.ArchiveKind.CPIO -> {
                CpioArchiveOutputStream(file.outputStream()).use { out ->
                    listOf("one.txt" to "OLD", "two.txt" to "NEW").forEach { (name, text) ->
                        out.putArchiveEntry(CpioArchiveEntry(CpioConstants.FORMAT_NEW, name, 3).apply { mode = 0x81a4 })
                        out.write(text.toByteArray()); out.closeArchiveEntry()
                    }
                }
                // newc has no content checksum; same-length filename patch creates a valid duplicate archive.
                file.writeBytes(file.readBytes().toString(Charsets.ISO_8859_1)
                    .replace("one.txt", "dup.txt").replace("two.txt", "dup.txt").toByteArray(Charsets.ISO_8859_1))
            }
            else -> {
                val raw: OutputStream = file.outputStream()
                val stream = when (kind) {
                    ArchiveVfs.ArchiveKind.TAR_GZ -> GzipCompressorOutputStream(raw)
                    ArchiveVfs.ArchiveKind.TAR_BZ2 -> BZip2CompressorOutputStream(raw)
                    ArchiveVfs.ArchiveKind.TAR_XZ -> XZCompressorOutputStream(raw)
                    else -> raw
                }
                TarArchiveOutputStream(stream).use { out ->
                    entries.forEach { (name, text) ->
                        out.putArchiveEntry(TarArchiveEntry(name).apply { size = 3 })
                        out.write(text.toByteArray()); out.closeArchiveEntry()
                    }
                }
            }
        }
        return file
    }

    @Test fun `同名同尺寸顺序条目列出的最后一项必须读到最后内容`() = runBlocking {
        val dir = Files.createTempDirectory("ordinal").toFile()
        val kinds = listOf(ArchiveVfs.ArchiveKind.TAR, ArchiveVfs.ArchiveKind.TAR_GZ,
            ArchiveVfs.ArchiveKind.TAR_BZ2, ArchiveVfs.ArchiveKind.TAR_XZ,
            ArchiveVfs.ArchiveKind.AR, ArchiveVfs.ArchiveKind.CPIO)
        try {
            for (kind in kinds) {
                val file = make(dir, kind)
                val host = VfsUri.of("sftp", "nas:22", "/${file.name}", "c=42")
                val vfs = ArchiveVfs(host, kind, file, env(dir))
                try {
                    vfs.connect()
                    val root = VfsUri.parse(ArchiveVfs.uriFor(host, kind).toString())
                    assertEquals("${kind.id} list", 1, vfs.list(root).size)
                    val uri = root.child("dup.txt")
                    assertEquals(3L, vfs.stat(uri).size)
                    val reader = vfs.openRead(uri)
                    val buf = ByteArray(3)
                    try {
                        assertEquals(3, reader.read(buf, 0, 3))
                        assertEquals("${kind.id} read", "NEW", buf.toString(Charsets.UTF_8))
                    } finally { reader.close() }
                    assertEquals("${kind.id} raw entry", "NEW", vfs.openEntryStream("dup.txt").use { it.readBytes().toString(Charsets.UTF_8) })
                } finally { vfs.close() }
            }
        } finally { dir.deleteRecursively() }
    }
}
