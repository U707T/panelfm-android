package com.u707t.panelfm

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `source version changes invalidate same length cache`() {
        val first = CacheSourceVersion(size = 12, lastModified = 100L)
        val same = CacheSourceVersion(size = 12, lastModified = 100L)
        val changed = CacheSourceVersion(size = 12, lastModified = 101L)
        assertTrue(first.isReliable)
        assertTrue(first.cacheKey("remote://file") == same.cacheKey("remote://file"))
        assertNotEquals(first.cacheKey("remote://file"), changed.cacheKey("remote://file"))
    }

    @Test
    fun `archive in-flight reuse rejects changed version and password`() {
        val oldVersion = CacheSourceVersion(size = 12, lastModified = 100L)
        val newVersion = CacheSourceVersion(size = 12, lastModified = 101L)
        assertTrue(archiveVersionsCompatible(oldVersion, oldVersion))
        assertFalse(archiveVersionsCompatible(newVersion, oldVersion))
        val fingerprint = secretFingerprint("old-secret")
        assertTrue(archivePasswordsCompatible("old-secret", fingerprint))
        assertFalse(archivePasswordsCompatible("new-secret", fingerprint))
        // A null request means "no password preference" and may share a compatible in-flight open.
        assertTrue(archivePasswordsCompatible(null, fingerprint))
    }

    @Test
    fun `unknown source never reuses a prior cache key`() {
        val unknown = CacheSourceVersion(size = -1L, lastModified = -1L)
        val zeroMtime = CacheSourceVersion(size = 12L, lastModified = 0L)
        assertFalse(unknown.isReliable)
        assertFalse(zeroMtime.isReliable)
        assertFalse(archiveVersionsCompatible(unknown, unknown))
        assertNotEquals(unknown.cacheKey("remote://file"), unknown.cacheKey("remote://file"))
    }

    @Test
    fun `atomic publication leaves no partial file`() = runTest {
        val target = File(tmp.root, "cache/document.pdf")
        writeAtomically(target, expectedLength = 5L) { it.write("hello".toByteArray()) }
        assertTrue(target.isFile)
        assertTrue(target.readText() == "hello")
        assertTrue(target.parentFile.listFiles()!!.none { it.name.contains(".part-") })
    }

    @Test
    fun `failed write does not publish target or partial`() = runTest {
        val target = File(tmp.root, "cache/document.pdf")
        runCatching {
            writeAtomically(target) {
                it.write("partial".toByteArray())
                error("download failed")
            }
        }
        assertFalse(target.exists())
        assertTrue(target.parentFile?.listFiles()?.none { it.name.contains(".part-") } != false)
    }
}
