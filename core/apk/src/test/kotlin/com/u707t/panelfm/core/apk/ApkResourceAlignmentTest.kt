package com.u707t.panelfm.core.apk

import com.android.apksig.ApkVerifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ApkResourceAlignmentTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun resourceOffset(file: File): Long = RandomAccessFile(file, "r").use { input ->
        fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        // Locate the central directory by EOCD; signature blocks are deliberately not scanned as ZIP entries.
        var end = input.length() - 22
        while (end >= maxOf(0, input.length() - 65557)) {
            input.seek(end)
            if (u32() == 0x06054b50L) break
            end--
        }
        check(end >= 0)
        input.seek(end + 16); var cursor = u32()
        while (true) {
            input.seek(cursor); check(u32() == 0x02014b50L)
            input.seek(cursor + 28); val nameLength = u16(); val extraLength = u16(); val commentLength = u16()
            input.seek(cursor + 42); val local = u32()
            val name = ByteArray(nameLength); input.readFully(name)
            if (name.toString(Charsets.UTF_8) == "resources.arsc") {
                input.seek(local); check(u32() == 0x04034b50L)
                input.seek(local + 26); val localName = u16(); val localExtra = u16()
                return@use local + 30 + localName + localExtra
            }
            cursor += 46 + nameLength + extraLength + commentLength
        }
        @Suppress("UNREACHABLE_CODE") 0L
    }
    private fun assertResource(file: File, expected: ByteArray) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("resources.arsc")
            assertEquals(ZipEntry.STORED, entry.method)
            assertArrayEquals(expected, zip.getInputStream(entry).use { it.readBytes() })
        }
        assertEquals("resources.arsc data must be 4-byte aligned", 0L, resourceOffset(file) % 4)
    }
    @Test fun `merged and finally signed resource tables are stored and four byte aligned`() {
        val bytes = ByteArray(517) { (it % 251).toByte() }
        // Vary the preceding header and compressed payload lengths to cover each alignment remainder.
        val key = ApkSigningKeyStore(tmp.newFolder("keys")).loadOrCreate()
        for (prefixLength in 0..7) {
            val base = zipOf(File(tmp.root, "base-$prefixLength.apk"), linkedMapOf(
                "AndroidManifest.xml" to Fixtures.baseManifest,
                "prefix${"x".repeat(prefixLength)}" to ByteArray(99 + prefixLength) { it.toByte() },
                "resources.arsc" to bytes,
            ))
            val merged = File(tmp.root, "merged-$prefixLength.apk")
            ApkMerger.merge(base, emptyList(), null, merged)
            assertResource(merged, bytes)
            val signed = File(tmp.root, "signed-$prefixLength.apk")
            ApkSignerService.sign(merged, signed, key, 26)
            assertResource(signed, bytes)
            val verified = ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(26).build().verify()
            assertTrue("Signature: ${verified.errors}", verified.isVerified)
            println("ARSC_SIGNED prefix=$prefixLength method=STORED offset=${resourceOffset(signed)} aligned4=true signature=true")
        }
    }
}
