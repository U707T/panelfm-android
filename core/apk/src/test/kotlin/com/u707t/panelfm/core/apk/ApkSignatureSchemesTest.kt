package com.u707t.panelfm.core.apk

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 签名方案检测（MT「签名状态 V1 + V2 + V3」）：
 *  - V1 = `META-INF` 下的 `.SF` 与 `.RSA`/`.DSA`/`.EC` 同时存在
 *  - V2/V3/V3.1 = 中央目录前的 APK Signing Block 里有对应 ID 的块
 *  - 读不出来（不是 zip / 截断）不抛异常，按「未签名」显示
 *
 * 这里用**手工拼的 zip**（含伪造的签名块）验证结构解析，不依赖真实签名的包 ——
 * 检测只看结构，构造出来的块已经足够覆盖「有 / 无 / 组合」三种情况。
 */
class ApkSignatureSchemesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val v2Id = 0x7109871aL
    private val v3Id = 0xf05368c0L
    private val v31Id = 0x1b93ad61L

    // ---------------------------------------------------------------- 夹具

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun fileOf(bytes: ByteArray, name: String = "app.apk"): File =
        File(tmp.newFolder("d-${System.nanoTime()}"), name).apply { writeBytes(bytes) }

    /** 在中央目录前插入 APK Signing Block（结构见 AOSP apksig 的 APK Signature Scheme v2） */
    private fun withSigningBlock(zip: ByteArray, ids: List<Long>): ByteArray {
        val eocd = findEocd(zip)
        val cdOffset = readInt(zip, eocd + 16)

        val pairs = ByteArrayOutputStream()
        ids.forEach { id ->
            val value = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            pairs.write(u64((4 + value.size).toLong()))
            pairs.write(u32(id))
            pairs.write(value)
        }
        // size 字段 = 键值对 + 尾部 size(8) + magic(16)
        val blockSize = pairs.size() + 8 + 16
        val block = ByteArrayOutputStream().apply {
            write(u64(blockSize.toLong()))
            write(pairs.toByteArray())
            write(u64(blockSize.toLong()))
            write("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        }.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(zip, 0, cdOffset)
        out.write(block)
        out.write(zip, cdOffset, zip.size - cdOffset)
        val bytes = out.toByteArray()
        // 中央目录偏移要跟着块的插入量一起后移（EOCD 本身也后移了 block.size）
        writeInt(bytes, eocd + block.size + 16, cdOffset + block.size)
        return bytes
    }

    private fun findEocd(bytes: ByteArray): Int {
        var i = bytes.size - 22
        while (i >= 0) {
            if (readInt(bytes, i) == 0x06054b50) return i
            i--
        }
        throw IllegalStateException("测试 zip 没有 EOCD")
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u32(v: Long): ByteArray = ByteArray(4) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u64(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun v1Entries(): Map<String, ByteArray> = mapOf(
        "AndroidManifest.xml" to ByteArray(32) { 7 },
        "classes.dex" to ByteArray(64) { 1 },
        "META-INF/MANIFEST.MF" to ByteArray(16),
        "META-INF/CERT.SF" to ByteArray(16),
        "META-INF/CERT.RSA" to ByteArray(16),
    )

    // ---------------------------------------------------------------- 用例

    @Test
    fun `普通 zip 没有签名块也没有 META-INF 签名 = 未签名`() {
        val file = fileOf(zipBytes(mapOf("AndroidManifest.xml" to ByteArray(16))))
        val schemes = ApkSignatures.detect(file)
        assertFalse(schemes.v1)
        assertFalse(schemes.v2)
        assertFalse(schemes.v3)
        assertEquals("未签名", schemes.label())
        assertEquals(SignatureSchemes.UNSIGNED, schemes.label())
    }

    @Test
    fun `只有 SF 没有签名块文件不算 V1`() {
        val file = fileOf(
            zipBytes(
                mapOf(
                    "META-INF/CERT.SF" to ByteArray(16),
                    "classes.dex" to ByteArray(8),
                ),
            ),
        )
        assertFalse("只有 .SF 不算 V1", ApkSignatures.detect(file).v1)
    }

    @Test
    fun `JAR 签名（SF + RSA）= 只有 V1`() {
        val file = fileOf(zipBytes(v1Entries()))
        val schemes = ApkSignatures.detect(file)
        assertTrue(schemes.v1)
        assertFalse(schemes.v2)
        assertFalse(schemes.v3)
        assertEquals("V1", schemes.label())
    }

    @Test
    fun `签名块里的 V2 与 V3 块都能识别`() {
        val file = fileOf(withSigningBlock(zipBytes(mapOf("classes.dex" to ByteArray(8))), listOf(v2Id, v3Id)))
        val schemes = ApkSignatures.detect(file)
        assertFalse(schemes.v1)
        assertTrue(schemes.v2)
        assertTrue(schemes.v3)
        assertEquals("V2 + V3", schemes.label())
    }

    @Test
    fun `V1 与 V2 V3 同时存在 = V1 + V2 + V3（MT 截图里的常见组合）`() {
        val file = fileOf(withSigningBlock(zipBytes(v1Entries()), listOf(v2Id, v3Id)))
        assertEquals("V1 + V2 + V3", ApkSignatures.detect(file).label())
    }

    @Test
    fun `V3_1 单独列出`() {
        val file = fileOf(withSigningBlock(zipBytes(v1Entries()), listOf(v2Id, v3Id, v31Id)))
        val schemes = ApkSignatures.detect(file)
        assertTrue(schemes.v31)
        assertEquals("V1 + V2 + V3 + V3.1", schemes.label())
    }

    @Test
    fun `签名块里出现未知块不影响结论`() {
        val file = fileOf(withSigningBlock(zipBytes(v1Entries()), listOf(0x1234L, v2Id)))
        assertEquals("V1 + V2", ApkSignatures.detect(file).label())
    }

    @Test
    fun `不是 zip 的文件不抛异常，按未签名处理`() {
        val file = fileOf(ByteArray(5000) { (it % 251).toByte() })
        assertEquals("未签名", ApkSignatures.detect(file).label())
    }

    @Test
    fun `空文件与超短文件也不抛异常`() {
        assertEquals("未签名", ApkSignatures.detect(fileOf(ByteArray(0), "empty.apk")).label())
        assertEquals("未签名", ApkSignatures.detect(fileOf(ByteArray(10), "tiny.apk")).label())
    }
}
