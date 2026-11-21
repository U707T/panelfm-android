package com.u707t.panelfm.ui.connections

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Writes one imported key to a private temp file and atomically publishes a unique name. */
internal fun importPrivateKeyAtomically(
    input: InputStream,
    keysDir: File,
    rawName: String,
    maxBytes: Long = 16L * 1024 * 1024,
): File {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (!keysDir.exists() && !keysDir.mkdirs()) throw IOException("无法创建私钥目录")
    if (!keysDir.isDirectory) throw IOException("私钥目录不可用")

    val basename = File(rawName.replace('\\', '/').substringAfterLast('/'))
        .name
        .replace(Regex("[\\x00-\\x1F<>:\"|?*]"), "_")
        .trim()
        .trimEnd('.')
        .take(100)
        .takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: "id_key"
    val dot = basename.lastIndexOf('.').takeIf { it > 0 } ?: basename.length
    val stem = basename.substring(0, dot).ifBlank { "id_key" }
    val extension = if (dot < basename.length) basename.substring(dot).take(16) else ""
    val destination = File(keysDir, "$stem-panel-${UUID.randomUUID()}$extension")
    val temp = File.createTempFile("key-import-", ".part", keysDir)
    var published = false
    try {
        var total = 0L
        input.use { source ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(16 * 1024)
                var emptyReads = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        if (++emptyReads >= 3) throw IOException("来源应用连续返回空数据")
                        continue
                    }
                    emptyReads = 0
                    total += count
                    if (total > maxBytes) throw IOException("私钥文件超过 ${maxBytes} 字节上限")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("私钥文件为空")
                output.flush()
                output.fd.sync()
            }
        }
        // Keep imported key files private to this app before they become visible to the form.
        if (!temp.setReadable(false, false) || !temp.setWritable(false, false) ||
            !temp.setReadable(true, true) || !temp.setWritable(true, true)
        ) {
            throw IOException("无法设置私钥文件权限")
        }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-directory move remains non-replacing; destination includes a random 128-bit suffix.
            Files.move(temp.toPath(), destination.toPath())
        }
        published = true
        return destination
    } catch (t: Throwable) {
        runCatching { temp.delete() }
        if (published) runCatching { destination.delete() }
        throw t
    } finally {
        runCatching { temp.delete() }
    }
}
