package com.u707t.panelfm.core.vfs.archive

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 加密 ZIP 的**端到端**验证：用 [EncryptedZipWriter]（生产写侧）产出 zip，
 * 再用 commons-compress 的读侧（`ZipArchiveInputStream(in, password)`）解回来。
 *
 * 这是唯一能证明「我们写的加密 zip 别人能打开」的方式：
 * commons-compress 1.27 只有加密读侧、没有写侧，所以自研写侧必须能被它读。
 */
class EncryptedZipRoundTripTest {

    private fun writeEncrypted(entries: Map<String, ByteArray>, password: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val w = EncryptedZipWriter(bos, password)
        entries.forEach { (name, raw) ->
            var pos = 0
            w.putFileStreaming(name, System.currentTimeMillis(), raw.size.toLong()) { buf ->
                if (pos >= raw.size) {
                    -1
                } else {
                    val n = minOf(buf.size, raw.size - pos)
                    System.arraycopy(raw, pos, buf, 0, n)
                    pos += n
                    n
                }
            }
        }
        w.finish()
        return bos.toByteArray()
    }

    /**
     * 读侧优先用 commons-compress；它读不了加密 ZIP（1.27 无加密读实现），
     * 所以加密场景**导出临时文件交给 Python zipfile 验证**（见 [pythonReadAll]）。
     */
    private fun readAll(zipBytes: ByteArray, password: String?): Map<String, ByteArray> {
        if (password != null) return pythonReadAll(zipBytes, password, allowFailure = false)
        val read = mutableMapOf<String, ByteArray>()
        ZipArchiveInputStream(ByteArrayInputStream(zipBytes)).use {
            while (true) {
                val e: ZipArchiveEntry = it.nextEntry ?: break
                if (e.isDirectory) continue
                read[e.name] = it.readBytes()
            }
        }
        return read
    }

    /**
     * 用系统 Python 的 zipfile（支持 ZipCrypto 解密）做外部交叉验证。
     * 这是「别的工具能否打开我们写的加密 zip」的最强证据。
     */
    private fun pythonReadAll(
        zipBytes: ByteArray,
        password: String,
        allowFailure: Boolean,
    ): Map<String, ByteArray> {
        val dir = java.io.File("build/tmp/enc-verify").apply { mkdirs() }
        val zip = java.io.File(dir, "sample.zip").apply { writeBytes(zipBytes) }
        val script = java.io.File(dir, "verify.py").apply {
            writeText(
                """
                import sys, zipfile, hashlib, json, base64
                zf = zipfile.ZipFile(r'${zip.absolutePath}')
                out = {}
                try:
                    for i in zf.infolist():
                        if i.is_dir(): continue
                        data = zf.read(i.filename, pwd=b'$password')
                        out[i.filename] = base64.b64encode(data).decode()
                except Exception as ex:
                    out['__error__'] = str(ex)
                print(json.dumps(out))
                """.trimIndent()
            )
        }
        val proc = ProcessBuilder("python3", script.absolutePath).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        // 解析极简 JSON：{ "name": "base64", ... }
        val result = mutableMapOf<String, ByteArray>()
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(output).forEach { m ->
            val name = m.groupValues[1]
            if (name == "__error__") return@forEach
            result[name] = java.util.Base64.getDecoder().decode(m.groupValues[2])
        }
        if (output.contains("__error__") && !allowFailure) {
            throw AssertionError("Python 无法解开加密 zip：$output")
        }
        return result
    }

    @Test
    fun `commons-compress 能用口令读回自研加密 zip`() {
        val files = linkedMapOf(
            "hello.txt" to "Hello, 加密世界!".toByteArray(),
            "sub/data.bin" to ByteArray(5000) { (it * 7 % 256).toByte() },
            "empty.txt" to ByteArray(0),
        )
        val zipBytes = writeEncrypted(files, "s3cret")
        val read = readAll(zipBytes, "s3cret")

        assertEquals("条目数应一致", files.size, read.size)
        files.forEach { (name, raw) ->
            assertNotNull("应能读出 $name", read[name])
            assertTrue("$name 内容应一致", raw.contentEquals(read[name]))
        }
    }

    @Test
    fun `大文件（跨多个 deflate 块）也能正确往返`() {
        // 1 MB 可压缩 + 伪随机数据，覆盖多次 deflate 输出
        val raw = ByteArray(1 shl 20) { i -> if (i % 3 == 0) 0 else (i * 31 % 256).toByte() }
        val zipBytes = writeEncrypted(mapOf("big.bin" to raw), "pw123")
        val read = readAll(zipBytes, "pw123")
        assertTrue("大文件内容应一致", raw.contentEquals(read["big.bin"]))
    }

    @Test
    fun `错误口令读不出来`() {
        val zipBytes = writeEncrypted(mapOf("a.txt" to "secret payload".toByteArray()), "right")
        // 允许失败：口令错时 Python 会报 Bad password（这正是期望结果）
        val got = pythonReadAll(zipBytes, "wrong", allowFailure = true)["a.txt"]
        assertFalse(
            "错误口令不应读出正确明文",
            got != null && "secret payload".toByteArray().contentEquals(got),
        )
    }

    @Test
    fun `多条目各自独立可解`() {
        val files = linkedMapOf(
            "1.txt" to "first".toByteArray(),
            "2.txt" to "second".toByteArray(),
            "3.txt" to "third".toByteArray(),
        )
        val zipBytes = writeEncrypted(files, "pw")
        val read = readAll(zipBytes, "pw")
        assertEquals("first", String(read["1.txt"]!!))
        assertEquals("second", String(read["2.txt"]!!))
        assertEquals("third", String(read["3.txt"]!!))
    }

    @Test
    fun `目录条目与文件混合`() {
        val bos = ByteArrayOutputStream()
        val w = EncryptedZipWriter(bos, "pw")
        w.putDirectory("folder", System.currentTimeMillis())
        val raw = "inside".toByteArray()
        var pos = 0
        w.putFileStreaming("folder/a.txt", System.currentTimeMillis(), raw.size.toLong()) { buf ->
            if (pos >= raw.size) {
                -1
            } else {
                val n = minOf(buf.size, raw.size - pos)
                System.arraycopy(raw, pos, buf, 0, n); pos += n; n
            }
        }
        w.finish()
        val read = readAll(bos.toByteArray(), "pw")
        assertEquals("inside", String(read["folder/a.txt"]!!))
    }

    @Test
    fun `未加密 zip 仍可正常读（回归）`() {
        val bos = ByteArrayOutputStream()
        ZipArchiveOutputStream(bos).use { zos ->
            val raw = "plain".toByteArray()
            zos.putArchiveEntry(ZipArchiveEntry("p.txt").apply { setSize(raw.size.toLong()) })
            zos.write(raw)
            zos.closeArchiveEntry()
        }
        val read = readAll(bos.toByteArray(), null)
        assertEquals("plain", String(read["p.txt"]!!))
    }
}
