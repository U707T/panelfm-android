package com.u707t.panelfm.core.apk

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxmlTest {

    @Test
    fun `不改任何东西时序列化与原文件逐字节相同`() {
        val original = Fixtures.baseManifest
        val out = AxmlFile.parse(original).toBytes()
        assertTrue(original.contentEquals(out))
    }

    @Test
    fun `能读出 base 清单的关键字段`() {
        val info = ApkManifestReader.parse(Fixtures.baseManifest)
        assertEquals("com.example.app", info.packageName)
        assertEquals("2.1", info.versionName)
        assertEquals(42L, info.versionCode)
        assertEquals(24, info.minSdk)
        assertNull(info.splitName)
        assertTrue(info.isBase)
        assertTrue(info.isSplitRequired)
        assertEquals("feature1:foo", info.requiredSplitTypes)
        assertEquals(listOf("feature1"), info.usesSplits)
        assertEquals(false, info.extractNativeLibs)
    }

    @Test
    fun `能认出差分包的 split 与 isFeatureSplit`() {
        val abi = ApkManifestReader.parse(Fixtures.abiManifest)
        assertEquals("config.arm64_v8a", abi.splitName)
        assertFalse(abi.isBase)
        assertEquals(SplitKind.ABI, SplitKind.of(abi.splitName, abi.isFeatureSplit))
        assertEquals("arm64-v8a", SplitKind.abiFolder(abi.splitName))

        val dpi = ApkManifestReader.parse(Fixtures.densityManifest)
        assertEquals(SplitKind.DENSITY, SplitKind.of(dpi.splitName, dpi.isFeatureSplit))

        val feature = ApkManifestReader.parse(Fixtures.featureManifest)
        assertEquals("feature1", feature.splitName)
        assertTrue(feature.isFeatureSplit)
        assertEquals(SplitKind.FEATURE, SplitKind.of(feature.splitName, feature.isFeatureSplit))
    }

    @Test
    fun `转单 APK 的补丁会去掉 isSplitRequired requiredSplitTypes 和 uses-split`() {
        val patched = ApkManifestPatcher.patchForStandalone(Fixtures.baseManifest)
        assertTrue(patched.changes.any { it.contains("isSplitRequired") })
        assertTrue(patched.changes.any { it.contains("requiredSplitTypes") })
        assertTrue(patched.changes.any { it.contains("uses-split") })

        val info = ApkManifestReader.parse(patched.bytes)
        assertFalse(info.isSplitRequired)
        assertNull(info.requiredSplitTypes)
        assertTrue(info.usesSplits.isEmpty())

        val xml = AxmlFile.parse(patched.bytes)
        assertNull(xml.firstElement("uses-split"))
        assertNotNull(xml.firstElement("x-uses-split"))

        // 其它字段必须原样保留
        assertEquals("com.example.app", info.packageName)
        assertEquals(42L, info.versionCode)
        assertEquals("2.1", info.versionName)
        assertEquals(24, info.minSdk)
        assertEquals(false, info.extractNativeLibs)
    }

    @Test
    fun `UTF-8 字符串池也能读改（补丁走字符串池重建）`() {
        val bytes = buildUtf8Axml()
        val before = ApkManifestReader.parse(bytes)
        assertEquals("com.example.utf8", before.packageName)
        assertTrue(before.isSplitRequired)
        assertEquals(listOf("feature1"), before.usesSplits)

        val patched = ApkManifestPatcher.patchForStandalone(bytes)
        val after = ApkManifestReader.parse(patched.bytes)
        assertEquals("com.example.utf8", after.packageName)
        assertFalse(after.isSplitRequired)
        assertTrue(after.usesSplits.isEmpty())
        assertNull(AxmlFile.parse(patched.bytes).firstElement("uses-split"))
    }

    /**
     * 手搓一份 UTF-8 池的 AXML：aapt2 默认写 UTF-16，UTF-8 池只能自己造，
     * 否则「池重建」那条分支永远测不到（真实 APK 里两种都存在）。
     */
    private fun buildUtf8Axml(): ByteArray {
        val strings = listOf(
            "manifest", "package", "com.example.utf8", "isSplitRequired", "uses-split", "name", "feature1",
        )
        val pool = utf8Pool(strings)

        val manifest = startElement(
            nameIndex = 0,
            attrs = listOf(
                attr(nameIndex = 1, stringIndex = 2),
                attr(nameIndex = 3, boolean = true),
            ),
        )
        val usesSplit = startElement(nameIndex = 4, attrs = listOf(attr(nameIndex = 5, stringIndex = 6)))
        val endUsesSplit = endElement(4)
        val endManifest = endElement(0)

        val body = pool + manifest + usesSplit + endUsesSplit + endManifest
        val header = ByteArray(8)
        putU16(header, 0, 0x0003)
        putU16(header, 2, 8)
        putU32(header, 4, 8 + body.size)
        return header + body
    }

    private fun utf8Pool(strings: List<String>): ByteArray {
        val headerSize = 28
        val encoded = strings.map { s ->
            val raw = s.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(s.length)
            out.write(raw.size)
            out.write(raw)
            out.write(0)
            out.toByteArray()
        }
        val dataSize = encoded.sumOf { it.size }
        val stringsStart = headerSize + 4 * strings.size
        val size = stringsStart + ((dataSize + 3) and 3.inv())
        val chunk = ByteArray(size)
        putU16(chunk, 0, 0x0001)
        putU16(chunk, 2, headerSize)
        putU32(chunk, 4, size)
        putU32(chunk, 8, strings.size)
        putU32(chunk, 12, 0)
        putU32(chunk, 16, 0x100)
        putU32(chunk, 20, stringsStart)
        putU32(chunk, 24, 0)
        var offset = 0
        encoded.forEachIndexed { index, bytes ->
            putU32(chunk, headerSize + 4 * index, offset)
            bytes.copyInto(chunk, stringsStart + offset)
            offset += bytes.size
        }
        return chunk
    }

    private fun startElement(nameIndex: Int, attrs: List<ByteArray>): ByteArray {
        val size = 16 + 20 + 20 * attrs.size
        val chunk = ByteArray(size)
        putU16(chunk, 0, 0x0102)
        putU16(chunk, 2, 16)
        putU32(chunk, 4, size)
        putU32(chunk, 8, 1) // lineNumber
        putU32(chunk, 12, -1) // comment
        putU32(chunk, 16, -1) // ns
        putU32(chunk, 20, nameIndex)
        putU16(chunk, 24, 20) // attributeStart
        putU16(chunk, 26, 20) // attributeSize
        putU16(chunk, 28, attrs.size)
        putU16(chunk, 30, 0)
        putU16(chunk, 32, 0)
        putU16(chunk, 34, 0)
        attrs.forEachIndexed { index, bytes -> bytes.copyInto(chunk, 16 + 20 + index * 20) }
        return chunk
    }

    private fun attr(nameIndex: Int, stringIndex: Int? = null, boolean: Boolean? = null): ByteArray {
        val chunk = ByteArray(20)
        putU32(chunk, 0, -1) // ns
        putU32(chunk, 4, nameIndex)
        if (stringIndex != null) {
            putU32(chunk, 8, stringIndex) // rawValue
            chunk[15] = 0x03
            putU32(chunk, 16, stringIndex)
        } else {
            putU32(chunk, 8, -1)
            chunk[15] = 0x12
            putU32(chunk, 16, if (boolean == true) -1 else 0)
        }
        return chunk
    }

    private fun endElement(nameIndex: Int): ByteArray {
        val chunk = ByteArray(24)
        putU16(chunk, 0, 0x0103)
        putU16(chunk, 2, 16)
        putU32(chunk, 4, 24)
        putU32(chunk, 8, 1)
        putU32(chunk, 12, -1)
        putU32(chunk, 16, -1)
        putU32(chunk, 20, nameIndex)
        return chunk
    }
}
