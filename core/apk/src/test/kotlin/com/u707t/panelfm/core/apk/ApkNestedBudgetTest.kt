package com.u707t.panelfm.core.apk

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ApkNestedBudgetTest {
    private fun nested(vararg sizes: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            sizes.forEachIndexed { i, size ->
                // Deflated entries without preset sizes use data descriptors: local size is unknown.
                zip.putNextEntry(ZipEntry("assets/padding-$i"))
                val block = ByteArray(4096)
                var left = size
                while (left > 0) {
                    val n = minOf(left, block.size)
                    zip.write(block, 0, n)
                    left -= n
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("small manifest".toByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun `未知大小的前置条目不能通过nextEntry隐式解压绕过8MB预算`() {
        val zip = nested(9 * 1024 * 1024)
        ZipInputStream(ByteArrayInputStream(zip)).use { assertEquals(-1L, it.nextEntry.size) }
        assertNull(ApkBundleReader.readNestedManifestBytes(ByteArrayInputStream(zip)))
    }

    @Test fun `多个未知大小条目共享总扫描预算`() {
        assertNull(ApkBundleReader.readNestedManifestBytes(ByteArrayInputStream(nested(5 * 1024 * 1024, 4 * 1024 * 1024))))
    }

    @Test fun `预算内存在非首条清单仍可读取`() {
        assertArrayEquals("small manifest".toByteArray(),
            ApkBundleReader.readNestedManifestBytes(ByteArrayInputStream(nested(8192, 4096))))
    }
}
